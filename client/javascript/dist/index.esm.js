function asyncGeneratorStep(n, t, e, r, o, a, c) {
  try {
    var i = n[a](c),
      u = i.value;
  } catch (n) {
    return void e(n);
  }
  i.done ? t(u) : Promise.resolve(u).then(r, o);
}
function _asyncToGenerator(n) {
  return function () {
    var t = this,
      e = arguments;
    return new Promise(function (r, o) {
      var a = n.apply(t, e);
      function _next(n) {
        asyncGeneratorStep(a, r, o, _next, _throw, "next", n);
      }
      function _throw(n) {
        asyncGeneratorStep(a, r, o, _next, _throw, "throw", n);
      }
      _next(void 0);
    });
  };
}
function _regenerator() {
  /*! regenerator-runtime -- Copyright (c) 2014-present, Facebook, Inc. -- license (MIT): https://github.com/babel/babel/blob/main/packages/babel-helpers/LICENSE */
  var e,
    t,
    r = "function" == typeof Symbol ? Symbol : {},
    n = r.iterator || "@@iterator",
    o = r.toStringTag || "@@toStringTag";
  function i(r, n, o, i) {
    var c = n && n.prototype instanceof Generator ? n : Generator,
      u = Object.create(c.prototype);
    return _regeneratorDefine(u, "_invoke", function (r, n, o) {
      var i,
        c,
        u,
        f = 0,
        p = o || [],
        y = false,
        G = {
          p: 0,
          n: 0,
          v: e,
          a: d,
          f: d.bind(e, 4),
          d: function (t, r) {
            return i = t, c = 0, u = e, G.n = r, a;
          }
        };
      function d(r, n) {
        for (c = r, u = n, t = 0; !y && f && !o && t < p.length; t++) {
          var o,
            i = p[t],
            d = G.p,
            l = i[2];
          r > 3 ? (o = l === n) && (u = i[(c = i[4]) ? 5 : (c = 3, 3)], i[4] = i[5] = e) : i[0] <= d && ((o = r < 2 && d < i[1]) ? (c = 0, G.v = n, G.n = i[1]) : d < l && (o = r < 3 || i[0] > n || n > l) && (i[4] = r, i[5] = n, G.n = l, c = 0));
        }
        if (o || r > 1) return a;
        throw y = true, n;
      }
      return function (o, p, l) {
        if (f > 1) throw TypeError("Generator is already running");
        for (y && 1 === p && d(p, l), c = p, u = l; (t = c < 2 ? e : u) || !y;) {
          i || (c ? c < 3 ? (c > 1 && (G.n = -1), d(c, u)) : G.n = u : G.v = u);
          try {
            if (f = 2, i) {
              if (c || (o = "next"), t = i[o]) {
                if (!(t = t.call(i, u))) throw TypeError("iterator result is not an object");
                if (!t.done) return t;
                u = t.value, c < 2 && (c = 0);
              } else 1 === c && (t = i.return) && t.call(i), c < 2 && (u = TypeError("The iterator does not provide a '" + o + "' method"), c = 1);
              i = e;
            } else if ((t = (y = G.n < 0) ? u : r.call(n, G)) !== a) break;
          } catch (t) {
            i = e, c = 1, u = t;
          } finally {
            f = 1;
          }
        }
        return {
          value: t,
          done: y
        };
      };
    }(r, o, i), true), u;
  }
  var a = {};
  function Generator() {}
  function GeneratorFunction() {}
  function GeneratorFunctionPrototype() {}
  t = Object.getPrototypeOf;
  var c = [][n] ? t(t([][n]())) : (_regeneratorDefine(t = {}, n, function () {
      return this;
    }), t),
    u = GeneratorFunctionPrototype.prototype = Generator.prototype = Object.create(c);
  function f(e) {
    return Object.setPrototypeOf ? Object.setPrototypeOf(e, GeneratorFunctionPrototype) : (e.__proto__ = GeneratorFunctionPrototype, _regeneratorDefine(e, o, "GeneratorFunction")), e.prototype = Object.create(u), e;
  }
  return GeneratorFunction.prototype = GeneratorFunctionPrototype, _regeneratorDefine(u, "constructor", GeneratorFunctionPrototype), _regeneratorDefine(GeneratorFunctionPrototype, "constructor", GeneratorFunction), GeneratorFunction.displayName = "GeneratorFunction", _regeneratorDefine(GeneratorFunctionPrototype, o, "GeneratorFunction"), _regeneratorDefine(u), _regeneratorDefine(u, o, "Generator"), _regeneratorDefine(u, n, function () {
    return this;
  }), _regeneratorDefine(u, "toString", function () {
    return "[object Generator]";
  }), (_regenerator = function () {
    return {
      w: i,
      m: f
    };
  })();
}
function _regeneratorDefine(e, r, n, t) {
  var i = Object.defineProperty;
  try {
    i({}, "", {});
  } catch (e) {
    i = 0;
  }
  _regeneratorDefine = function (e, r, n, t) {
    function o(r, n) {
      _regeneratorDefine(e, r, function (e) {
        return this._invoke(r, n, e);
      });
    }
    r ? i ? i(e, r, {
      value: n,
      enumerable: !t,
      configurable: !t,
      writable: !t
    }) : e[r] = n : (o("next", 0), o("throw", 1), o("return", 2));
  }, _regeneratorDefine(e, r, n, t);
}
function _typeof(o) {
  "@babel/helpers - typeof";

  return _typeof = "function" == typeof Symbol && "symbol" == typeof Symbol.iterator ? function (o) {
    return typeof o;
  } : function (o) {
    return o && "function" == typeof Symbol && o.constructor === Symbol && o !== Symbol.prototype ? "symbol" : typeof o;
  }, _typeof(o);
}

