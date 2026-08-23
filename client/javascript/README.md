# Secure Communication JavaScript SDK 2.0

浏览器 WebCrypto 客户端，线协议保持 v1，公共 API 按统一客户端契约升级为 2.0。

```bash
npm install --save-exact @coolxer/secure-communication-js@2.0.0
```

```js
import {
  SecureRequest,
  createSecureClient
} from '@coolxer/secure-communication-js';

const client = createSecureClient({
  baseUrl: 'https://api.example.com',
  appId: 'my-web',
  deviceType: 'H5',
  serverTrustAnchors: {
    'server-key-2026': '<P-256 SPKI Base64URL>'
  }
});

const response = await client.request(new SecureRequest({
  method: 'POST',
  logicalPath: '/orders/query',
  contentType: 'application/json',
  protectedHeaders: { code: 'my-protected-business-code' },
  body: JSON.stringify({ orderId: '10001' })
}));
const result = response.json();
```

H5 不调用 `enroll`。默认 `IndexedDbIdentityStore` 在
`coolxer-secure-communication-v2` 中保存不可导出的安装密钥，不读取或删除 1.x 身份。
清理站点数据、无痕环境或更换 Origin 后会产生新身份。

自定义身份存储实现 `loadOrCreate(appId)`，返回具有 `deviceId`、`publicKeySPKI()` 和
`sign(data)` 的身份。调用可通过 `{ signal: AbortSignal }` 取消；默认超时 15 秒，错误
通过 `SecureError.code/httpStatus/traceId/cause` 暴露。

`baseUrl` 可使用 HTTP 或 HTTPS，生产环境建议使用 HTTPS。非安全上下文中的浏览器
可能不提供 WebCrypto，此时 SDK 返回 `SC_CRYPTO_UNAVAILABLE`；SDK 不绕过浏览器的
安全上下文策略。SDK 不重试业务请求；`SC_UNKNOWN_SESSION` 会清除会话，本次调用仍
失败，由业务决定是否创建新的 SecureRequest 重试。

2.0 根包不再导出 `createSecureFetch` 或 codec 原语，也不兼容散参数 `request`。
迁移说明见[客户端 2.0 迁移指南](../../docs/客户端2.0迁移指南.md)。

```bash
npm ci
npm test
```
