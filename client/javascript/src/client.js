import {
  SecureCommunicationError,
  V1_INTERNATIONAL_SUITE,
  createV1Codec,
  deriveAesGcmSession,
  verifyP256Transcript
} from './v1.js';
import { createSecureFetch } from './transport.js';
import { utf8Encode } from './utf8.js';

function subtle() {
  if (!globalThis.crypto || !globalThis.crypto.subtle) {
    throw new SecureCommunicationError(
      'SC_CRYPTO_UNAVAILABLE', 'WebCrypto is required');
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

async function responseJson(response) {
  var body;
  try {
    body = await response.json();
  } catch (error) {
    throw new SecureCommunicationError(
      'SC_HANDSHAKE_FAILED', 'Handshake response is invalid', { cause: error });
  }
  if (!response.ok) {
    throw new SecureCommunicationError(
      body.code || 'SC_HANDSHAKE_FAILED', body.message || 'Handshake failed',
      { status: response.status, traceId: body.traceId });
  }
  return body;
}

export function MemoryIdentityStore() {
  var value = null;
  this.load = async function load() { return value; };
  this.save = async function save(identity) { value = identity; };
}

export function IndexedDbIdentityStore(configuration) {
  configuration = configuration || {};
  var databaseName = configuration.databaseName || 'coolxer-secure-communication';
  var key = configuration.key || 'installation-v1';

  function database() {
    if (typeof indexedDB === 'undefined') {
      throw new SecureCommunicationError(
        'SC_IDENTITY_STORE_UNAVAILABLE', 'IndexedDB is required');
    }
    return new Promise(function open(resolve, reject) {
      var request = indexedDB.open(databaseName, 1);
      request.onupgradeneeded = function upgrade() {
        if (!request.result.objectStoreNames.contains('identity')) {
          request.result.createObjectStore('identity');
        }
      };
      request.onsuccess = function success() { resolve(request.result); };
      request.onerror = function failed() { reject(request.error); };
    });
  }

  this.load = async function load() {
    var db = await database();
    return new Promise(function read(resolve, reject) {
      var transaction = db.transaction('identity', 'readonly');
      var request = transaction.objectStore('identity').get(key);
      request.onsuccess = function success() { resolve(request.result || null); };
      request.onerror = function failed() { reject(request.error); };
      transaction.oncomplete = function complete() { db.close(); };
    });
  };

  this.save = async function save(identity) {
    var db = await database();
    return new Promise(function write(resolve, reject) {
      var transaction = db.transaction('identity', 'readwrite');
      transaction.objectStore('identity').put(identity, key);
      transaction.oncomplete = function complete() { db.close(); resolve(); };
      transaction.onerror = function failed() { db.close(); reject(transaction.error); };
    });
  };
}

async function installationIdentity(store) {
  var existing = await store.load();
  if (existing && existing.deviceId && existing.privateKey && existing.publicKey) {
    return existing;
  }
  var keys = await subtle().generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  var created = {
    deviceId: globalThis.crypto.randomUUID(),
    privateKey: keys.privateKey,
    publicKey: keys.publicKey
  };
  await store.save(created);
  return created;
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
  configuration = configuration || {};
  if (!configuration.baseUrl || !configuration.appId) {
    throw new TypeError('baseUrl and appId are required');
  }
  var baseUrl = String(configuration.baseUrl).replace(/\/+$/, '');
  if (!configuration.allowInsecureForTesting && !baseUrl.startsWith('https://')) {
    throw new TypeError('baseUrl must use HTTPS');
  }
  var fetchImplementation = configuration.fetch || globalThis.fetch;
  if (!fetchImplementation) throw new TypeError('fetch implementation is required');
  var store = configuration.identityStore || new IndexedDbIdentityStore();
  var deviceType = String(configuration.deviceType || 'H5').toUpperCase();
  var enrollmentToken = null;
  var secureFetch = null;
  var session = null;

  this.enroll = function enroll(token) {
    if (deviceType === 'H5') {
      throw new SecureCommunicationError(
        'SC_ENROLLMENT_NOT_SUPPORTED', 'H5 enrollment uses Origin policy');
    }
    if (!token || typeof token !== 'string') throw new TypeError('token is required');
    enrollmentToken = token;
  };

  this.initialize = async function initialize() {
    if (secureFetch && session && Date.now() < session.expiresAt) return this;
    var identity = await installationIdentity(store);
    var ephemeral = await subtle().generateKey(
      { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    var clientEphemeral = new Uint8Array(await subtle().exportKey('spki', ephemeral.publicKey));
    var installation = new Uint8Array(await subtle().exportKey('spki', identity.publicKey));
    var startRequest = {
      v: 1,
      suite: V1_INTERNATIONAL_SUITE,
      appId: configuration.appId,
      deviceId: identity.deviceId,
      deviceType: deviceType,
      clientEphemeralPublicKey: b64(clientEphemeral),
      installationPublicKey: b64(installation),
      enrollmentToken: enrollmentToken,
      timestamp: Date.now()
    };
    var start = await responseJson(await fetchImplementation(
      baseUrl + '/sc/v1/handshake', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        credentials: configuration.credentials,
        cache: 'no-store',
        redirect: 'error',
        body: JSON.stringify(startRequest)
      }));
    enrollmentToken = null;
    if (start.v !== 1 || start.suite !== V1_INTERNATIONAL_SUITE
        || !configuration.serverTrustAnchors
        || !configuration.serverTrustAnchors[start.kid]) {
      throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Untrusted server identity');
    }
    var serverIdentity = unb64(start.serverIdentityPublicKey);
    var pinned = unb64(configuration.serverTrustAnchors[start.kid]);
    if (!equal(serverIdentity, pinned)) {
      throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Server identity pin mismatch');
    }
    var serverEphemeral = unb64(start.serverEphemeralPublicKey);
    var hash = new Uint8Array(await subtle().digest(
      'SHA-256', new Uint8Array(utf8Encode(transcript(
        start, startRequest, clientEphemeral, installation,
        serverIdentity, serverEphemeral)))));
    var serverSigningKey = await subtle().importKey(
      'spki', serverIdentity, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    if (!await verifyP256Transcript(
      hash, unb64(start.signature), serverSigningKey)) {
      throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Invalid server proof');
    }
    var peerEphemeral = await subtle().importKey(
      'spki', serverEphemeral, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    session = await deriveAesGcmSession({
      kid: start.kid,
      sid: start.sid,
      localEphemeralPrivateKey: ephemeral.privateKey,
      peerEphemeralPublicKey: peerEphemeral,
      transcriptHash: hash,
      expiresAt: start.expiresAt
    });
    var proof = new Uint8Array(await subtle().sign(
      { name: 'ECDSA', hash: 'SHA-256' }, identity.privateKey, hash));
    await responseJson(await fetchImplementation(
      baseUrl + '/sc/v1/handshake/finish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        credentials: configuration.credentials,
        cache: 'no-store',
        redirect: 'error',
        body: JSON.stringify({ kid: start.kid, sid: start.sid, proof: b64(proof) })
      }));
    secureFetch = createSecureFetch({
      baseUrl: baseUrl,
      codec: createV1Codec(session),
      fetch: fetchImplementation,
      credentials: configuration.credentials,
      protectedHeaderNames: configuration.protectedHeaderNames || ['code'],
      allowInsecureForTesting: configuration.allowInsecureForTesting
    });
    return this;
  };

  this.request = async function request(method, path, protectedHeaders, body, requestId, options) {
    await this.initialize();
    options = options || {};
    return secureFetch(path, {
      method: method,
      headers: { 'Content-Type': options.contentType || 'application/json' },
      protectedHeaders: Object.assign({}, protectedHeaders || {}),
      requestId: requestId,
      body: body,
      signal: options.signal,
      credentials: configuration.credentials
    });
  };

  this.closeSession = function closeSession() {
    session = null;
    secureFetch = null;
  };
}

export function createSecureClient(configuration) {
  return new SecureClient(configuration);
}