function pushCodePoint(bytes, codePoint) {
  if (codePoint <= 0x7f) {
    bytes.push(codePoint);
  } else if (codePoint <= 0x7ff) {
    bytes.push(0xc0 | codePoint >>> 6, 0x80 | codePoint & 0x3f);
  } else if (codePoint <= 0xffff) {
    bytes.push(0xe0 | codePoint >>> 12, 0x80 | codePoint >>> 6 & 0x3f, 0x80 | codePoint & 0x3f);
  } else {
    bytes.push(0xf0 | codePoint >>> 18, 0x80 | codePoint >>> 12 & 0x3f, 0x80 | codePoint >>> 6 & 0x3f, 0x80 | codePoint & 0x3f);
  }
}
function utf8Encode(value) {
  var bytes = [];
  var index;
  for (index = 0; index < value.length; index += 1) {
    var first = value.charCodeAt(index);
    var codePoint = first;
    if (first >= 0xd800 && first <= 0xdbff) {
      if (index + 1 < value.length) {
        var second = value.charCodeAt(index + 1);
        if (second >= 0xdc00 && second <= 0xdfff) {
          codePoint = 0x10000 + (first - 0xd800 << 10) + (second - 0xdc00);
          index += 1;
        } else {
          codePoint = 0xfffd;
        }
      } else {
        codePoint = 0xfffd;
      }
    } else if (first >= 0xdc00 && first <= 0xdfff) {
      codePoint = 0xfffd;
    }
    pushCodePoint(bytes, codePoint);
  }
  return bytes;
}
function continuation(bytes, index) {
  var value = bytes[index];
  if ((value & 0xc0) !== 0x80) {
    throw new Error('Invalid UTF-8 data');
  }
  return value & 0x3f;
}
function utf8Decode(bytes) {
  var result = '';
  var index = 0;
  while (index < bytes.length) {
    var first = bytes[index];
    var codePoint;
    var size;
    if (first <= 0x7f) {
      codePoint = first;
      size = 1;
    } else if (first >= 0xc2 && first <= 0xdf) {
      if (index + 1 >= bytes.length) {
        throw new Error('Invalid UTF-8 data');
      }
      codePoint = (first & 0x1f) << 6 | continuation(bytes, index + 1);
      size = 2;
    } else if (first >= 0xe0 && first <= 0xef) {
      if (index + 2 >= bytes.length) {
        throw new Error('Invalid UTF-8 data');
      }
      var second = bytes[index + 1];
      if (first === 0xe0 && second < 0xa0 || first === 0xed && second >= 0xa0) {
        throw new Error('Invalid UTF-8 data');
      }
      codePoint = (first & 0x0f) << 12 | continuation(bytes, index + 1) << 6 | continuation(bytes, index + 2);
      size = 3;
    } else if (first >= 0xf0 && first <= 0xf4) {
      if (index + 3 >= bytes.length) {
        throw new Error('Invalid UTF-8 data');
      }
      var next = bytes[index + 1];
      if (first === 0xf0 && next < 0x90 || first === 0xf4 && next >= 0x90) {
        throw new Error('Invalid UTF-8 data');
      }
      codePoint = (first & 0x07) << 18 | continuation(bytes, index + 1) << 12 | continuation(bytes, index + 2) << 6 | continuation(bytes, index + 3);
      size = 4;
    } else {
      throw new Error('Invalid UTF-8 data');
    }
    if (codePoint <= 0xffff) {
      result += String.fromCharCode(codePoint);
    } else {
      codePoint -= 0x10000;
      result += String.fromCharCode(0xd800 | codePoint >>> 10, 0xdc00 | codePoint & 0x3ff);
    }
    index += size;
  }
  return result;
}

