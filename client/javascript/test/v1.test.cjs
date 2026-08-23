const assert = require('node:assert/strict');
const test = require('node:test');

const publicApi = require('../dist/index.cjs');
let V1_ENVELOPE_MEDIA_TYPE;
let createSecureFetch;
let createV1Codec;
let deriveAesGcmSession;
let importAesGcmSession;
let normalizeV1Path;
let verifyP256Transcript;

test.before(async () => {
  const protocol = await import('../src/v1.js');
  const transport = await import('../src/transport.js');
  ({
    V1_ENVELOPE_MEDIA_TYPE,
    createV1Codec,
    deriveAesGcmSession,
    importAesGcmSession,
    normalizeV1Path,
    verifyP256Transcript
  } = protocol);
  ({ createSecureFetch } = transport);
});

test('package root exposes only the unified high-level contract', () => {
  assert.deepEqual(Object.keys(publicApi).sort(), [
    'IndexedDbIdentityStore', 'MemoryIdentityStore', 'SecureClient',
    'SecureClientConfig', 'SecureError', 'SecureRequest', 'SecureResponse',
    'createSecureClient'
  ]);
  const request = new publicApi.SecureRequest({ logicalPath: '/health' });
  assert.equal(request.method, 'GET');
  assert.equal(request.contentType, 'application/octet-stream');
  assert.equal(request.body.length, 0);
  assert.throws(
    () => new publicApi.SecureRequest({ logicalPath: 'https://example.test/x' }),
    /path/);
  const error = new publicApi.SecureError('SC_TEST', 'test', {
    httpStatus: 409, traceId: 'trace'
  });
  assert.equal(error.httpStatus, 409);
  assert.equal(error.traceId, 'trace');
});

test('configuration accepts HTTP and HTTPS and rejects other URL forms', () => {
  const common = {
    appId: 'agent',
    serverTrustAnchors: { kid: 'spki' },
    identityStore: new publicApi.MemoryIdentityStore(),
    fetch: async () => {}
  };
  const config = new publicApi.SecureClientConfig({
    ...common,
    baseUrl: 'http://127.0.0.1:8080',
    deviceType: 'server'
  });
  assert.equal(config.deviceType, 'SERVER');
  assert.equal(config.requestTimeoutMillis, 15000);
  assert.equal(config.allowedClockSkewMillis, 120000);
  const publicHttp = new publicApi.SecureClientConfig({
    ...common,
    baseUrl: 'http://192.0.2.10:8080'
  });
  assert.equal(publicHttp.baseUrl, 'http://192.0.2.10:8080');
  ['ftp://example.test', 'https://user:secret@example.test',
    'https://example.test?x=1', 'https://example.test#fragment'].forEach(baseUrl => {
    assert.throws(() => new publicApi.SecureClientConfig({ ...common, baseUrl }), /baseUrl/);
  });
  assert.throws(() => new publicApi.SecureClientConfig({
    ...common,
    baseUrl: 'https://example.test',
    deviceType: 'UNKNOWN'
  }), /deviceType/);
});

test('concurrent initialization is shared and retains token after failure', async () => {
  let starts = 0;
  const tokens = [];
  let release;
  const blocked = new Promise(resolve => { release = resolve; });
  const client = publicApi.createSecureClient({
    baseUrl: 'https://api.example.test',
    appId: 'host-agent',
    deviceType: 'HOST',
    serverTrustAnchors: { kid: 'spki' },
    identityStore: new publicApi.MemoryIdentityStore(),
    fetch: async (url, init) => {
      if (url.endsWith('/handshake')) {
        starts += 1;
        tokens.push(JSON.parse(init.body).enrollmentToken);
        if (starts === 1) await blocked;
      }
      return { ok: true, status: 200, json: async () => ({ v: 0 }) };
    }
  });
  client.enroll('one-time-token');
  const controller = new AbortController();
  const cancelled = client.initialize({ signal: controller.signal });
  const shared = client.initialize();
  controller.abort();
  release();
  await assert.rejects(cancelled, error => error.code === 'SC_REQUEST_CANCELLED');
  await assert.rejects(shared, error => error.code === 'SC_HANDSHAKE_FAILED');
  assert.equal(starts, 1);
  await assert.rejects(client.initialize(), error => error.code === 'SC_HANDSHAKE_FAILED');
  assert.equal(starts, 2);
  assert.deepEqual(tokens, ['one-time-token', 'one-time-token']);
});

