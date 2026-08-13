import {
  SecureError,
  V1_INTERNATIONAL_SUITE,
  createV1Codec,
  deriveAesGcmSession,
  normalizeV1ContentType,
  normalizeV1Method,
  normalizeV1Path,
  verifyP256Transcript
} from './v1.js';
import { createSecureFetch } from './transport.js';
import { utf8Decode, utf8Encode } from './utf8.js';

export { SecureError };

var DEVICE_TYPES = ['H5', 'HOST', 'SERVER', 'ANDROID', 'IOS', 'EMULATOR'];

function subtle() {
  if (!globalThis.crypto || !globalThis.crypto.subtle) {
    throw new SecureError('SC_CRYPTO_UNAVAILABLE', 'WebCrypto is required');
  }
  return globalThis.crypto.subtle;
}

function b64(bytes) {
  var value = '';
  var array = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (var index = 0; index < array.length; index += 1) {
    value += String.fromCharCode(array[index]);
  }
  return btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function unb64(value) {
  var padded = String(value).replace(/-/g, '+').replace(/_/g, '/');
  while (padded.length % 4) padded += '=';
  var binary = atob(padded);
  var result = new Uint8Array(binary.length);
  for (var index = 0; index < binary.length; index += 1) {
    result[index] = binary.charCodeAt(index);
  }
  return result;
}

function equal(left, right) {
  if (left.length !== right.length) return false;
  var value = 0;
  for (var index = 0; index < left.length; index += 1) value |= left[index] ^ right[index];
  return value === 0;
}

function newRequestId() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  if (!globalThis.crypto || typeof globalThis.crypto.getRandomValues !== 'function') {
    throw new SecureError('SC_CRYPTO_UNAVAILABLE', 'WebCrypto random generator is required');
  }
  return b64(globalThis.crypto.getRandomValues(new Uint8Array(16)));
}

function bodyBytes(value) {
  if (value == null) return new Uint8Array(0);
  if (value instanceof Uint8Array) return new Uint8Array(value);
  if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) {
    return new Uint8Array(value.slice(0));
  }
  if (typeof value === 'string') return new Uint8Array(utf8Encode(value));
  throw new TypeError('body must be a string, Uint8Array, or ArrayBuffer');
}

function isLoopback(hostname) {
  var host = String(hostname || '').toLowerCase();
  if (host === 'localhost' || host === '[::1]' || host === '::1') return true;
  var parts = host.split('.');
  return parts.length === 4 && parts[0] === '127'
    && parts.every(function valid(part) {
      return /^\d{1,3}$/.test(part) && Number(part) <= 255;
    });
}

function normalizedBaseUrl(value, allowLoopback) {
  var parsed;
  try { parsed = new URL(String(value)); } catch (error) {
    throw new TypeError('baseUrl is invalid');
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new TypeError('baseUrl is invalid');
  }
  if (parsed.protocol !== 'https:'
      && !(allowLoopback && parsed.protocol === 'http:' && isLoopback(parsed.hostname))) {
    throw new TypeError('baseUrl must use HTTPS');
  }
  return parsed.toString().replace(/\/+$/, '');
}

function mapExecutionError(error) {
  if (error instanceof SecureError) return error;
  if (error && error.name === 'AbortError') {
    return new SecureError(
      error.__secureTimeout ? 'SC_REQUEST_TIMEOUT' : 'SC_REQUEST_CANCELLED',
      error.__secureTimeout ? 'Secure request timed out' : 'Secure request was cancelled',
      { cause: error });
  }
  return new SecureError('SC_NETWORK_FAILED', 'Secure network request failed', { cause: error });
}

function executionSignal(callerSignal, timeoutMillis) {
  var controller = new AbortController();
  var timedOut = false;
  var timer = setTimeout(function timeout() {
    timedOut = true;
    controller.abort();
  }, timeoutMillis);
  var cancel = function cancel() { controller.abort(); };
  if (callerSignal) {
    if (callerSignal.aborted) cancel();
    else callerSignal.addEventListener('abort', cancel, { once: true });
  }
  return {
    signal: controller.signal,
    cleanup: function cleanup() {
      clearTimeout(timer);
      if (callerSignal) callerSignal.removeEventListener('abort', cancel);
    },
    error: function error(original) {
      if (timedOut) {
        var timeoutError = new SecureError(
          'SC_REQUEST_TIMEOUT', 'Secure request timed out', { cause: original });
        return timeoutError;
      }
      return mapExecutionError(original);
    }
  };
}

