# @coolxer/secure-communication-js

JavaScript 客户端同时提供：

- v2 WebCrypto 协议核心与 Promise/fetch 官方适配器；
- 显式的 XHR H5 v1 迁移适配器；
- 原有 `createH5Codec(appId)` 字节兼容实现。

版本：`0.2.0`。包提供 CommonJS、ES Module 和 UMD 产物；UMD 全局名为
`SecureCommunicationJS`。

## v2

```js
import {
  createSecureFetch,
  createV2Codec,
  importAesGcmSession
} from '@coolxer/secure-communication-js';

const session = await importAesGcmSession({
  kid,
  sid,
  requestKey,
  responseKey,
  requestNoncePrefix,
  responseNoncePrefix,
  expiresAt
});
const codec = createV2Codec(session);
const secureFetch = createSecureFetch({
  baseUrl: 'https://api.example.com',
  codec
});

const response = await secureFetch('/messages?lang=zh', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ message: 'hello' })
});
const value = response.json();
```

`requestKey` 和 `responseKey` 是 32 字节的已认证握手结果；
`importAesGcmSession` 将其导入为不可导出的 AES-GCM `CryptoKey`。生产代码不能把
这些字节写入源码、localStorage 或 Bundle。每次并发调用会在异步加密前分配独立
序列号；失败重试需要重新调用，不能重发相同信封。

`createSecureFetch`：

- 强制 HTTPS；
- 将逻辑 HTTP 方法和规范化路径写入认证信封；
- 统一通过 `POST /sc/v2` 传输；
- 使用宿主提供的 `fetch`、`AbortSignal`、credentials 配置；
- 认证/路由失败直接抛出 `SecureCommunicationError`，不会进入 v1。

## H5 v1 compatibility

```js
import {
  createH5Codec,
  createLegacyH5Xhr
} from '@coolxer/secure-communication-js';

const codec = createH5Codec('1596861234c4ea6ddd041d45b3912345');
const body = codec.encodeRequest(JSON.stringify({ message: 'hello' }));

const legacyRequest = createLegacyH5Xhr({
  baseUrl: 'https://api.example.com',
  codec
});
const plaintext = await legacyRequest('/messages', '{"message":"hello"}');
```

`createH5Codec` 仍提供 `encrypt`、`decrypt`、`encodeRequest` 和
`decodeResponse`。appId 必须为 32 字符；非法 Hex、块长度和 PKCS#7 padding
均抛错。

H5 v1 使用：

```text
key = MD5(lowercase(appId) + "_bsdk_")[0..15]
iv = key
request = UPPER_HEX(legacy-SM4-CBC-PKCS7(UTF8(plaintext))) + uppercase(appId)
```

它为兼容历史 Spring 实现保留了非标准轮转行为，不得标识为通用 GB/T 32907
实现，也不提供认证、防篡改或防重放。

## Build

```bash
npm ci
npm test
npm pack --dry-run
```

测试覆盖 H5 固定报文、Unicode/emoji、非法输入、v2 跨语言 AES-GCM 向量、
方向隔离、路由篡改、并发序列以及 CJS/ESM/UMD 入口。