const KEY = Uint8Array.from(
  Buffer.from(
    '000102030405060708090a0b0c0d0e0f'
      + '101112131415161718191a1b1c1d1e1f',
    'hex'));
const RESPONSE_KEY = Uint8Array.from(
  Buffer.from(
    '1f1e1d1c1b1a19181716151413121110'
      + '0f0e0d0c0b0a09080706050403020100',
    'hex'));
const NOW = 1785283200000;

async function session() {
  return importAesGcmSession({
    kid: 'test-key-2026-01',
    sid: 'test-session-0001',
    requestKey: KEY,
    responseKey: RESPONSE_KEY,
    requestNoncePrefix: Uint8Array.from([0xa0, 0xa1, 0xa2, 0xa3]),
    responseNoncePrefix: Uint8Array.from([0xb0, 0xb1, 0xb2, 0xb3]),
    expiresAt: NOW + 60_000
  });
}

test('matches the cross-language AES-256-GCM request vector', async () => {
  const codec = createV1Codec(await session(), {
    now: () => NOW,
    initialSequence: 1
  });

  const encoded = await codec.encodeRequest({
    requestId: 'request-0001',
    method: 'post',
    path: '/api/messages?x=1&lang=zh',
    contentType: 'application/json; charset=utf-8',
    body: '{"message":"你好🌍"}'
  });
  const envelope = JSON.parse(encoded);
  assert.equal(encoded.includes('/api/messages'), false);

  assert.deepEqual(
    {
      ...envelope,
      ct: undefined
    },
    {
      v: 1,
      suite: 'P256_HKDF_SHA256_AES256_GCM',
      kid: 'test-key-2026-01',
      sid: 'test-session-0001',
      ts: NOW,
      seq: 1,
      rid: 'request-0001',
      m: 'POST',
      p: '/sc/v1/message',
      cty: 'application/sc-protected+json',
      st: 0,
      nonce: 'oKGiowAAAAAAAAAB',
      ct: undefined
    });
  assert.equal(
    envelope.ct,
    'y43V6x8Hp9N--JdIn9atb-inacLk7T5Q9B7Jzez-vufGdNrX0f2xKw');
});

test('decodes a direction-separated authenticated response', async () => {
  const codec = createV1Codec(await session(), { now: () => NOW });
  const nonce = Uint8Array.from(
    Buffer.from('b0b1b2b30000000000000001', 'hex'));
  const envelope = {
    v: 1,
    suite: 'P256_HKDF_SHA256_AES256_GCM',
    kid: 'test-key-2026-01',
    sid: 'test-session-0001',
    ts: NOW,
    seq: 1,
    rid: 'request-0001',
    m: 'POST',
    p: '/sc/v1/message',
    cty: 'application/sc-protected+json',
    st: 200,
    nonce: Buffer.from(nonce).toString('base64url'),
    ct: ''
  };
  const aad = new TextEncoder().encode([
    'SC1', 'response', envelope.suite, envelope.kid, envelope.sid,
    String(envelope.ts), String(envelope.seq), envelope.rid, envelope.m, envelope.p,
    envelope.cty, String(envelope.st)
  ].join('\n'));
  const serverKey = await crypto.subtle.importKey(
    'raw', RESPONSE_KEY, { name: 'AES-GCM' }, false, ['encrypt']);
  const sealed = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce, additionalData: aad, tagLength: 128 },
    serverKey,
    new TextEncoder().encode('{"ok":true}'));
  envelope.ct = Buffer.from(sealed).toString('base64url');
  assert.equal(
    envelope.ct,
    '2Q9-vvRfgB3ZDenLUzdtXout2_wtOCcXZu6C');

  const response = await codec.decodeResponse(JSON.stringify(envelope), {
    sequence: 1,
    requestId: 'request-0001'
  });

  assert.equal(response.contentType, 'application/sc-protected+json');
  assert.equal(response.status, 200);
  assert.equal(response.text(), '{"ok":true}');

  envelope.rid = 'request-other';
  await assert.rejects(
    () => codec.decodeResponse(envelope, { sequence: 1 }),
    error => error.code === 'SC_AUTHENTICATION_FAILED');
});

