import { md5Hex } from './md5.js';
import { decryptCbc, encryptCbc } from './sm4.js';
import { utf8Decode, utf8Encode } from './utf8.js';

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

export function createH5Codec(appId) {
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
