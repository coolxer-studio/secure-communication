'use strict';

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

var SHIFTS = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
var CONSTANTS = [];
var constantIndex;
for (constantIndex = 0; constantIndex < 64; constantIndex += 1) {
  CONSTANTS[constantIndex] = Math.floor(Math.abs(Math.sin(constantIndex + 1)) * 0x100000000) >>> 0;
}
function rotateLeft$1(value, count) {
  return value << count | value >>> 32 - count;
}
function wordToHex(value) {
  var output = '';
  var index;
  for (index = 0; index < 4; index += 1) {
    output += ('0' + (value >>> index * 8 & 0xff).toString(16)).slice(-2);
  }
  return output;
}
function md5Hex(value) {
  var bytes = utf8Encode(value);
  var originalLength = bytes.length;
  var bitLengthLow = originalLength * 8 >>> 0;
  var bitLengthHigh = Math.floor(originalLength / 0x20000000) >>> 0;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) {
    bytes.push(0);
  }
  var lengthIndex;
  for (lengthIndex = 0; lengthIndex < 4; lengthIndex += 1) {
    bytes.push(bitLengthLow >>> lengthIndex * 8 & 0xff);
  }
  for (lengthIndex = 0; lengthIndex < 4; lengthIndex += 1) {
    bytes.push(bitLengthHigh >>> lengthIndex * 8 & 0xff);
  }
  var a0 = 0x67452301;
  var b0 = 0xefcdab89;
  var c0 = 0x98badcfe;
  var d0 = 0x10325476;
  var offset;
  for (offset = 0; offset < bytes.length; offset += 64) {
    var words = [];
    var wordIndex;
    for (wordIndex = 0; wordIndex < 16; wordIndex += 1) {
      var position = offset + wordIndex * 4;
      words[wordIndex] = bytes[position] | bytes[position + 1] << 8 | bytes[position + 2] << 16 | bytes[position + 3] << 24;
    }
    var a = a0;
    var b = b0;
    var c = c0;
    var d = d0;
    var round;
    for (round = 0; round < 64; round += 1) {
      var f;
      var g;
      if (round < 16) {
        f = b & c | ~b & d;
        g = round;
      } else if (round < 32) {
        f = d & b | ~d & c;
        g = (5 * round + 1) % 16;
      } else if (round < 48) {
        f = b ^ c ^ d;
        g = (3 * round + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = 7 * round % 16;
      }
      var nextD = d;
      d = c;
      c = b;
      b = b + rotateLeft$1(a + f + CONSTANTS[round] + words[g] | 0, SHIFTS[round]) | 0;
      a = nextD;
    }
    a0 = a0 + a | 0;
    b0 = b0 + b | 0;
    c0 = c0 + c | 0;
    d0 = d0 + d | 0;
  }
  return wordToHex(a0) + wordToHex(b0) + wordToHex(c0) + wordToHex(d0);
}