test('normalizes routing and allocates unique sequences before async work', async () => {
  assert.equal(
    normalizeV1Path('/search?b=2&a=%e4%b8%ad&a=1'),
    '/search?a=%E4%B8%AD&a=1&b=2');
  assert.throws(() => normalizeV1Path('https://example.test/x'), /absolute/);

  const codec = createV1Codec(await session(), { now: () => NOW });
  const [first, second] = await Promise.all([
    codec.encodeRequest({ requestId: 'request-one', method: 'GET', path: '/one' }),
    codec.encodeRequest({ requestId: 'request-two', method: 'GET', path: '/two' })
  ]);
  assert.equal(JSON.parse(first).seq, 1);
  assert.equal(JSON.parse(second).seq, 2);
  assert.notEqual(JSON.parse(first).nonce, JSON.parse(second).nonce);
});

test('fetch adapter tunnels logical methods and never silently downgrades', async () => {
  const calls = [];
  const codec = {
    encodeRequest: async request => JSON.stringify({
      seq: 7,
      logical: request
    }),
    decodeResponse: async (body, expected) => {
      assert.equal(body, '{"sealed":true}');
      assert.equal(expected.sequence, 7);
      assert.match(expected.requestId, /^[!-~]{1,128}$/);
      return {
        body: new TextEncoder().encode(
          '{"contentType":"application/json","body":"e30"}'),
        contentType: 'application/sc-protected+json',
        status: 201,
        text: () => '{}'
      };
    }
  };
  const secureFetch = createSecureFetch({
    baseUrl: 'https://api.example.test',
    codec,
    fetch: async (url, init) => {
      calls.push({ url, init });
      return {
        status: 200,
        ok: true,
        headers: {
          get: name => name.toLowerCase() === 'content-type'
            ? V1_ENVELOPE_MEDIA_TYPE
            : null
        },
        text: async () => '{"sealed":true}'
      };
    }
  });

  const response = await secureFetch('/items?b=2&a=1', { method: 'GET' });

  assert.equal(response.json().constructor, Object);
  assert.equal(response.status, 201);
  assert.equal(calls[0].url, 'https://api.example.test/sc/v1/message');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.redirect, 'error');
  assert.doesNotThrow(() => createSecureFetch({
    baseUrl: 'http://api.example.test',
    codec,
    fetch: async () => {}
  }));
  assert.throws(() => createSecureFetch({
    baseUrl: 'ftp://api.example.test',
    codec,
    fetch: async () => {}
  }), /HTTP or HTTPS/);
});

test('derives identical P-256 ECDH/HKDF session material on both peers', async () => {
  const clientKeys = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const serverKeys = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const common = {
    kid: 'handshake-key',
    sid: 'handshake-session',
    transcriptHash: Uint8Array.from({ length: 32 }, (_, index) => index),
    expiresAt: NOW + 60_000
  };
  const clientSession = await deriveAesGcmSession({
    ...common,
    localEphemeralPrivateKey: clientKeys.privateKey,
    peerEphemeralPublicKey: serverKeys.publicKey
  });
  const serverSession = await deriveAesGcmSession({
    ...common,
    localEphemeralPrivateKey: serverKeys.privateKey,
    peerEphemeralPublicKey: clientKeys.publicKey
  });
  const clientCodec = createV1Codec(clientSession, {
    now: () => NOW,
    initialSequence: 1
  });
  const serverCodec = createV1Codec(serverSession, {
    now: () => NOW,
    initialSequence: 1
  });

  const [clientEnvelope, serverEnvelope] = await Promise.all([
    clientCodec.encodeRequest({ requestId: 'request-derived', method: 'POST', path: '/derived', body: 'hello' }),
    serverCodec.encodeRequest({ requestId: 'request-derived', method: 'POST', path: '/derived', body: 'hello' })
  ]);

  assert.deepEqual(JSON.parse(clientEnvelope), JSON.parse(serverEnvelope));
});

test('verifies the authenticated handshake transcript before derivation', async () => {
  const signingKeys = await crypto.subtle.generateKey(
    { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const hash = Uint8Array.from({ length: 32 }, (_, index) => 31 - index);
  const signature = new Uint8Array(await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    signingKeys.privateKey,
    hash));
  assert.equal(signature.length, 64);
  assert.equal(await verifyP256Transcript(
    hash, signature, signingKeys.publicKey), true);
  signature[0] ^= 1;
  assert.equal(await verifyP256Transcript(
    hash, signature, signingKeys.publicKey), false);
});