async function waitFor(promise, signal) {
  if (!signal) return promise;
  if (signal.aborted) {
    throw new SecureError('SC_REQUEST_CANCELLED', 'Secure request was cancelled');
  }
  var cancel;
  var cancelled = new Promise(function create(_, reject) {
    cancel = function cancelWaiter() {
      reject(new SecureError('SC_REQUEST_CANCELLED', 'Secure request was cancelled'));
    };
    signal.addEventListener('abort', cancel, { once: true });
  });
  try { return await Promise.race([promise, cancelled]); }
  finally { signal.removeEventListener('abort', cancel); }
}

async function responseJson(response) {
  var body;
  try { body = await response.json(); } catch (error) {
    throw new SecureError('SC_HANDSHAKE_FAILED', 'Handshake response is invalid', { cause: error });
  }
  if (!response.ok) {
    throw new SecureError(
      body.code || 'SC_HANDSHAKE_FAILED', body.message || 'Handshake failed',
      { httpStatus: response.status, traceId: body.traceId });
  }
  return body;
}

export function SecureClientConfig(configuration) {
  configuration = configuration || {};
  if (!configuration.appId || !/^[A-Za-z0-9._:@/-]{1,128}$/.test(configuration.appId)) {
    throw new TypeError('appId is invalid');
  }
  this.allowInsecureLoopbackForTesting = configuration.allowInsecureLoopbackForTesting === true;
  this.baseUrl = normalizedBaseUrl(
    configuration.baseUrl, this.allowInsecureLoopbackForTesting);
  this.appId = configuration.appId;
  this.deviceType = String(configuration.deviceType || 'H5').toUpperCase();
  if (DEVICE_TYPES.indexOf(this.deviceType) < 0) throw new TypeError('deviceType is invalid');
  if (!configuration.serverTrustAnchors
      || Object.keys(configuration.serverTrustAnchors).length === 0) {
    throw new TypeError('serverTrustAnchors are required');
  }
  this.serverTrustAnchors = Object.assign({}, configuration.serverTrustAnchors);
  this.identityStore = configuration.identityStore || new IndexedDbIdentityStore();
  if (!this.identityStore || typeof this.identityStore.loadOrCreate !== 'function') {
    throw new TypeError('identityStore.loadOrCreate is required');
  }
  this.requestTimeoutMillis = configuration.requestTimeoutMillis == null
    ? 15000 : Number(configuration.requestTimeoutMillis);
  this.allowedClockSkewMillis = configuration.allowedClockSkewMillis == null
    ? 120000 : Number(configuration.allowedClockSkewMillis);
  if (!(this.requestTimeoutMillis > 0) || !(this.allowedClockSkewMillis >= 0)) {
    throw new TypeError('timeout configuration is invalid');
  }
  this.fetch = configuration.fetch || globalThis.fetch;
  if (typeof this.fetch !== 'function') throw new TypeError('fetch implementation is required');
  this.credentials = configuration.credentials;
  Object.freeze(this.serverTrustAnchors);
  Object.freeze(this);
}

export function SecureRequest(configuration) {
  configuration = configuration || {};
  this.method = normalizeV1Method(configuration.method || 'GET');
  this.logicalPath = normalizeV1Path(configuration.logicalPath);
  this.contentType = normalizeV1ContentType(
    configuration.contentType || 'application/octet-stream');
  this.protectedHeaders = Object.assign({}, configuration.protectedHeaders || {});
  Object.keys(this.protectedHeaders).forEach(function validateHeader(name) {
    var normalized = name.toLowerCase();
    var value = String(configuration.protectedHeaders[name]);
    if (!/^[a-z0-9-]{1,64}$/.test(normalized) || value.length > 8192
        || /[\r\n]/.test(value)) {
      throw new TypeError('protected header is invalid');
    }
  });
  Object.freeze(this.protectedHeaders);
  this.body = bodyBytes(configuration.body);
  this.requestId = configuration.requestId || null;
  if (this.requestId != null && !/^[\x21-\x7e]{1,128}$/.test(this.requestId)) {
    throw new TypeError('requestId is invalid');
  }
}

export function SecureResponse(status, contentType, body) {
  this.status = status;
  this.contentType = normalizeV1ContentType(contentType);
  this.body = bodyBytes(body);
  this.ok = status >= 200 && status < 300;
}
SecureResponse.prototype.text = function text() {
  return utf8Decode(Array.prototype.slice.call(this.body));
};
SecureResponse.prototype.json = function json() { return JSON.parse(this.text()); };

