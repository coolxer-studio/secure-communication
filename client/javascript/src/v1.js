import { utf8Decode, utf8Encode } from './utf8.js';

var VERSION = 1;
var SUITE = 'P256_HKDF_SHA256_AES256_GCM';
var OUTER_METHOD = 'POST';
var MESSAGE_ENDPOINT = '/sc/v1/message';
var PROTECTED_MEDIA_TYPE = 'application/sc-protected+json';
var MAX_SEQUENCE = 9007199254740991;
var IDENTIFIER = /^[\x21-\x7e]{1,128}$/;
var BASE64URL = /^[A-Za-z0-9_-]+$/;

export function SecureError(code, message, details) {
  this.name = 'SecureError';
  this.code = code;
  this.message = message || code;
  this.httpStatus = details && (details.httpStatus == null
    ? details.status : details.httpStatus);
  this.traceId = details && details.traceId;
  this.cause = details && details.cause;
  if (Error.captureStackTrace) {
    Error.captureStackTrace(this, SecureError);
  }
}

SecureError.prototype = Object.create(Error.prototype);
SecureError.prototype.constructor = SecureError;

// Internal compatibility name used by the protocol implementation. It is not
// exported from the package root in 2.0.
export var SecureCommunicationError = SecureError;

function fail(code, message, cause) {
  throw new SecureCommunicationError(code, message, { cause: cause });
}

function getSubtle(explicit) {
  if (explicit) {
    return explicit;
  }
  if (typeof globalThis !== 'undefined'
      && globalThis.crypto
      && globalThis.crypto.subtle) {
    return globalThis.crypto.subtle;
  }
  fail('SC_CRYPTO_UNAVAILABLE', 'WebCrypto SubtleCrypto is required');
}

function asBytes(value, name) {
  if (value instanceof Uint8Array) {
    return new Uint8Array(value);
  }
  if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) {
    return new Uint8Array(value.slice(0));
  }
  if (typeof value === 'string') {
    return new Uint8Array(utf8Encode(value));
  }
  throw new TypeError(name + ' must be a string, Uint8Array, or ArrayBuffer');
}

function requireIdentifier(value, name) {
  if (typeof value !== 'string' || !IDENTIFIER.test(value)) {
    throw new TypeError(name + ' must contain 1-128 visible ASCII characters');
  }
}

function requirePrefix(value, name) {
  var bytes = asBytes(value, name);
  if (bytes.length !== 4) {
    throw new TypeError(name + ' must contain exactly 4 bytes');
  }
  return bytes;
}

export function normalizeV1ContentType(value) {
  var normalized = value
    ? String(value).split(';', 1)[0].trim().toLowerCase()
    : 'application/octet-stream';
  if (!/^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(normalized)) {
    throw new TypeError('contentType is invalid');
  }
  return normalized;
}

function uppercasePercentHex(value) {
  var index;
  var result = '';
  for (index = 0; index < value.length; index += 1) {
    if (value.charAt(index) === '%') {
      if (index + 2 >= value.length
          || !/^[0-9a-fA-F]{2}$/.test(value.slice(index + 1, index + 3))) {
        throw new TypeError('path contains invalid percent-encoding');
      }
      result += '%' + value.slice(index + 1, index + 3).toUpperCase();
      index += 2;
    } else {
      result += value.charAt(index);
    }
  }
  return result;
}

function queryParts(pair) {
  var index = pair.indexOf('=');
  return index < 0 ? [pair, ''] : [pair.slice(0, index), pair.slice(index + 1)];
}

export function normalizeV1Path(value) {
  if (typeof value !== 'string'
      || value.charAt(0) !== '/'
      || value.indexOf('\n') >= 0
      || value.indexOf('\r') >= 0
      || value.indexOf('#') >= 0
      || value.indexOf('://') >= 0
      || value.indexOf(' ') >= 0) {
    throw new TypeError('path must be an absolute encoded business path');
  }
  var split = value.split('?');
  if (split.length > 2) {
    throw new TypeError('path contains more than one query separator');
  }
  var path = uppercasePercentHex(split[0]);
  if (split.length === 1 || split[1] === '') {
    return path;
  }
  var pairs = split[1].split('&').filter(function nonEmpty(pair) {
    return pair !== '';
  }).map(uppercasePercentHex);
  pairs.sort(function compare(left, right) {
    var leftParts = queryParts(left);
    var rightParts = queryParts(right);
    if (leftParts[0] < rightParts[0]) { return -1; }
    if (leftParts[0] > rightParts[0]) { return 1; }
    if (leftParts[1] < rightParts[1]) { return -1; }
    if (leftParts[1] > rightParts[1]) { return 1; }
    return 0;
  });
  return pairs.length ? path + '?' + pairs.join('&') : path;
}

