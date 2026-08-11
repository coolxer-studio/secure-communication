import {
  SecureCommunicationError,
  V1_ENVELOPE_MEDIA_TYPE,
  normalizeV1ContentType,
  normalizeV1Method,
  normalizeV1Path
} from './v1.js';
import { utf8Decode, utf8Encode } from './utf8.js';

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
  while (padded.length % 4) { padded += '='; }
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
    throw new SecureCommunicationError('SC_INVALID_ENVELOPE', null, { cause: error });
  }
  if (!value || Array.isArray(value)
      || Object.keys(value).sort().join('|') !== 'body|contentType'
      || typeof value.contentType !== 'string') {
    throw new SecureCommunicationError('SC_INVALID_ENVELOPE');
  }
  return { contentType: normalizeV1ContentType(value.contentType), body: decodeBase64Url(value.body) };
}

function protectedPayload(init, configuredNames) {
  var values = {};
  var names = configuredNames || ['code'];
  names.forEach(function collect(name) {
    var normalized = String(name).toLowerCase();
    if (!/^[a-z0-9-]{1,64}$/.test(normalized)
        || normalized === 'content-type') {
      throw new TypeError('protected header name is invalid');
    }
    var value = getHeader(init.headers, normalized);
    if (value != null) {
      values[normalized] = String(value);
    }
  });
  Object.keys(init.protectedHeaders || {}).forEach(function explicit(name) {
    var normalized = name.toLowerCase();
    if (!/^[a-z0-9-]{1,64}$/.test(normalized)
        || /[\r\n]/.test(String(init.protectedHeaders[name]))) {
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

export function createSecureFetch(configuration) {
  configuration = configuration || {};
  if (!configuration.codec) {
    throw new TypeError('codec is required');
  }
  var fetchImplementation = configuration.fetch
    || (typeof fetch === 'function' ? fetch : null);
  if (!fetchImplementation) {
    throw new TypeError('fetch implementation is required');
  }
  var baseUrl = requireHttps(
    configuration.baseUrl, configuration.allowInsecureForTesting === true);
  var endpoint = baseUrl + (configuration.endpoint || '/sc/v1/message');

  return async function secureFetch(path, init) {
    init = init || {};
    var method = String(init.method || 'GET').toUpperCase();
    var contentType = getHeader(init.headers, 'content-type')
      || 'application/octet-stream';
    var requestId = init.requestId || newRequestId();
    var payloadInit = Object.assign({}, init, {
      method: method,
      logicalPath: path,
      logicalContentType: contentType
    });
    var encoded = await configuration.codec.encodeRequest({
      requestId: requestId,
      body: protectedPayload(payloadInit, configuration.protectedHeaderNames)
    });
    var requestEnvelope = JSON.parse(encoded);
    var response = await fetchImplementation(endpoint, {
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
    var responseText = await response.text();
    var responseMediaType = String(
      getHeader(response.headers, 'content-type') || '').split(';', 1)[0].trim();
    if (responseMediaType.toLowerCase() !== V1_ENVELOPE_MEDIA_TYPE) {
      var errorBody;
      try {
        errorBody = JSON.parse(responseText);
      } catch (ignored) {
        errorBody = {};
      }
      throw new SecureCommunicationError(
        errorBody.code || 'SC_TRANSPORT_FAILED',
        errorBody.message || 'Secure transport failed',
        { status: response.status, traceId: errorBody.traceId });
    }
    var decoded = await configuration.codec.decodeResponse(responseText, {
      sequence: requestEnvelope.seq,
      requestId: requestId
    });
    var protectedResult = protectedResponse(decoded);
    return {
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
    };
  };
}