var SBOX = [0xd6, 0x90, 0xe9, 0xfe, 0xcc, 0xe1, 0x3d, 0xb7, 0x16, 0xb6, 0x14, 0xc2, 0x28, 0xfb, 0x2c, 0x05, 0x2b, 0x67, 0x9a, 0x76, 0x2a, 0xbe, 0x04, 0xc3, 0xaa, 0x44, 0x13, 0x26, 0x49, 0x86, 0x06, 0x99, 0x9c, 0x42, 0x50, 0xf4, 0x91, 0xef, 0x98, 0x7a, 0x33, 0x54, 0x0b, 0x43, 0xed, 0xcf, 0xac, 0x62, 0xe4, 0xb3, 0x1c, 0xa9, 0xc9, 0x08, 0xe8, 0x95, 0x80, 0xdf, 0x94, 0xfa, 0x75, 0x8f, 0x3f, 0xa6, 0x47, 0x07, 0xa7, 0xfc, 0xf3, 0x73, 0x17, 0xba, 0x83, 0x59, 0x3c, 0x19, 0xe6, 0x85, 0x4f, 0xa8, 0x68, 0x6b, 0x81, 0xb2, 0x71, 0x64, 0xda, 0x8b, 0xf8, 0xeb, 0x0f, 0x4b, 0x70, 0x56, 0x9d, 0x35, 0x1e, 0x24, 0x0e, 0x5e, 0x63, 0x58, 0xd1, 0xa2, 0x25, 0x22, 0x7c, 0x3b, 0x01, 0x21, 0x78, 0x87, 0xd4, 0x00, 0x46, 0x57, 0x9f, 0xd3, 0x27, 0x52, 0x4c, 0x36, 0x02, 0xe7, 0xa0, 0xc4, 0xc8, 0x9e, 0xea, 0xbf, 0x8a, 0xd2, 0x40, 0xc7, 0x38, 0xb5, 0xa3, 0xf7, 0xf2, 0xce, 0xf9, 0x61, 0x15, 0xa1, 0xe0, 0xae, 0x5d, 0xa4, 0x9b, 0x34, 0x1a, 0x55, 0xad, 0x93, 0x32, 0x30, 0xf5, 0x8c, 0xb1, 0xe3, 0x1d, 0xf6, 0xe2, 0x2e, 0x82, 0x66, 0xca, 0x60, 0xc0, 0x29, 0x23, 0xab, 0x0d, 0x53, 0x4e, 0x6f, 0xd5, 0xdb, 0x37, 0x45, 0xde, 0xfd, 0x8e, 0x2f, 0x03, 0xff, 0x6a, 0x72, 0x6d, 0x6c, 0x5b, 0x51, 0x8d, 0x1b, 0xaf, 0x92, 0xbb, 0xdd, 0xbc, 0x7f, 0x11, 0xd9, 0x5c, 0x41, 0x1f, 0x10, 0x5a, 0xd8, 0x0a, 0xc1, 0x31, 0x88, 0xa5, 0xcd, 0x7b, 0xbd, 0x2d, 0x74, 0xd0, 0x12, 0xb8, 0xe5, 0xb4, 0xb0, 0x89, 0x69, 0x97, 0x4a, 0x0c, 0x96, 0x77, 0x7e, 0x65, 0xb9, 0xf1, 0x09, 0xc5, 0x6e, 0xc6, 0x84, 0x18, 0xf0, 0x7d, 0xec, 0x3a, 0xdc, 0x4d, 0x20, 0x79, 0xee, 0x5f, 0x3e, 0xd7, 0xcb, 0x39, 0x48];
var FK = [0xa3b1bac6, 0x56aa3350, 0x677d9197, 0xb27022dc];
var CK = [0x00070e15, 0x1c232a31, 0x383f464d, 0x545b6269, 0x70777e85, 0x8c939aa1, 0xa8afb6bd, 0xc4cbd2d9, 0xe0e7eef5, 0xfc030a11, 0x181f262d, 0x343b4249, 0x50575e65, 0x6c737a81, 0x888f969d, 0xa4abb2b9, 0xc0c7ced5, 0xdce3eaf1, 0xf8ff060d, 0x141b2229, 0x30373e45, 0x4c535a61, 0x686f767d, 0x848b9299, 0xa0a7aeb5, 0xbcc3cad1, 0xd8dfe6ed, 0xf4fb0209, 0x10171e25, 0x2c333a41, 0x484f565d, 0x646b7279];
function rotateLeft(value, count) {
  // Keep the arithmetic right shift used by the Spring Boot H5 implementation.
  // This is not the standard SM4 rotate operation, but changing it would break
  // the deployed wire protocol and the existing agent-h5 ciphertext.
  return value << count | value >> 32 - count;
}
function readWord(bytes, offset) {
  return bytes[offset] << 24 | bytes[offset + 1] << 16 | bytes[offset + 2] << 8 | bytes[offset + 3];
}
function writeWord(value, output, offset) {
  output[offset] = value >>> 24 & 0xff;
  output[offset + 1] = value >>> 16 & 0xff;
  output[offset + 2] = value >>> 8 & 0xff;
  output[offset + 3] = value & 0xff;
}
function substitute(value) {
  return SBOX[value >>> 24 & 0xff] << 24 | SBOX[value >>> 16 & 0xff] << 16 | SBOX[value >>> 8 & 0xff] << 8 | SBOX[value & 0xff];
}
function roundTransform(value) {
  var substituted = substitute(value);
  return substituted ^ rotateLeft(substituted, 2) ^ rotateLeft(substituted, 10) ^ rotateLeft(substituted, 18) ^ rotateLeft(substituted, 24);
}
function keyTransform(value) {
  var substituted = substitute(value);
  return substituted ^ rotateLeft(substituted, 13) ^ rotateLeft(substituted, 23);
}
function createRoundKeys(keyBytes) {
  if (!keyBytes || keyBytes.length !== 16) {
    throw new Error('SM4 key must contain 16 bytes');
  }
  var values = [];
  var roundKeys = [];
  var index;
  for (index = 0; index < 4; index += 1) {
    values[index] = readWord(keyBytes, index * 4) ^ FK[index];
  }
  for (index = 0; index < 32; index += 1) {
    values[index + 4] = values[index] ^ keyTransform(values[index + 1] ^ values[index + 2] ^ values[index + 3] ^ CK[index]);
    roundKeys[index] = values[index + 4];
  }
  return roundKeys;
}
function cryptBlock(input, roundKeys) {
  var values = [readWord(input, 0), readWord(input, 4), readWord(input, 8), readWord(input, 12)];
  var round;
  for (round = 0; round < 32; round += 1) {
    values[round + 4] = values[round] ^ roundTransform(values[round + 1] ^ values[round + 2] ^ values[round + 3] ^ roundKeys[round]);
  }
  var output = [];
  writeWord(values[35], output, 0);
  writeWord(values[34], output, 4);
  writeWord(values[33], output, 8);
  writeWord(values[32], output, 12);
  return output;
}
function pad(bytes) {
  var paddingLength = 16 - bytes.length % 16;
  var output = bytes.slice();
  var index;
  for (index = 0; index < paddingLength; index += 1) {
    output.push(paddingLength);
  }
  return output;
}
function unpad(bytes) {
  if (bytes.length === 0 || bytes.length % 16 !== 0) {
    throw new Error('Invalid PKCS#7 padded data');
  }
  var paddingLength = bytes[bytes.length - 1];
  if (paddingLength < 1 || paddingLength > 16 || paddingLength > bytes.length) {
    throw new Error('Invalid PKCS#7 padding');
  }
  var index;
  for (index = bytes.length - paddingLength; index < bytes.length; index += 1) {
    if (bytes[index] !== paddingLength) {
      throw new Error('Invalid PKCS#7 padding');
    }
  }
  return bytes.slice(0, bytes.length - paddingLength);
}
function encryptCbc(plainBytes, keyBytes, ivBytes) {
  if (!ivBytes || ivBytes.length !== 16) {
    throw new Error('SM4 IV must contain 16 bytes');
  }
  var roundKeys = createRoundKeys(keyBytes);
  var padded = pad(plainBytes);
  var previous = ivBytes.slice();
  var output = [];
  var offset;
  for (offset = 0; offset < padded.length; offset += 16) {
    var block = [];
    var index;
    for (index = 0; index < 16; index += 1) {
      block[index] = padded[offset + index] ^ previous[index];
    }
    previous = cryptBlock(block, roundKeys);
    output = output.concat(previous);
  }
  return output;
}
function decryptCbc(cipherBytes, keyBytes, ivBytes) {
  if (!cipherBytes || cipherBytes.length === 0 || cipherBytes.length % 16 !== 0) {
    throw new Error('SM4 ciphertext must contain complete 16-byte blocks');
  }
  if (!ivBytes || ivBytes.length !== 16) {
    throw new Error('SM4 IV must contain 16 bytes');
  }
  var roundKeys = createRoundKeys(keyBytes).reverse();
  var previous = ivBytes.slice();
  var output = [];
  var offset;
  for (offset = 0; offset < cipherBytes.length; offset += 16) {
    var cipherBlock = cipherBytes.slice(offset, offset + 16);
    var decrypted = cryptBlock(cipherBlock, roundKeys);
    var index;
    for (index = 0; index < 16; index += 1) {
      output.push(decrypted[index] ^ previous[index]);
    }
    previous = cipherBlock;
  }
  return unpad(output);
}

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