export function MemoryIdentityStore() { this.identities = {}; }
MemoryIdentityStore.prototype.loadOrCreate = async function loadOrCreate(appId) {
  if (!this.identities[appId]) this.identities[appId] = (await createIdentity()).identity;
  return this.identities[appId];
};

export function IndexedDbIdentityStore(configuration) {
  configuration = configuration || {};
  this.databaseName = configuration.databaseName || 'coolxer-secure-communication-v2';
}
IndexedDbIdentityStore.prototype.loadOrCreate = async function loadOrCreate(appId) {
  if (typeof indexedDB === 'undefined') {
    throw new SecureError('SC_IDENTITY_STORE_UNAVAILABLE', 'IndexedDB is required');
  }
  var databaseName = this.databaseName;
  var db = await new Promise(function open(resolve, reject) {
    var request = indexedDB.open(databaseName, 1);
    request.onupgradeneeded = function upgrade() {
      if (!request.result.objectStoreNames.contains('identity')) {
        request.result.createObjectStore('identity');
      }
    };
    request.onsuccess = function success() { resolve(request.result); };
    request.onerror = function failed() { reject(request.error); };
  });
  var existing = await new Promise(function read(resolve, reject) {
    var transaction = db.transaction('identity', 'readonly');
    var request = transaction.objectStore('identity').get('installation-v2:' + appId);
    request.onsuccess = function success() { resolve(request.result || null); };
    request.onerror = function failed() { reject(request.error); };
  });
  if (existing) { db.close(); return identityFromStored(existing); }
  var created = await createIdentity();
  await new Promise(function write(resolve, reject) {
    var transaction = db.transaction('identity', 'readwrite');
    transaction.objectStore('identity').put(created.stored, 'installation-v2:' + appId);
    transaction.oncomplete = resolve;
    transaction.onerror = function failed() { reject(transaction.error); };
  });
  db.close();
  return created.identity;
};

function identityFromStored(stored) {
  return {
    deviceId: stored.deviceId,
    publicKeySPKI: async function publicKeySPKI() {
      return new Uint8Array(await subtle().exportKey('spki', stored.publicKey));
    },
    sign: async function sign(data) {
      return new Uint8Array(await subtle().sign(
        { name: 'ECDSA', hash: 'SHA-256' }, stored.privateKey, data));
    }
  };
}

async function createIdentity() {
  var keys = await subtle().generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  var stored = {
    deviceId: globalThis.crypto.randomUUID(),
    privateKey: keys.privateKey,
    publicKey: keys.publicKey
  };
  return { identity: identityFromStored(stored), stored: stored };
}

function transcript(response, request, clientEphemeral, installation, serverIdentity, serverEphemeral) {
  return [
    'SC1-HANDSHAKE', '1', V1_INTERNATIONAL_SUITE, request.appId,
    request.deviceId, request.deviceType, b64(clientEphemeral), b64(installation),
    b64(serverIdentity), b64(serverEphemeral), response.kid, response.sid,
    String(response.createdAt), String(response.expiresAt)
  ].join('\n');
}

