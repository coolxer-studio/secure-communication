const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const { createH5Codec } = require('../dist/index.cjs');

const APP_ID = '1596861234c4ea6ddd041d45b3912345';
const REQUEST_PLAIN_TEXT = '{"common":["7b5b925a-0cd5-42f4-a64f-5ceba73d6fed","function getTime() { [native code] }","1.0.0","1","测试web","","-","h5","-","-","-","-","其他网络","-","-",null,null,"-","2023-08-10T01:52:23.834Z"]}';
const REQUEST_BODY = '697FAF50DE96323E543C690263B3BE064B29AC056D83B61971CA85D3DD068C1758DC5ADC82421B6401E7404225377C9B724A95BD5E223C7779896930A96365AC59F74D8E370B7E4075EACF13AEA2A3CE3EB66585D25408D215EC7586374255C7271EC0B0FA42F14852872A5866E90CCD3874101CF78B99450F4749CE53F960E19D0E7404B2D88AF63D9FEA996378F5AF7D4DFCE4984F0ADB15838F386C19EC0450BFB0D938451462226B82410012EB49EAFBC9D1C0F7AB17B9D8950C6191B43C6DD4C91D6357728362E7CE92EC4EFD541596861234C4EA6DDD041D45B3912345';

test('matches the Spring Boot H5 request fixture', () => {
  const codec = createH5Codec(APP_ID);
  assert.equal(codec.encodeRequest(REQUEST_PLAIN_TEXT), REQUEST_BODY);

  const cipherHex = REQUEST_BODY.slice(0, -32);
  assert.equal(codec.decrypt(cipherHex), REQUEST_PLAIN_TEXT);
  assert.equal(codec.decrypt(cipherHex.toLowerCase()), REQUEST_PLAIN_TEXT);
});

test('round-trips empty and Unicode text', () => {
  const codec = createH5Codec(APP_ID);
  const values = ['', '中文通信', '{"message":"emoji 😀 与𠮷"}'];

  for (const value of values) {
    const cipherHex = codec.encrypt(value);
    assert.match(cipherHex, /^[0-9A-F]+$/);
    assert.equal(cipherHex.length % 32, 0);
    assert.equal(codec.decodeResponse(cipherHex), value);
  }
});

test('appends the normalized 32-character request suffix', () => {
  const mixedCaseId = '2396641245c4fe6ttd041565b3651245';
  const codec = createH5Codec(mixedCaseId);
  assert.equal(codec.encodeRequest('{}').slice(-32), mixedCaseId.toUpperCase());
});

test('rejects invalid inputs and corrupt ciphertext', () => {
  assert.throws(() => createH5Codec('short'), /32 characters/);
  assert.throws(() => createH5Codec(null), /must be a string/);

  const codec = createH5Codec(APP_ID);
  assert.throws(() => codec.encrypt({}), /must be a string/);
  assert.throws(() => codec.decrypt('ABC'), /hexadecimal string/);
  assert.throws(() => codec.decrypt('GG'), /hexadecimal string/);
  assert.throws(() => codec.decrypt('00'.repeat(16)), /padding/);
});

test('loads the CommonJS, ES module, and UMD entry points', async () => {
  const esm = await import('../dist/index.esm.js');
  assert.equal(typeof esm.createH5Codec, 'function');

  const umdSource = fs.readFileSync(path.resolve(__dirname, '../dist/index.umd.js'), 'utf8');
  const sandbox = {};
  vm.runInNewContext(umdSource, sandbox);
  assert.equal(typeof sandbox.SecureCommunicationJS.createH5Codec, 'function');
});
