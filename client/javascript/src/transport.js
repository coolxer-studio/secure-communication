import {
  SecureCommunicationError,
  V2_ENVELOPE_MEDIA_TYPE
} from './v2.js';

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
  var endpoint = baseUrl + (configuration.endpoint || '/sc/v2');

  return async function secureFetch(path, init) {
    init = init || {};
    var method = String(init.method || 'GET').toUpperCase();
    var contentType = getHeader(init.headers, 'content-type')
      || 'application/octet-stream';
    var encoded = await configuration.codec.encodeRequest({
      method: method,
      path: path,
      contentType: contentType,
      body: init.body == null ? new Uint8Array(0) : init.body
    });
    var requestEnvelope = JSON.parse(encoded);
    var response = await fetchImplementation(endpoint, {
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
    var responseText = await response.text();
    var responseMediaType = String(
      getHeader(response.headers, 'content-type') || '').split(';', 1)[0].trim();
    if (responseMediaType.toLowerCase() !== V2_ENVELOPE_MEDIA_TYPE) {
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
      method: method,
      path: path
    });
    return {
      status: decoded.status,
      ok: decoded.status >= 200 && decoded.status < 300,
      contentType: decoded.contentType,
      body: decoded.body,
      text: decoded.text,
      json: function json() {
        return JSON.parse(decoded.text());
      }
    };
  };
}

/**
 * Explicit v1 H5 transport for hosts that still require XMLHttpRequest.
 * It never falls back from v2 and therefore requires a caller-supplied H5 codec.
 */
export function createLegacyH5Xhr(configuration) {
  configuration = configuration || {};
  if (!configuration.codec) {
    throw new TypeError('legacy H5 codec is required');
  }
  var Xhr = configuration.XMLHttpRequest
    || (typeof XMLHttpRequest !== 'undefined' ? XMLHttpRequest : null);
  if (!Xhr) {
    throw new TypeError('XMLHttpRequest implementation is required');
  }
  var baseUrl = requireHttps(
    configuration.baseUrl, configuration.allowInsecureForTesting === true);

  return function legacyH5Request(path, plainText) {
    return new Promise(function perform(resolve, reject) {
      var xhr = new Xhr();
      xhr.open('POST', baseUrl + '/sc/h5' + path, true);
      xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
      xhr.onload = function loaded() {
        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new SecureCommunicationError(
            'SC_LEGACY_TRANSPORT_FAILED',
            'Legacy H5 transport failed',
            { status: xhr.status }));
          return;
        }
        try {
          resolve(configuration.codec.decodeResponse(xhr.responseText));
        } catch (error) {
          reject(error);
        }
      };
      xhr.onerror = function failed() {
        reject(new SecureCommunicationError(
          'SC_NETWORK_FAILED', 'Legacy H5 network request failed'));
      };
      xhr.send(configuration.codec.encodeRequest(plainText));
    });
  };
}