export function SecureClient(configuration) {
  var config = configuration instanceof SecureClientConfig
    ? configuration : new SecureClientConfig(configuration);
  var enrollmentToken = null;
  var secureFetch = null;
  var session = null;
  var initializePromise = null;
  var generation = 0;

  this.enroll = function enroll(token) {
    if (config.deviceType === 'H5') {
      throw new SecureError('SC_ENROLLMENT_NOT_SUPPORTED', 'H5 enrollment uses Origin policy');
    }
    if (!token || typeof token !== 'string') throw new TypeError('token is required');
    enrollmentToken = token;
  };

  var performInitialize = async function performInitialize(expectedGeneration) {
    var execution = executionSignal(null, config.requestTimeoutMillis);
    var tokenUsed = enrollmentToken;
    try {
      var identity = await config.identityStore.loadOrCreate(config.appId);
      if (!identity || !identity.deviceId || typeof identity.publicKeySPKI !== 'function'
          || typeof identity.sign !== 'function') {
        throw new SecureError('SC_IDENTITY_FAILED', 'Installation identity is invalid');
      }
      var ephemeral = await subtle().generateKey(
        { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
      var clientEphemeral = new Uint8Array(await subtle().exportKey('spki', ephemeral.publicKey));
      var installation = new Uint8Array(await identity.publicKeySPKI());
      var startRequest = {
        v: 1, suite: V1_INTERNATIONAL_SUITE, appId: config.appId,
        deviceId: identity.deviceId, deviceType: config.deviceType,
        clientEphemeralPublicKey: b64(clientEphemeral),
        installationPublicKey: b64(installation),
        enrollmentToken: tokenUsed, timestamp: Date.now()
      };
      var start = await responseJson(await config.fetch(config.baseUrl + '/sc/v1/handshake', {
        method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        credentials: config.credentials, cache: 'no-store', redirect: 'error',
        body: JSON.stringify(startRequest), signal: execution.signal
      }));
      if (start.v !== 1 || start.suite !== V1_INTERNATIONAL_SUITE
          || !config.serverTrustAnchors[start.kid]) {
        throw new SecureError('SC_HANDSHAKE_FAILED', 'Untrusted server identity');
      }
      var serverIdentity = unb64(start.serverIdentityPublicKey);
      if (!equal(serverIdentity, unb64(config.serverTrustAnchors[start.kid]))) {
        throw new SecureError('SC_HANDSHAKE_FAILED', 'Server identity pin mismatch');
      }
      var serverEphemeral = unb64(start.serverEphemeralPublicKey);
      var hash = new Uint8Array(await subtle().digest('SHA-256', new Uint8Array(utf8Encode(
        transcript(start, startRequest, clientEphemeral, installation, serverIdentity, serverEphemeral)))));
      var serverSigningKey = await subtle().importKey(
        'spki', serverIdentity, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
      if (!await verifyP256Transcript(hash, unb64(start.signature), serverSigningKey)) {
        throw new SecureError('SC_HANDSHAKE_FAILED', 'Invalid server proof');
      }
      var peerEphemeral = await subtle().importKey(
        'spki', serverEphemeral, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
      var established = await deriveAesGcmSession({
        kid: start.kid, sid: start.sid, localEphemeralPrivateKey: ephemeral.privateKey,
        peerEphemeralPublicKey: peerEphemeral, transcriptHash: hash, expiresAt: start.expiresAt
      });
      var proof = new Uint8Array(await identity.sign(hash));
      var finish = await responseJson(await config.fetch(
        config.baseUrl + '/sc/v1/handshake/finish', {
          method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          credentials: config.credentials, cache: 'no-store', redirect: 'error',
          body: JSON.stringify({ kid: start.kid, sid: start.sid, proof: b64(proof) }),
          signal: execution.signal
        }));
      if (!finish.active) throw new SecureError('SC_HANDSHAKE_FAILED', 'Handshake was not activated');
      if (generation !== expectedGeneration) {
        throw new SecureError('SC_REQUEST_CANCELLED', 'Initialization was invalidated');
      }
      session = established;
      secureFetch = createSecureFetch({
        baseUrl: config.baseUrl, codec: createV1Codec(session, {
          allowedClockSkewMs: config.allowedClockSkewMillis
        }), fetch: config.fetch, credentials: config.credentials,
        allowInsecureLoopbackForTesting: config.allowInsecureLoopbackForTesting
      });
      if (enrollmentToken === tokenUsed) enrollmentToken = null;
    } catch (error) {
      session = null;
      secureFetch = null;
      throw execution.error(error);
    } finally { execution.cleanup(); }
  };

  this.initialize = async function initialize(options) {
    options = options || {};
    if (secureFetch && session && Date.now() < session.expiresAt) return this;
    if (!initializePromise) {
      var expectedGeneration = generation;
      initializePromise = performInitialize(expectedGeneration).finally(function clear() {
        initializePromise = null;
      });
    }
    await waitFor(initializePromise, options.signal);
    return this;
  };

  this.request = async function request(value, options) {
    options = options || {};
    var secureRequest = new SecureRequest(value);
    await this.initialize({ signal: options.signal });
    var execution = executionSignal(options.signal, config.requestTimeoutMillis);
    try {
      var result = await secureFetch(secureRequest.logicalPath, {
        method: secureRequest.method,
        protectedHeaders: secureRequest.protectedHeaders,
        body: secureRequest.body,
        requestId: secureRequest.requestId || newRequestId(),
        contentType: secureRequest.contentType,
        signal: execution.signal,
        credentials: config.credentials
      });
      return new SecureResponse(result.status, result.contentType, result.body);
    } catch (error) {
      var mapped = execution.error(error);
      if (mapped.code === 'SC_UNKNOWN_SESSION') this.closeSession();
      throw mapped;
    } finally { execution.cleanup(); }
  };

  this.closeSession = function closeSession() {
    generation += 1;
    session = null;
    secureFetch = null;
  };
}

export function createSecureClient(configuration) { return new SecureClient(configuration); }