var VERSION = 1;
var SUITE = 'P256_HKDF_SHA256_AES256_GCM';
var OUTER_METHOD = 'POST';
var MESSAGE_ENDPOINT = '/sc/v1/message';
var PROTECTED_MEDIA_TYPE = 'application/sc-protected+json';
var MAX_SEQUENCE = 9007199254740991;
var IDENTIFIER = /^[\x21-\x7e]{1,128}$/;
var BASE64URL = /^[A-Za-z0-9_-]+$/;
function SecureCommunicationError(code, message, details) {
  this.name = 'SecureCommunicationError';
  this.code = code;
  this.message = message || code;
  this.status = details && details.status;
  this.traceId = details && details.traceId;
  this.cause = details && details.cause;
  if (Error.captureStackTrace) {
    Error.captureStackTrace(this, SecureCommunicationError);
  }
}
SecureCommunicationError.prototype = Object.create(Error.prototype);
SecureCommunicationError.prototype.constructor = SecureCommunicationError;
function fail(code, message, cause) {
  throw new SecureCommunicationError(code, message, {
    cause: cause
  });
}
function getSubtle(explicit) {
  if (explicit) {
    return explicit;
  }
  if (typeof globalThis !== 'undefined' && globalThis.crypto && globalThis.crypto.subtle) {
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
function normalizeV1ContentType(value) {
  var normalized = value ? String(value).split(';', 1)[0].trim().toLowerCase() : 'application/octet-stream';
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
      if (index + 2 >= value.length || !/^[0-9a-fA-F]{2}$/.test(value.slice(index + 1, index + 3))) {
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
function normalizeV1Path(value) {
  if (typeof value !== 'string' || value.charAt(0) !== '/' || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('#') >= 0 || value.indexOf('://') >= 0 || value.indexOf(' ') >= 0) {
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
    if (leftParts[0] < rightParts[0]) {
      return -1;
    }
    if (leftParts[0] > rightParts[0]) {
      return 1;
    }
    if (leftParts[1] < rightParts[1]) {
      return -1;
    }
    if (leftParts[1] > rightParts[1]) {
      return 1;
    }
    return 0;
  });
  return pairs.length ? path + '?' + pairs.join('&') : path;
}
function normalizeV1Method(value) {
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
  var lines = ['SC1', direction, envelope.suite, envelope.kid, envelope.sid, String(envelope.ts), String(envelope.seq), envelope.rid, envelope.m, envelope.p, envelope.cty];
  if (direction === 'response') {
    lines.push(String(envelope.st));
  }
  return new Uint8Array(utf8Encode(lines.join('\n')));
}
function validateEnvelope(envelope, session, now, allowedClockSkewMs) {
  var expectedKeys = ['ct', 'cty', 'kid', 'm', 'nonce', 'p', 'rid', 'seq', 'sid', 'st', 'suite', 'ts', 'v'];
  var keys = Object.keys(envelope).sort();
  if (keys.length !== expectedKeys.length || keys.join('|') !== expectedKeys.join('|')) {
    fail('SC_INVALID_ENVELOPE', 'Envelope fields are invalid');
  }
  if (envelope.v !== VERSION) {
    fail('SC_UNSUPPORTED_VERSION', 'Envelope version is unsupported');
  }
  if (envelope.suite !== session.suite || envelope.kid !== session.kid || envelope.sid !== session.sid) {
    fail('SC_UNKNOWN_SESSION', 'Envelope session does not match');
  }
  if (!Number.isSafeInteger(envelope.seq) || envelope.seq < 1) {
    fail('SC_INVALID_ENVELOPE', 'Envelope sequence is invalid');
  }
  requireIdentifier(envelope.rid, 'envelope.rid');
  if (!Number.isInteger(envelope.st) || envelope.st < 100 || envelope.st > 599) {
    fail('SC_INVALID_ENVELOPE', 'Envelope status is invalid');
  }
  if (!Number.isSafeInteger(envelope.ts) || Math.abs(now - envelope.ts) > allowedClockSkewMs) {
    fail('SC_REQUEST_EXPIRED', 'Envelope timestamp is outside the accepted window');
  }
  if (envelope.m !== OUTER_METHOD || envelope.p !== MESSAGE_ENDPOINT || envelope.cty !== PROTECTED_MEDIA_TYPE) {
    fail('SC_ROUTE_MISMATCH', 'Envelope route binding is not canonical');
  }
}
function importAesGcmSession(_x, _x2) {
  return _importAesGcmSession.apply(this, arguments);
}

/**
 * Derives direction-separated session material from an already authenticated
 * P-256 ECDH handshake transcript.
 */
function _importAesGcmSession() {
  _importAesGcmSession = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee3(material, options) {
    var subtle, requestKey, responseKey;
    return _regenerator().w(function (_context3) {
      while (1) switch (_context3.n) {
        case 0:
          options = options || {};
          requireIdentifier(material.kid, 'kid');
          requireIdentifier(material.sid, 'sid');
          subtle = getSubtle(options.subtle);
          _context3.n = 1;
          return subtle.importKey('raw', asBytes(material.requestKey, 'requestKey'), {
            name: 'AES-GCM',
            length: 256
          }, false, ['encrypt']);
        case 1:
          requestKey = _context3.v;
          _context3.n = 2;
          return subtle.importKey('raw', asBytes(material.responseKey, 'responseKey'), {
            name: 'AES-GCM',
            length: 256
          }, false, ['decrypt']);
        case 2:
          responseKey = _context3.v;
          return _context3.a(2, {
            suite: SUITE,
            kid: material.kid,
            sid: material.sid,
            requestKey: requestKey,
            responseKey: responseKey,
            requestNoncePrefix: requirePrefix(material.requestNoncePrefix, 'requestNoncePrefix'),
            responseNoncePrefix: requirePrefix(material.responseNoncePrefix, 'responseNoncePrefix'),
            expiresAt: material.expiresAt == null ? null : Number(material.expiresAt)
          });
      }
    }, _callee3);
  }));
  return _importAesGcmSession.apply(this, arguments);
}
function deriveAesGcmSession(_x3, _x4) {
  return _deriveAesGcmSession.apply(this, arguments);
}
function _deriveAesGcmSession() {
  _deriveAesGcmSession = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee4(material, options) {
    var subtle, transcriptHash, sharedSecret, hkdfKey, derived, bytes, session, _t3;
    return _regenerator().w(function (_context4) {
      while (1) switch (_context4.p = _context4.n) {
        case 0:
          options = options || {};
          subtle = getSubtle(options.subtle);
          transcriptHash = asBytes(material.transcriptHash, 'transcriptHash');
          if (!(transcriptHash.length !== 32)) {
            _context4.n = 1;
            break;
          }
          throw new TypeError('transcriptHash must contain exactly 32 bytes');
        case 1:
          _context4.p = 1;
          _context4.n = 2;
          return subtle.deriveBits({
            name: 'ECDH',
            "public": material.peerEphemeralPublicKey
          }, material.localEphemeralPrivateKey, 256);
        case 2:
          sharedSecret = _context4.v;
          _context4.n = 3;
          return subtle.importKey('raw', sharedSecret, 'HKDF', false, ['deriveBits']);
        case 3:
          hkdfKey = _context4.v;
          _context4.n = 4;
          return subtle.deriveBits({
            name: 'HKDF',
            hash: 'SHA-256',
            salt: transcriptHash,
            info: new Uint8Array(utf8Encode('SC1/session/' + SUITE + '/' + material.sid))
          }, hkdfKey, 72 * 8);
        case 4:
          derived = _context4.v;
          _context4.n = 6;
          break;
        case 5:
          _context4.p = 5;
          _t3 = _context4.v;
          fail('SC_HANDSHAKE_FAILED', 'Session key derivation failed', _t3);
        case 6:
          bytes = new Uint8Array(derived);
          _context4.n = 7;
          return importAesGcmSession({
            kid: material.kid,
            sid: material.sid,
            requestKey: bytes.slice(0, 32),
            responseKey: bytes.slice(32, 64),
            requestNoncePrefix: bytes.slice(64, 68),
            responseNoncePrefix: bytes.slice(68, 72),
            expiresAt: material.expiresAt
          }, {
            subtle: subtle
          });
        case 7:
          session = _context4.v;
          bytes.fill(0);
          new Uint8Array(sharedSecret).fill(0);
          return _context4.a(2, session);
      }
    }, _callee4, null, [[1, 5]]);
  }));
  return _deriveAesGcmSession.apply(this, arguments);
}
function verifyP256Transcript(_x5, _x6, _x7, _x8) {
  return _verifyP256Transcript.apply(this, arguments);
}
function _verifyP256Transcript() {
  _verifyP256Transcript = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee5(transcriptHash, p1363Signature, serverSigningPublicKey, options) {
    var hash, signature, _t4;
    return _regenerator().w(function (_context5) {
      while (1) switch (_context5.p = _context5.n) {
        case 0:
          options = options || {};
          hash = asBytes(transcriptHash, 'transcriptHash');
          signature = asBytes(p1363Signature, 'p1363Signature');
          if (!(hash.length !== 32 || signature.length !== 64)) {
            _context5.n = 1;
            break;
          }
          return _context5.a(2, false);
        case 1:
          _context5.p = 1;
          _context5.n = 2;
          return getSubtle(options.subtle).verify({
            name: 'ECDSA',
            hash: 'SHA-256'
          }, serverSigningPublicKey, signature, hash);
        case 2:
          return _context5.a(2, _context5.v);
        case 3:
          _context5.p = 3;
          _t4 = _context5.v;
          fail('SC_HANDSHAKE_FAILED', 'Transcript verification failed', _t4);
        case 4:
          return _context5.a(2);
      }
    }, _callee5, null, [[1, 3]]);
  }));
  return _verifyP256Transcript.apply(this, arguments);
}
function createV1Codec(session, options) {
  options = options || {};
  if (!session || session.suite !== SUITE) {
    throw new TypeError('A supported v1 session is required');
  }
  requireIdentifier(session.kid, 'session.kid');
  requireIdentifier(session.sid, 'session.sid');
  var requestPrefix = requirePrefix(session.requestNoncePrefix, 'session.requestNoncePrefix');
  var responsePrefix = requirePrefix(session.responseNoncePrefix, 'session.responseNoncePrefix');
  var subtle = getSubtle(options.subtle);
  var clock = options.now || Date.now;
  var allowedClockSkewMs = options.allowedClockSkewMs == null ? 300000 : Number(options.allowedClockSkewMs);
  var nextSequence = options.initialSequence == null ? 1 : Number(options.initialSequence);
  function encodeRequest(_x9) {
    return _encodeRequest.apply(this, arguments);
  }
  function _encodeRequest() {
    _encodeRequest = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee(request) {
      var sequence, timestamp, envelope, requestNonce, sealed, _t;
      return _regenerator().w(function (_context) {
        while (1) switch (_context.p = _context.n) {
          case 0:
            request = request || {};
            if (!Number.isSafeInteger(nextSequence) || nextSequence < 1 || nextSequence > MAX_SEQUENCE) {
              fail('SC_SEQUENCE_EXHAUSTED', 'Session sequence is exhausted');
            }
            sequence = nextSequence;
            nextSequence += 1;
            timestamp = Number(clock());
            if (!Number.isSafeInteger(timestamp)) {
              fail('SC_CLOCK_INVALID', 'Clock must return epoch milliseconds');
            }
            if (session.expiresAt != null && timestamp >= session.expiresAt) {
              fail('SC_UNKNOWN_SESSION', 'Session has expired');
            }
            requireIdentifier(request.requestId, 'requestId');
            envelope = {
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
            requestNonce = nonce(requestPrefix, sequence);
            envelope.nonce = base64UrlEncode(requestNonce);
            _context.p = 1;
            _context.n = 2;
            return subtle.encrypt({
              name: 'AES-GCM',
              iv: requestNonce,
              additionalData: aad('request', envelope),
              tagLength: 128
            }, session.requestKey, asBytes(request.body == null ? new Uint8Array(0) : request.body, 'body'));
          case 2:
            sealed = _context.v;
            _context.n = 4;
            break;
          case 3:
            _context.p = 3;
            _t = _context.v;
            fail('SC_CRYPTO_FAILED', 'Request encryption failed', _t);
          case 4:
            envelope.ct = base64UrlEncode(new Uint8Array(sealed));
            return _context.a(2, JSON.stringify(envelope));
        }
      }, _callee, null, [[1, 3]]);
    }));
    return _encodeRequest.apply(this, arguments);
  }
  function decodeResponse(_x0, _x1) {
    return _decodeResponse.apply(this, arguments);
  }
  function _decodeResponse() {
    _decodeResponse = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee2(encoded, expected) {
      var envelope, responseNonce, opened, _t2;
      return _regenerator().w(function (_context2) {
        while (1) switch (_context2.p = _context2.n) {
          case 0:
            try {
              envelope = typeof encoded === 'string' ? JSON.parse(encoded) : encoded;
            } catch (error) {
              fail('SC_INVALID_ENVELOPE', 'Response is not valid JSON', error);
            }
            if (!envelope || _typeof(envelope) !== 'object' || Array.isArray(envelope)) {
              fail('SC_INVALID_ENVELOPE', 'Response envelope is invalid');
            }
            validateEnvelope(envelope, session, Number(clock()), allowedClockSkewMs);
            expected = expected || {};
            if (expected.sequence != null && envelope.seq !== expected.sequence || expected.requestId && envelope.rid !== expected.requestId) {
              fail('SC_ROUTE_MISMATCH', 'Response does not match the request');
            }
            responseNonce = base64UrlDecode(envelope.nonce);
            if (!equalBytes(responseNonce, nonce(responsePrefix, envelope.seq))) {
              fail('SC_INVALID_ENVELOPE', 'Response nonce is invalid');
            }
            _context2.p = 1;
            _context2.n = 2;
            return subtle.decrypt({
              name: 'AES-GCM',
              iv: responseNonce,
              additionalData: aad('response', envelope),
              tagLength: 128
            }, session.responseKey, base64UrlDecode(envelope.ct));
          case 2:
            opened = _context2.v;
            _context2.n = 4;
            break;
          case 3:
            _context2.p = 3;
            _t2 = _context2.v;
            fail('SC_AUTHENTICATION_FAILED', 'Response authentication failed', _t2);
          case 4:
            return _context2.a(2, {
              body: new Uint8Array(opened),
              contentType: envelope.cty,
              status: envelope.st,
              envelope: envelope,
              text: function text() {
                return utf8Decode(Array.prototype.slice.call(new Uint8Array(opened)));
              }
            });
        }
      }, _callee2, null, [[1, 3]]);
    }));
    return _decodeResponse.apply(this, arguments);
  }
  return {
    encodeRequest: encodeRequest,
    decodeResponse: decodeResponse
  };
}
var V1_INTERNATIONAL_SUITE = SUITE;
var V1_ENVELOPE_MEDIA_TYPE = 'application/sc-envelope+json';