export function normalizeV1Method(value) {
  var method = String(value || 'GET').toUpperCase();
  if (!/^[A-Z]{3,16}$/.test(method)) {
    throw new TypeError('method is invalid');
  }
  return method;
}

function nonce(prefix, sequence) {
  var result = new Uint8Array(12);
  var view = new DataView(result.buffer);
  result.set(prefix, 0);
  view.setUint32(4, Math.floor(sequence / 4294967296), false);
  view.setUint32(8, sequence >>> 0, false);
  return result;
}

function equalBytes(left, right) {
  if (left.length !== right.length) {
    return false;
  }
  var difference = 0;
  var index;
  for (index = 0; index < left.length; index += 1) {
    difference |= left[index] ^ right[index];
  }
  return difference === 0;
}

function base64UrlEncode(bytes) {
  var binary = '';
  var index;
  for (index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index]);
  }
  if (typeof btoa !== 'function') {
    fail('SC_ENCODING_UNAVAILABLE', 'Base64 encoder is unavailable');
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlDecode(value) {
  if (typeof value !== 'string' || !BASE64URL.test(value)) {
    fail('SC_INVALID_ENVELOPE', 'Envelope Base64URL value is invalid');
  }
  if (typeof atob !== 'function') {
    fail('SC_ENCODING_UNAVAILABLE', 'Base64 decoder is unavailable');
  }
  var padded = value.replace(/-/g, '+').replace(/_/g, '/');
  while (padded.length % 4) {
    padded += '=';
  }
  var binary;
  try {
    binary = atob(padded);
  } catch (error) {
    fail('SC_INVALID_ENVELOPE', 'Envelope Base64URL value is invalid', error);
  }
  var bytes = new Uint8Array(binary.length);
  var index;
  for (index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function aad(direction, envelope) {
  var lines = [
    'SC1',
    direction,
    envelope.suite,
    envelope.kid,
    envelope.sid,
    String(envelope.ts),
    String(envelope.seq),
    envelope.rid,
    envelope.m,
    envelope.p,
    envelope.cty
  ];
  if (direction === 'response') {
    lines.push(String(envelope.st));
  }
  return new Uint8Array(utf8Encode(lines.join('\n')));
}

function validateEnvelope(envelope, session, now, allowedClockSkewMs) {
  var expectedKeys = ['ct', 'cty', 'kid', 'm', 'nonce', 'p', 'rid', 'seq', 'sid',
    'st', 'suite', 'ts', 'v'];
  var keys = Object.keys(envelope).sort();
  if (keys.length !== expectedKeys.length
      || keys.join('|') !== expectedKeys.join('|')) {
    fail('SC_INVALID_ENVELOPE', 'Envelope fields are invalid');
  }
  if (envelope.v !== VERSION) {
    fail('SC_UNSUPPORTED_VERSION', 'Envelope version is unsupported');
  }
  if (envelope.suite !== session.suite
      || envelope.kid !== session.kid
      || envelope.sid !== session.sid) {
    fail('SC_UNKNOWN_SESSION', 'Envelope session does not match');
  }
  if (!Number.isSafeInteger(envelope.seq) || envelope.seq < 1) {
    fail('SC_INVALID_ENVELOPE', 'Envelope sequence is invalid');
  }
  requireIdentifier(envelope.rid, 'envelope.rid');
  if (!Number.isInteger(envelope.st)
      || envelope.st < 100
      || envelope.st > 599) {
    fail('SC_INVALID_ENVELOPE', 'Envelope status is invalid');
  }
  if (!Number.isSafeInteger(envelope.ts)
      || Math.abs(now - envelope.ts) > allowedClockSkewMs) {
    fail('SC_REQUEST_EXPIRED', 'Envelope timestamp is outside the accepted window');
  }
  if (envelope.m !== OUTER_METHOD
      || envelope.p !== MESSAGE_ENDPOINT
      || envelope.cty !== PROTECTED_MEDIA_TYPE) {
    fail('SC_ROUTE_MISMATCH', 'Envelope route binding is not canonical');
  }
}

export async function importAesGcmSession(material, options) {
  options = options || {};
  requireIdentifier(material.kid, 'kid');
  requireIdentifier(material.sid, 'sid');
  var subtle = getSubtle(options.subtle);
  var requestKey = await subtle.importKey(
    'raw', asBytes(material.requestKey, 'requestKey'),
    { name: 'AES-GCM', length: 256 }, false, ['encrypt']);
  var responseKey = await subtle.importKey(
    'raw', asBytes(material.responseKey, 'responseKey'),
    { name: 'AES-GCM', length: 256 }, false, ['decrypt']);
  return {
    suite: SUITE,
    kid: material.kid,
    sid: material.sid,
    requestKey: requestKey,
    responseKey: responseKey,
    requestNoncePrefix: requirePrefix(
      material.requestNoncePrefix, 'requestNoncePrefix'),
    responseNoncePrefix: requirePrefix(
      material.responseNoncePrefix, 'responseNoncePrefix'),
    expiresAt: material.expiresAt == null ? null : Number(material.expiresAt)
  };
}

/**
 * Derives direction-separated session material from an already authenticated
 * P-256 ECDH handshake transcript.
 */
export async function deriveAesGcmSession(material, options) {
  options = options || {};
  var subtle = getSubtle(options.subtle);
  var transcriptHash = asBytes(material.transcriptHash, 'transcriptHash');
  if (transcriptHash.length !== 32) {
    throw new TypeError('transcriptHash must contain exactly 32 bytes');
  }
  var sharedSecret;
  var hkdfKey;
  var derived;
  try {
    sharedSecret = await subtle.deriveBits(
      { name: 'ECDH', public: material.peerEphemeralPublicKey },
      material.localEphemeralPrivateKey,
      256);
    hkdfKey = await subtle.importKey(
      'raw', sharedSecret, 'HKDF', false, ['deriveBits']);
    derived = await subtle.deriveBits(
      {
        name: 'HKDF',
        hash: 'SHA-256',
        salt: transcriptHash,
        info: new Uint8Array(utf8Encode(
          'SC1/session/' + SUITE + '/' + material.sid))
      },
      hkdfKey,
      72 * 8);
  } catch (error) {
    fail('SC_HANDSHAKE_FAILED', 'Session key derivation failed', error);
  }
  var bytes = new Uint8Array(derived);
  var session = await importAesGcmSession({
    kid: material.kid,
    sid: material.sid,
    requestKey: bytes.slice(0, 32),
    responseKey: bytes.slice(32, 64),
    requestNoncePrefix: bytes.slice(64, 68),
    responseNoncePrefix: bytes.slice(68, 72),
    expiresAt: material.expiresAt
  }, { subtle: subtle });
  bytes.fill(0);
  new Uint8Array(sharedSecret).fill(0);
  return session;
}

export async function verifyP256Transcript(
  transcriptHash, p1363Signature, serverSigningPublicKey, options) {
  options = options || {};
  var hash = asBytes(transcriptHash, 'transcriptHash');
  var signature = asBytes(p1363Signature, 'p1363Signature');
  if (hash.length !== 32 || signature.length !== 64) {
    return false;
  }
  try {
    return await getSubtle(options.subtle).verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      serverSigningPublicKey,
      signature,
      hash);
  } catch (error) {
    fail('SC_HANDSHAKE_FAILED', 'Transcript verification failed', error);
  }
}

export function createV1Codec(session, options) {
  options = options || {};
  if (!session || session.suite !== SUITE) {
    throw new TypeError('A supported v1 session is required');
  }
  requireIdentifier(session.kid, 'session.kid');
  requireIdentifier(session.sid, 'session.sid');
  var requestPrefix = requirePrefix(
    session.requestNoncePrefix, 'session.requestNoncePrefix');
  var responsePrefix = requirePrefix(
    session.responseNoncePrefix, 'session.responseNoncePrefix');
  var subtle = getSubtle(options.subtle);
  var clock = options.now || Date.now;
  var allowedClockSkewMs = options.allowedClockSkewMs == null
    ? 300000
    : Number(options.allowedClockSkewMs);
  var nextSequence = options.initialSequence == null
    ? 1
    : Number(options.initialSequence);

  async function encodeRequest(request) {
    request = request || {};
    if (!Number.isSafeInteger(nextSequence)
        || nextSequence < 1
        || nextSequence > MAX_SEQUENCE) {
      fail('SC_SEQUENCE_EXHAUSTED', 'Session sequence is exhausted');
    }
    var sequence = nextSequence;
    nextSequence += 1;
    var timestamp = Number(clock());
    if (!Number.isSafeInteger(timestamp)) {
      fail('SC_CLOCK_INVALID', 'Clock must return epoch milliseconds');
    }
    if (session.expiresAt != null && timestamp >= session.expiresAt) {
      fail('SC_UNKNOWN_SESSION', 'Session has expired');
    }
    requireIdentifier(request.requestId, 'requestId');
    var envelope = {
      v: VERSION,
      suite: session.suite,
      kid: session.kid,
      sid: session.sid,
      ts: timestamp,
      seq: sequence,
      rid: request.requestId,
      m: OUTER_METHOD,
      p: MESSAGE_ENDPOINT,
      cty: PROTECTED_MEDIA_TYPE,
      st: 0,
      nonce: '',
      ct: ''
    };
    var requestNonce = nonce(requestPrefix, sequence);
    envelope.nonce = base64UrlEncode(requestNonce);
    var sealed;
    try {
      sealed = await subtle.encrypt(
        {
          name: 'AES-GCM',
          iv: requestNonce,
          additionalData: aad('request', envelope),
          tagLength: 128
        },
        session.requestKey,
        asBytes(request.body == null ? new Uint8Array(0) : request.body, 'body'));
    } catch (error) {
      fail('SC_CRYPTO_FAILED', 'Request encryption failed', error);
    }
    envelope.ct = base64UrlEncode(new Uint8Array(sealed));
    return JSON.stringify(envelope);
  }

  async function decodeResponse(encoded, expected) {
    var envelope;
    try {
      envelope = typeof encoded === 'string' ? JSON.parse(encoded) : encoded;
    } catch (error) {
      fail('SC_INVALID_ENVELOPE', 'Response is not valid JSON', error);
    }
    if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) {
      fail('SC_INVALID_ENVELOPE', 'Response envelope is invalid');
    }
    validateEnvelope(envelope, session, Number(clock()), allowedClockSkewMs);
    expected = expected || {};
    if ((expected.sequence != null && envelope.seq !== expected.sequence)
        || (expected.requestId && envelope.rid !== expected.requestId)) {
      fail('SC_ROUTE_MISMATCH', 'Response does not match the request');
    }
    var responseNonce = base64UrlDecode(envelope.nonce);
    if (!equalBytes(responseNonce, nonce(responsePrefix, envelope.seq))) {
      fail('SC_INVALID_ENVELOPE', 'Response nonce is invalid');
    }
    var opened;
    try {
      opened = await subtle.decrypt(
        {
          name: 'AES-GCM',
          iv: responseNonce,
          additionalData: aad('response', envelope),
          tagLength: 128
        },
        session.responseKey,
        base64UrlDecode(envelope.ct));
    } catch (error) {
      fail('SC_AUTHENTICATION_FAILED', 'Response authentication failed', error);
    }
    return {
      body: new Uint8Array(opened),
      contentType: envelope.cty,
      status: envelope.st,
      envelope: envelope,
      text: function text() {
        return utf8Decode(Array.prototype.slice.call(new Uint8Array(opened)));
      }
    };
  }

  return {
    encodeRequest: encodeRequest,
    decodeResponse: decodeResponse
  };
}

export var V1_INTERNATIONAL_SUITE = SUITE;
export var V1_ENVELOPE_MEDIA_TYPE = 'application/sc-envelope+json';
