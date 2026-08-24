function pushCodePoint(bytes, codePoint) {
  if (codePoint <= 0x7f) {
    bytes.push(codePoint);
  } else if (codePoint <= 0x7ff) {
    bytes.push(
      0xc0 | (codePoint >>> 6),
      0x80 | (codePoint & 0x3f)
    );
  } else if (codePoint <= 0xffff) {
    bytes.push(
      0xe0 | (codePoint >>> 12),
      0x80 | ((codePoint >>> 6) & 0x3f),
      0x80 | (codePoint & 0x3f)
    );
  } else {
    bytes.push(
      0xf0 | (codePoint >>> 18),
      0x80 | ((codePoint >>> 12) & 0x3f),
      0x80 | ((codePoint >>> 6) & 0x3f),
      0x80 | (codePoint & 0x3f)
    );
  }
}

export function utf8Encode(value) {
  var bytes = [];
  var index;

  for (index = 0; index < value.length; index += 1) {
    var first = value.charCodeAt(index);
    var codePoint = first;

    if (first >= 0xd800 && first <= 0xdbff) {
      if (index + 1 < value.length) {
        var second = value.charCodeAt(index + 1);
        if (second >= 0xdc00 && second <= 0xdfff) {
          codePoint = 0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00);
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

export function utf8Decode(bytes) {
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
      codePoint = ((first & 0x1f) << 6) | continuation(bytes, index + 1);
      size = 2;
    } else if (first >= 0xe0 && first <= 0xef) {
      if (index + 2 >= bytes.length) {
        throw new Error('Invalid UTF-8 data');
      }
      var second = bytes[index + 1];
      if ((first === 0xe0 && second < 0xa0) || (first === 0xed && second >= 0xa0)) {
        throw new Error('Invalid UTF-8 data');
      }
      codePoint =
        ((first & 0x0f) << 12) |
        (continuation(bytes, index + 1) << 6) |
        continuation(bytes, index + 2);
      size = 3;
    } else if (first >= 0xf0 && first <= 0xf4) {
      if (index + 3 >= bytes.length) {
        throw new Error('Invalid UTF-8 data');
      }
      var next = bytes[index + 1];
      if ((first === 0xf0 && next < 0x90) || (first === 0xf4 && next >= 0x90)) {
        throw new Error('Invalid UTF-8 data');
      }
      codePoint =
        ((first & 0x07) << 18) |
        (continuation(bytes, index + 1) << 12) |
        (continuation(bytes, index + 2) << 6) |
        continuation(bytes, index + 3);
      size = 4;
    } else {
      throw new Error('Invalid UTF-8 data');
    }

    if (codePoint <= 0xffff) {
      result += String.fromCharCode(codePoint);
    } else {
      codePoint -= 0x10000;
      result += String.fromCharCode(
        0xd800 | (codePoint >>> 10),
        0xdc00 | (codePoint & 0x3ff)
      );
    }
    index += size;
  }

  return result;
}