function bodyBytes(value) {
  if (value == null) {
    return new Uint8Array(0);
  }
  if (value instanceof Uint8Array) {
    return value;
  }
  if (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer) {
    return new Uint8Array(value);
  }
  if (typeof value === 'string') {
    return new Uint8Array(utf8Encode(value));
  }
  throw new TypeError('body must be a string, Uint8Array, or ArrayBuffer');
}
function base64Url(bytes) {
  var binary = '';
  var index;
  for (index = 0; index < bytes.length; index += 1) {
    binary += String.fromCharCode(bytes[index]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function decodeBase64Url(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]*$/.test(value)) {
    throw new SecureCommunicationError('SC_INVALID_ENVELOPE');
  }
  var padded = value.replace(/-/g, '+').replace(/_/g, '/');
  while (padded.length % 4) {
    padded += '=';
  }
  var binary = atob(padded);
  var result = new Uint8Array(binary.length);
  var index;
  for (index = 0; index < binary.length; index += 1) {
    result[index] = binary.charCodeAt(index);
  }
  return result;
}
function protectedResponse(decoded) {
  var value;
  try {
    value = JSON.parse(utf8Decode(Array.prototype.slice.call(decoded.body)));
  } catch (error) {
    throw new SecureCommunicationError('SC_INVALID_ENVELOPE', null, {
      cause: error
    });
  }
  if (!value || Array.isArray(value) || Object.keys(value).sort().join('|') !== 'body|contentType' || typeof value.contentType !== 'string') {
    throw new SecureCommunicationError('SC_INVALID_ENVELOPE');
  }
  return {
    contentType: normalizeV1ContentType(value.contentType),
    body: decodeBase64Url(value.body)
  };
}
function protectedPayload(init, configuredNames) {
  var values = {};
  var names = configuredNames || ['code'];
  names.forEach(function collect(name) {
    var normalized = String(name).toLowerCase();
    if (!/^[a-z0-9-]{1,64}$/.test(normalized) || normalized === 'content-type') {
      throw new TypeError('protected header name is invalid');
    }
    var value = getHeader(init.headers, normalized);
    if (value != null) {
      values[normalized] = String(value);
    }
  });
  Object.keys(init.protectedHeaders || {}).forEach(function explicit(name) {
    var normalized = name.toLowerCase();
    if (!/^[a-z0-9-]{1,64}$/.test(normalized) || /[\r\n]/.test(String(init.protectedHeaders[name]))) {
      throw new TypeError('protected header is invalid');
    }
    values[normalized] = String(init.protectedHeaders[name]);
  });
  return JSON.stringify({
    method: normalizeV1Method(init.method),
    path: normalizeV1Path(init.logicalPath),
    contentType: normalizeV1ContentType(init.logicalContentType),
    headers: values,
    body: base64Url(bodyBytes(init.body))
  });
}
function newRequestId() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  if (!globalThis.crypto || typeof globalThis.crypto.getRandomValues !== 'function') {
    throw new TypeError('WebCrypto random generator is required');
  }
  return base64Url(globalThis.crypto.getRandomValues(new Uint8Array(16)));
}
function requireHttps(baseUrl, allowInsecureForTesting) {
  var parsed = new URL(baseUrl);
  if (parsed.protocol !== 'https:' && !allowInsecureForTesting) {
    throw new TypeError('baseUrl must use HTTPS');
  }
  return parsed.toString().replace(/\/+$/, '');
}
function getHeader(headers, name) {
  if (!headers) {
    return null;
  }
  if (typeof headers.get === 'function') {
    return headers.get(name);
  }
  var lower = name.toLowerCase();
  var keys = Object.keys(headers);
  var index;
  for (index = 0; index < keys.length; index += 1) {
    if (keys[index].toLowerCase() === lower) {
      return headers[keys[index]];
    }
  }
  return null;
}
function createSecureFetch(configuration) {
  configuration = configuration || {};
  if (!configuration.codec) {
    throw new TypeError('codec is required');
  }
  var fetchImplementation = configuration.fetch || (typeof fetch === 'function' ? fetch : null);
  if (!fetchImplementation) {
    throw new TypeError('fetch implementation is required');
  }
  var baseUrl = requireHttps(configuration.baseUrl, configuration.allowInsecureForTesting === true);
  var endpoint = baseUrl + (configuration.endpoint || '/sc/v1/message');
  return /*#__PURE__*/function () {
    var _secureFetch = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee(path, init) {
      var method, contentType, requestId, payloadInit, encoded, requestEnvelope, response, responseText, responseMediaType, errorBody, decoded, protectedResult;
      return _regenerator().w(function (_context) {
        while (1) switch (_context.n) {
          case 0:
            init = init || {};
            method = String(init.method || 'GET').toUpperCase();
            contentType = getHeader(init.headers, 'content-type') || 'application/octet-stream';
            requestId = init.requestId || newRequestId();
            payloadInit = Object.assign({}, init, {
              method: method,
              logicalPath: path,
              logicalContentType: contentType
            });
            _context.n = 1;
            return configuration.codec.encodeRequest({
              requestId: requestId,
              body: protectedPayload(payloadInit, configuration.protectedHeaderNames)
            });
          case 1:
            encoded = _context.v;
            requestEnvelope = JSON.parse(encoded);
            _context.n = 2;
            return fetchImplementation(endpoint, {
              method: 'POST',
              headers: {
                'Content-Type': V1_ENVELOPE_MEDIA_TYPE,
                'Accept': V1_ENVELOPE_MEDIA_TYPE
              },
              body: encoded,
              signal: init.signal,
              credentials: init.credentials,
              cache: 'no-store',
              redirect: 'error'
            });
          case 2:
            response = _context.v;
            _context.n = 3;
            return response.text();
          case 3:
            responseText = _context.v;
            responseMediaType = String(getHeader(response.headers, 'content-type') || '').split(';', 1)[0].trim();
            if (!(responseMediaType.toLowerCase() !== V1_ENVELOPE_MEDIA_TYPE)) {
              _context.n = 4;
              break;
            }
            try {
              errorBody = JSON.parse(responseText);
            } catch (ignored) {
              errorBody = {};
            }
            throw new SecureCommunicationError(errorBody.code || 'SC_TRANSPORT_FAILED', errorBody.message || 'Secure transport failed', {
              status: response.status,
              traceId: errorBody.traceId
            });
          case 4:
            _context.n = 5;
            return configuration.codec.decodeResponse(responseText, {
              sequence: requestEnvelope.seq,
              requestId: requestId
            });
          case 5:
            decoded = _context.v;
            protectedResult = protectedResponse(decoded);
            return _context.a(2, {
              status: decoded.status,
              ok: decoded.status >= 200 && decoded.status < 300,
              contentType: protectedResult.contentType,
              body: protectedResult.body,
              text: function text() {
                return utf8Decode(Array.prototype.slice.call(protectedResult.body));
              },
              json: function json() {
                return JSON.parse(decoded.text());
              }
            });
        }
      }, _callee);
    }));
    function secureFetch(_x, _x2) {
      return _secureFetch.apply(this, arguments);
    }
    return secureFetch;
  }();
}