var VERSION = 2;
var SUITE = 'SC2_P256_HKDF_SHA256_AES_256_GCM';
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
function normalizeContentType(value) {
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
function normalizeV2Path(value) {
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
function normalizeMethod(value) {
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
  var lines = ['SC2', direction, envelope.suite, envelope.kid, envelope.sid, String(envelope.ts), String(envelope.seq), envelope.m, envelope.p, envelope.cty];
  if (direction === 'response') {
    lines.push(String(envelope.st));
  }
  return new Uint8Array(utf8Encode(lines.join('\n')));
}
function validateEnvelope(envelope, session, now, allowedClockSkewMs) {
  var expectedKeys = ['ct', 'cty', 'kid', 'm', 'nonce', 'p', 'seq', 'sid', 'st', 'suite', 'ts', 'v'];
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
  if (!Number.isInteger(envelope.st) || envelope.st < 100 || envelope.st > 599) {
    fail('SC_INVALID_ENVELOPE', 'Envelope status is invalid');
  }
  if (!Number.isSafeInteger(envelope.ts) || Math.abs(now - envelope.ts) > allowedClockSkewMs) {
    fail('SC_REQUEST_EXPIRED', 'Envelope timestamp is outside the accepted window');
  }
  if (normalizeMethod(envelope.m) !== envelope.m || normalizeV2Path(envelope.p) !== envelope.p || normalizeContentType(envelope.cty) !== envelope.cty) {
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
            info: new Uint8Array(utf8Encode('SC2/session/' + SUITE + '/' + material.sid))
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
function createV2Codec(session, options) {
  options = options || {};
  if (!session || session.suite !== SUITE) {
    throw new TypeError('A supported v2 session is required');
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
            envelope = {
              v: VERSION,
              suite: session.suite,
              kid: session.kid,
              sid: session.sid,
              ts: timestamp,
              seq: sequence,
              m: normalizeMethod(request.method),
              p: normalizeV2Path(request.path),
              cty: normalizeContentType(request.contentType),
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
            if (expected.sequence != null && envelope.seq !== expected.sequence || expected.method && envelope.m !== normalizeMethod(expected.method) || expected.path && envelope.p !== normalizeV2Path(expected.path)) {
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
var V2_INTERNATIONAL_SUITE = SUITE;
var V2_ENVELOPE_MEDIA_TYPE = 'application/sc-envelope+json';

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
  var endpoint = baseUrl + (configuration.endpoint || '/sc/v2');
  return /*#__PURE__*/function () {
    var _secureFetch = _asyncToGenerator(/*#__PURE__*/_regenerator().m(function _callee(path, init) {
      var method, contentType, encoded, requestEnvelope, response, responseText, responseMediaType, errorBody, decoded;
      return _regenerator().w(function (_context) {
        while (1) switch (_context.n) {
          case 0:
            init = init || {};
            method = String(init.method || 'GET').toUpperCase();
            contentType = getHeader(init.headers, 'content-type') || 'application/octet-stream';
            _context.n = 1;
            return configuration.codec.encodeRequest({
              method: method,
              path: path,
              contentType: contentType,
              body: init.body == null ? new Uint8Array(0) : init.body
            });
          case 1:
            encoded = _context.v;
            requestEnvelope = JSON.parse(encoded);
            _context.n = 2;
            return fetchImplementation(endpoint, {
              method: 'POST',
              headers: {
                'Content-Type': V2_ENVELOPE_MEDIA_TYPE,
                'Accept': V2_ENVELOPE_MEDIA_TYPE
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
            if (!(responseMediaType.toLowerCase() !== V2_ENVELOPE_MEDIA_TYPE)) {
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
              method: method,
              path: path
            });
          case 5:
            decoded = _context.v;
            return _context.a(2, {
              status: decoded.status,
              ok: decoded.status >= 200 && decoded.status < 300,
              contentType: decoded.contentType,
              body: decoded.body,
              text: decoded.text,
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

/**
 * Explicit v1 H5 transport for hosts that still require XMLHttpRequest.
 * It never falls back from v2 and therefore requires a caller-supplied H5 codec.
 */
function createLegacyH5Xhr(configuration) {
  configuration = configuration || {};
  if (!configuration.codec) {
    throw new TypeError('legacy H5 codec is required');
  }
  var Xhr = configuration.XMLHttpRequest || (typeof XMLHttpRequest !== 'undefined' ? XMLHttpRequest : null);
  if (!Xhr) {
    throw new TypeError('XMLHttpRequest implementation is required');
  }
  var baseUrl = requireHttps(configuration.baseUrl, configuration.allowInsecureForTesting === true);
  return function legacyH5Request(path, plainText) {
    return new Promise(function perform(resolve, reject) {
      var xhr = new Xhr();
      xhr.open('POST', baseUrl + '/sc/h5' + path, true);
      xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
      xhr.onload = function loaded() {
        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new SecureCommunicationError('SC_LEGACY_TRANSPORT_FAILED', 'Legacy H5 transport failed', {
            status: xhr.status
          }));
          return;
        }
        try {
          resolve(configuration.codec.decodeResponse(xhr.responseText));
        } catch (error) {
          reject(error);
        }
      };
      xhr.onerror = function failed() {
        reject(new SecureCommunicationError('SC_NETWORK_FAILED', 'Legacy H5 network request failed'));
      };
      xhr.send(configuration.codec.encodeRequest(plainText));
    });
  };
}

var APP_ID_LENGTH = 32;
var KEY_LENGTH = 16;
var SALT = '_bsdk_';
var HEX_PATTERN = /^[0-9a-fA-F]+$/;
function requireString(value, name) {
  if (typeof value !== 'string') {
    throw new TypeError(name + ' must be a string');
  }
}
function validateAppId(appId) {
  requireString(appId, 'appId');
  if (appId.length !== APP_ID_LENGTH) {
    throw new TypeError('appId must contain exactly 32 characters');
  }
}
function toHex(bytes) {
  var result = '';
  var index;
  for (index = 0; index < bytes.length; index += 1) {
    result += ('0' + bytes[index].toString(16)).slice(-2);
  }
  return result.toUpperCase();
}
function fromHex(value) {
  requireString(value, 'cipherHex');
  if (value.length === 0 || value.length % 2 !== 0 || !HEX_PATTERN.test(value)) {
    throw new TypeError('cipherHex must be a non-empty, even-length hexadecimal string');
  }
  var bytes = [];
  var index;
  for (index = 0; index < value.length; index += 2) {
    bytes.push(parseInt(value.slice(index, index + 2), 16));
  }
  return bytes;
}
function deriveKey(appId) {
  return md5Hex(appId.toLowerCase() + SALT).slice(0, KEY_LENGTH).toLowerCase();
}
function createH5Codec(appId) {
  validateAppId(appId);
  var key = deriveKey(appId);
  var keyBytes = utf8Encode(key);
  var requestSuffix = appId.toUpperCase();
  function encrypt(plainText) {
    requireString(plainText, 'plainText');
    return toHex(encryptCbc(utf8Encode(plainText), keyBytes, keyBytes));
  }
  function decrypt(cipherHex) {
    var cipherBytes = fromHex(cipherHex);
    return utf8Decode(decryptCbc(cipherBytes, keyBytes, keyBytes));
  }
  return {
    encrypt: encrypt,
    decrypt: decrypt,
    encodeRequest: function encodeRequest(plainText) {
      return encrypt(plainText) + requestSuffix;
    },
    decodeResponse: function decodeResponse(cipherHex) {
      return decrypt(cipherHex);
    }
  };
}

exports.SecureCommunicationError = SecureCommunicationError;
exports.V2_ENVELOPE_MEDIA_TYPE = V2_ENVELOPE_MEDIA_TYPE;
exports.V2_INTERNATIONAL_SUITE = V2_INTERNATIONAL_SUITE;
exports.createH5Codec = createH5Codec;
exports.createLegacyH5Xhr = createLegacyH5Xhr;
exports.createSecureFetch = createSecureFetch;
exports.createV2Codec = createV2Codec;
exports.deriveAesGcmSession = deriveAesGcmSession;
exports.importAesGcmSession = importAesGcmSession;
exports.normalizeV2Path = normalizeV2Path;
exports.verifyP256Transcript = verifyP256Transcript;
//# sourceMappingURL=index.cjs.map
