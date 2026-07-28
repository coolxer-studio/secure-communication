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

exports.createH5Codec = createH5Codec;
//# sourceMappingURL=index.cjs.map
