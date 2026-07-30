const assert = require('node:assert/strict');
const test = require('node:test');

const {
  V2_ENVELOPE_MEDIA_TYPE,
  createSecureFetch,
  createV2Codec,
  deriveAesGcmSession,
  importAesGcmSession,
  normalizeV2Path,
  verifyP256Transcript
} = require('../dist/index.cjs');

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
  const codec = createV2Codec(await session(), {
    now: () => NOW,
    initialSequence: 1
  });

  const encoded = await codec.encodeRequest({
    method: 'post',
    path: '/api/messages?x=1&lang=zh',
    contentType: 'application/json; charset=utf-8',
    body: '{"message":"你好🌍"}'
  });
  const envelope = JSON.parse(encoded);

  assert.deepEqual(
    {
      ...envelope,
      ct: undefined
    },
    {
      v: 2,
      suite: 'SC2_P256_HKDF_SHA256_AES_256_GCM',
      kid: 'test-key-2026-01',
      sid: 'test-session-0001',
      ts: NOW,
      seq: 1,
      m: 'POST',
      p: '/api/messages?lang=zh&x=1',
      cty: 'application/json',
      st: 0,
      nonce: 'oKGiowAAAAAAAAAB',
      ct: undefined
    });
  assert.equal(
    envelope.ct,
    'y43V6x8Hp9N--JdIn9atb-inacLk7T5QtmiZGSIKeMAzqGA6pM4MDw');
});

test('decodes a direction-separated authenticated response', async () => {
  const codec = createV2Codec(await session(), { now: () => NOW });
  const nonce = Uint8Array.from(
    Buffer.from('b0b1b2b30000000000000001', 'hex'));
  const envelope = {
    v: 2,
    suite: 'SC2_P256_HKDF_SHA256_AES_256_GCM',
    kid: 'test-key-2026-01',
    sid: 'test-session-0001',
    ts: NOW,
    seq: 1,
    m: 'POST',
    p: '/api/messages?lang=zh&x=1',
    cty: 'application/json',
    st: 200,
    nonce: Buffer.from(nonce).toString('base64url'),
    ct: ''
  };
  const aad = new TextEncoder().encode([
    'SC2', 'response', envelope.suite, envelope.kid, envelope.sid,
    String(envelope.ts), String(envelope.seq), envelope.m, envelope.p,
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
    '2Q9-vvRfgB3ZDen3nAHAJVoAB7nCdfzW28iM');

  const response = await codec.decodeResponse(JSON.stringify(envelope), {
    sequence: 1,
    method: 'POST',
    path: '/api/messages?x=1&lang=zh'
  });

  assert.equal(response.contentType, 'application/json');
  assert.equal(response.status, 200);
  assert.equal(response.text(), '{"ok":true}');

  envelope.p = '/api/other';
  await assert.rejects(
    () => codec.decodeResponse(envelope, { sequence: 1 }),
    error => error.code === 'SC_AUTHENTICATION_FAILED');
});

test('normalizes routing and allocates unique sequences before async work', async () => {
  assert.equal(
    normalizeV2Path('/search?b=2&a=%e4%b8%ad&a=1'),
    '/search?a=%E4%B8%AD&a=1&b=2');
  assert.throws(() => normalizeV2Path('https://example.test/x'), /absolute/);

  const codec = createV2Codec(await session(), { now: () => NOW });
  const [first, second] = await Promise.all([
    codec.encodeRequest({ method: 'GET', path: '/one' }),
    codec.encodeRequest({ method: 'GET', path: '/two' })
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
      assert.deepEqual(expected, {
        sequence: 7,
        method: 'GET',
        path: '/items?b=2&a=1'
      });
      return {
        body: Uint8Array.from([123, 125]),
        contentType: 'application/json',
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
            ? V2_ENVELOPE_MEDIA_TYPE
            : null
        },
        text: async () => '{"sealed":true}'
      };
    }
  });

  const response = await secureFetch('/items?b=2&a=1', { method: 'GET' });

  assert.equal(response.json().constructor, Object);
  assert.equal(response.status, 201);
  assert.equal(calls[0].url, 'https://api.example.test/sc/v2');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.redirect, 'error');
  assert.throws(
    () => createSecureFetch({
      baseUrl: 'http://api.example.test',
      codec,
      fetch: async () => {}
    }),
    /HTTPS/);
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
  const clientCodec = createV2Codec(clientSession, {
    now: () => NOW,
    initialSequence: 1
  });
  const serverCodec = createV2Codec(serverSession, {
    now: () => NOW,
    initialSequence: 1
  });

  const [clientEnvelope, serverEnvelope] = await Promise.all([
    clientCodec.encodeRequest({ method: 'POST', path: '/derived', body: 'hello' }),
    serverCodec.encodeRequest({ method: 'POST', path: '/derived', body: 'hello' })
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
