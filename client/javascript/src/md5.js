import { utf8Encode } from './utf8.js';

var SHIFTS = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
];

var CONSTANTS = [];
var constantIndex;
for (constantIndex = 0; constantIndex < 64; constantIndex += 1) {
  CONSTANTS[constantIndex] = Math.floor(Math.abs(Math.sin(constantIndex + 1)) * 0x100000000) >>> 0;
}

function rotateLeft(value, count) {
  return (value << count) | (value >>> (32 - count));
}

function wordToHex(value) {
  var output = '';
  var index;
  for (index = 0; index < 4; index += 1) {
    output += ('0' + ((value >>> (index * 8)) & 0xff).toString(16)).slice(-2);
  }
  return output;
}

export function md5Hex(value) {
  var bytes = utf8Encode(value);
  var originalLength = bytes.length;
  var bitLengthLow = (originalLength * 8) >>> 0;
  var bitLengthHigh = Math.floor(originalLength / 0x20000000) >>> 0;

  bytes.push(0x80);
  while (bytes.length % 64 !== 56) {
    bytes.push(0);
  }

  var lengthIndex;
  for (lengthIndex = 0; lengthIndex < 4; lengthIndex += 1) {
    bytes.push((bitLengthLow >>> (lengthIndex * 8)) & 0xff);
  }
  for (lengthIndex = 0; lengthIndex < 4; lengthIndex += 1) {
    bytes.push((bitLengthHigh >>> (lengthIndex * 8)) & 0xff);
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
      words[wordIndex] =
        bytes[position] |
        (bytes[position + 1] << 8) |
        (bytes[position + 2] << 16) |
        (bytes[position + 3] << 24);
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
        f = (b & c) | (~b & d);
        g = round;
      } else if (round < 32) {
        f = (d & b) | (~d & c);
        g = (5 * round + 1) % 16;
      } else if (round < 48) {
        f = b ^ c ^ d;
        g = (3 * round + 5) % 16;
      } else {
        f = c ^ (b | ~d);
        g = (7 * round) % 16;
      }

      var nextD = d;
      d = c;
      c = b;
      b = (b + rotateLeft((a + f + CONSTANTS[round] + words[g]) | 0, SHIFTS[round])) | 0;
      a = nextD;
    }

    a0 = (a0 + a) | 0;
    b0 = (b0 + b) | 0;
    c0 = (c0 + c) | 0;
    d0 = (d0 + d) | 0;
  }

  return wordToHex(a0) + wordToHex(b0) + wordToHex(c0) + wordToHex(d0);
}