function subtle() {
  if (!globalThis.crypto || !globalThis.crypto.subtle) {
    throw new SecureCommunicationError('SC_CRYPTO_UNAVAILABLE', 'WebCrypto is required');
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
function responseJson(_x) {
  return _responseJson.apply(this, arguments);
}
function _responseJson() {
  _responseJson = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee7(response) {
    var body, _t1;
    return _regenerator().w(function (_context7) {
      while (1) switch (_context7.p = _context7.n) {
        case 0:
          _context7.p = 0;
          _context7.n = 1;
          return response.json();
        case 1:
          body = _context7.v;
          _context7.n = 3;
          break;
        case 2:
          _context7.p = 2;
          _t1 = _context7.v;
          throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Handshake response is invalid', {
            cause: _t1
          });
        case 3:
          if (response.ok) {
            _context7.n = 4;
            break;
          }
          throw new SecureCommunicationError(body.code || 'SC_HANDSHAKE_FAILED', body.message || 'Handshake failed', {
            status: response.status,
            traceId: body.traceId
          });
        case 4:
          return _context7.a(2, body);
      }
    }, _callee7, null, [[0, 2]]);
  }));
  return _responseJson.apply(this, arguments);
}
function MemoryIdentityStore() {
  var value = null;
  this.load = /*#__PURE__*/function () {
    var _load = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee() {
      return _regenerator().w(function (_context) {
        while (1) switch (_context.n) {
          case 0:
            return _context.a(2, value);
        }
      }, _callee);
    }));
    function load() {
      return _load.apply(this, arguments);
    }
    return load;
  }();
  this.save = /*#__PURE__*/function () {
    var _save = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee2(identity) {
      return _regenerator().w(function (_context2) {
        while (1) switch (_context2.n) {
          case 0:
            value = identity;
          case 1:
            return _context2.a(2);
        }
      }, _callee2);
    }));
    function save(_x2) {
      return _save.apply(this, arguments);
    }
    return save;
  }();
}
function IndexedDbIdentityStore(configuration) {
  configuration = configuration || {};
  var databaseName = configuration.databaseName || 'coolxer-secure-communication';
  var key = configuration.key || 'installation-v1';
  function database() {
    if (typeof indexedDB === 'undefined') {
      throw new SecureCommunicationError('SC_IDENTITY_STORE_UNAVAILABLE', 'IndexedDB is required');
    }
    return new Promise(function open(resolve, reject) {
      var request = indexedDB.open(databaseName, 1);
      request.onupgradeneeded = function upgrade() {
        if (!request.result.objectStoreNames.contains('identity')) {
          request.result.createObjectStore('identity');
        }
      };
      request.onsuccess = function success() {
        resolve(request.result);
      };
      request.onerror = function failed() {
        reject(request.error);
      };
    });
  }
  this.load = /*#__PURE__*/function () {
    var _load2 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee3() {
      var db;
      return _regenerator().w(function (_context3) {
        while (1) switch (_context3.n) {
          case 0:
            _context3.n = 1;
            return database();
          case 1:
            db = _context3.v;
            return _context3.a(2, new Promise(function read(resolve, reject) {
              var transaction = db.transaction('identity', 'readonly');
              var request = transaction.objectStore('identity').get(key);
              request.onsuccess = function success() {
                resolve(request.result || null);
              };
              request.onerror = function failed() {
                reject(request.error);
              };
              transaction.oncomplete = function complete() {
                db.close();
              };
            }));
        }
      }, _callee3);
    }));
    function load() {
      return _load2.apply(this, arguments);
    }
    return load;
  }();
  this.save = /*#__PURE__*/function () {
    var _save2 = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee4(identity) {
      var db;
      return _regenerator().w(function (_context4) {
        while (1) switch (_context4.n) {
          case 0:
            _context4.n = 1;
            return database();
          case 1:
            db = _context4.v;
            return _context4.a(2, new Promise(function write(resolve, reject) {
              var transaction = db.transaction('identity', 'readwrite');
              transaction.objectStore('identity').put(identity, key);
              transaction.oncomplete = function complete() {
                db.close();
                resolve();
              };
              transaction.onerror = function failed() {
                db.close();
                reject(transaction.error);
              };
            }));
        }
      }, _callee4);
    }));
    function save(_x3) {
      return _save2.apply(this, arguments);
    }
    return save;
  }();
}
function installationIdentity(_x4) {
  return _installationIdentity.apply(this, arguments);
}
function _installationIdentity() {
  _installationIdentity = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee8(store) {
    var existing, keys, created;
    return _regenerator().w(function (_context8) {
      while (1) switch (_context8.n) {
        case 0:
          _context8.n = 1;
          return store.load();
        case 1:
          existing = _context8.v;
          if (!(existing && existing.deviceId && existing.privateKey && existing.publicKey)) {
            _context8.n = 2;
            break;
          }
          return _context8.a(2, existing);
        case 2:
          _context8.n = 3;
          return subtle().generateKey({
            name: 'ECDSA',
            namedCurve: 'P-256'
          }, false, ['sign', 'verify']);
        case 3:
          keys = _context8.v;
          created = {
            deviceId: globalThis.crypto.randomUUID(),
            privateKey: keys.privateKey,
            publicKey: keys.publicKey
          };
          _context8.n = 4;
          return store.save(created);
        case 4:
          return _context8.a(2, created);
      }
    }, _callee8);
  }));
  return _installationIdentity.apply(this, arguments);
}
function transcript(response, request, clientEphemeral, installation, serverIdentity, serverEphemeral) {
  return ['SC1-HANDSHAKE', '1', V1_INTERNATIONAL_SUITE, request.appId, request.deviceId, request.deviceType, b64(clientEphemeral), b64(installation), b64(serverIdentity), b64(serverEphemeral), response.kid, response.sid, String(response.createdAt), String(response.expiresAt)].join('\n');
}
function SecureClient(configuration) {
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
      throw new SecureCommunicationError('SC_ENROLLMENT_NOT_SUPPORTED', 'H5 enrollment uses Origin policy');
    }
    if (!token || typeof token !== 'string') throw new TypeError('token is required');
    enrollmentToken = token;
  };
  this.initialize = /*#__PURE__*/function () {
    var _initialize = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee5() {
      var identity, ephemeral, clientEphemeral, installation, startRequest, start, serverIdentity, pinned, serverEphemeral, hash, serverSigningKey, peerEphemeral, proof, _t, _t2, _t3, _t4, _t5, _t6, _t7, _t8, _t9, _t0;
      return _regenerator().w(function (_context5) {
        while (1) switch (_context5.n) {
          case 0:
            if (!(secureFetch && session && Date.now() < session.expiresAt)) {
              _context5.n = 1;
              break;
            }
            return _context5.a(2, this);
          case 1:
            _context5.n = 2;
            return installationIdentity(store);
          case 2:
            identity = _context5.v;
            _context5.n = 3;
            return subtle().generateKey({
              name: 'ECDH',
              namedCurve: 'P-256'
            }, false, ['deriveBits']);
          case 3:
            ephemeral = _context5.v;
            _t = Uint8Array;
            _context5.n = 4;
            return subtle().exportKey('spki', ephemeral.publicKey);
          case 4:
            _t2 = _context5.v;
            clientEphemeral = new _t(_t2);
            _t3 = Uint8Array;
            _context5.n = 5;
            return subtle().exportKey('spki', identity.publicKey);
          case 5:
            _t4 = _context5.v;
            installation = new _t3(_t4);
            startRequest = {
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
            _t5 = responseJson;
            _context5.n = 6;
            return fetchImplementation(baseUrl + '/sc/v1/handshake', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
              },
              credentials: configuration.credentials,
              cache: 'no-store',
              redirect: 'error',
              body: JSON.stringify(startRequest)
            });
          case 6:
            _context5.n = 7;
            return _t5(_context5.v);
          case 7:
            start = _context5.v;
            enrollmentToken = null;
            if (!(start.v !== 1 || start.suite !== V1_INTERNATIONAL_SUITE || !configuration.serverTrustAnchors || !configuration.serverTrustAnchors[start.kid])) {
              _context5.n = 8;
              break;
            }
            throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Untrusted server identity');
          case 8:
            serverIdentity = unb64(start.serverIdentityPublicKey);
            pinned = unb64(configuration.serverTrustAnchors[start.kid]);
            if (equal(serverIdentity, pinned)) {
              _context5.n = 9;
              break;
            }
            throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Server identity pin mismatch');
          case 9:
            serverEphemeral = unb64(start.serverEphemeralPublicKey);
            _t6 = Uint8Array;
            _context5.n = 10;
            return subtle().digest('SHA-256', new Uint8Array(utf8Encode(transcript(start, startRequest, clientEphemeral, installation, serverIdentity, serverEphemeral))));
          case 10:
            _t7 = _context5.v;
            hash = new _t6(_t7);
            _context5.n = 11;
            return subtle().importKey('spki', serverIdentity, {
              name: 'ECDSA',
              namedCurve: 'P-256'
            }, false, ['verify']);
          case 11:
            serverSigningKey = _context5.v;
            _context5.n = 12;
            return verifyP256Transcript(hash, unb64(start.signature), serverSigningKey);
          case 12:
            if (_context5.v) {
              _context5.n = 13;
              break;
            }
            throw new SecureCommunicationError('SC_HANDSHAKE_FAILED', 'Invalid server proof');
          case 13:
            _context5.n = 14;
            return subtle().importKey('spki', serverEphemeral, {
              name: 'ECDH',
              namedCurve: 'P-256'
            }, false, []);
          case 14:
            peerEphemeral = _context5.v;
            _context5.n = 15;
            return deriveAesGcmSession({
              kid: start.kid,
              sid: start.sid,
              localEphemeralPrivateKey: ephemeral.privateKey,
              peerEphemeralPublicKey: peerEphemeral,
              transcriptHash: hash,
              expiresAt: start.expiresAt
            });
          case 15:
            session = _context5.v;
            _t8 = Uint8Array;
            _context5.n = 16;
            return subtle().sign({
              name: 'ECDSA',
              hash: 'SHA-256'
            }, identity.privateKey, hash);
          case 16:
            _t9 = _context5.v;
            proof = new _t8(_t9);
            _t0 = responseJson;
            _context5.n = 17;
            return fetchImplementation(baseUrl + '/sc/v1/handshake/finish', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
              },
              credentials: configuration.credentials,
              cache: 'no-store',
              redirect: 'error',
              body: JSON.stringify({
                kid: start.kid,
                sid: start.sid,
                proof: b64(proof)
              })
            });
          case 17:
            _context5.n = 18;
            return _t0(_context5.v);
          case 18:
            secureFetch = createSecureFetch({
              baseUrl: baseUrl,
              codec: createV1Codec(session),
              fetch: fetchImplementation,
              credentials: configuration.credentials,
              protectedHeaderNames: configuration.protectedHeaderNames || ['code'],
              allowInsecureForTesting: configuration.allowInsecureForTesting
            });
            return _context5.a(2, this);
        }
      }, _callee5, this);
    }));
    function initialize() {
      return _initialize.apply(this, arguments);
    }
    return initialize;
  }();
  this.request = /*#__PURE__*/function () {
    var _request = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee6(method, path, protectedHeaders, body, requestId, options) {
      return _regenerator().w(function (_context6) {
        while (1) switch (_context6.n) {
          case 0:
            _context6.n = 1;
            return this.initialize();
          case 1:
            options = options || {};
            return _context6.a(2, secureFetch(path, {
              method: method,
              headers: {
                'Content-Type': options.contentType || 'application/json'
              },
              protectedHeaders: Object.assign({}, protectedHeaders || {}),
              requestId: requestId,
              body: body,
              signal: options.signal,
              credentials: configuration.credentials
            }));
        }
      }, _callee6, this);
    }));
    function request(_x5, _x6, _x7, _x8, _x9, _x0) {
      return _request.apply(this, arguments);
    }
    return request;
  }();
  this.closeSession = function closeSession() {
    session = null;
    secureFetch = null;
  };
}
function createSecureClient(configuration) {
  return new SecureClient(configuration);
}

export { IndexedDbIdentityStore, MemoryIdentityStore, SecureClient, SecureCommunicationError, V1_ENVELOPE_MEDIA_TYPE, V1_INTERNATIONAL_SUITE, createSecureClient, createSecureFetch, createV1Codec, deriveAesGcmSession, importAesGcmSession, normalizeV1Path, verifyP256Transcript };
//# sourceMappingURL=index.esm.js.map
