# Secure Communication JavaScript SDK 1.0

浏览器客户端基于 WebCrypto 实现协议 v1，适用于 Web/H5。SDK 完成服务端身份固定、
P-256 握手、会话密钥派生、AES-256-GCM 请求/响应保护和序号管理。项目总览见
[根 README](../../README.md)。

## 安装

```bash
npm install --save-exact @coolxer/secure-communication-js@1.0.0
```

使用 ESM：

```js
import { createSecureClient } from '@coolxer/secure-communication-js';
```

直接加载构建后的 UMD 文件时，API 位于 `window.SecureCommunicationJS`：

```html
<script src="./index.umd.js"></script>
<script>
  const createSecureClient = window.SecureCommunicationJS.createSecureClient;
</script>
```

生产构建应锁定版本和 lockfile 完整性，不要复制来源不明的压缩包到业务仓库。

## 最小接入

```js
import { createSecureClient } from '@coolxer/secure-communication-js';

const client = createSecureClient({
  baseUrl: 'https://api.example.com',
  appId: 'my-web',
  deviceType: 'H5',
  serverTrustAnchors: {
    'server-key-2026': '<P-256 SPKI Base64URL>'
  }
});

await client.initialize();

const response = await client.request(
  'POST',
  '/orders/query',
  { code: 'my-protected-business-code' },
  JSON.stringify({ orderId: '10001' }),
  crypto.randomUUID()
);

if (!response.ok) {
  throw new Error(`business request failed: ${response.status}`);
}
const result = await response.json();
```

`baseUrl` 必须使用 HTTPS。信任锚的 key 必须等于服务端返回的 `kid`，value 必须是
同一 P-256 服务端身份公钥的 SPKI Base64URL。信任锚是公开信息，但必须通过可信
构建或配置渠道分发，不能在运行时从待连接服务端下载后直接信任。

`code` 等业务 header 应放在 `protectedHeaders`，不要再作为外层 fetch header。
逻辑 path 必须已加入服务端 `LogicalRouteAuthorizer` 白名单。

## H5 身份和注册

H5 不调用 `enroll`。SDK 首次使用时生成不可导出的 WebCrypto P-256 安装私钥，并
将 `CryptoKey` 保存到 IndexedDB。清理站点数据、无痕环境或更换 Origin 后可能生成
新的安装身份。

服务端应同时校验 appId 和精确 Origin allow-list。Origin 只能作为浏览器准入信号，
不能替代 HTTPS、服务端公钥固定和安装密钥持有证明。不要把前端 appKey 当作秘密。

## CORS

服务端需要允许受信 Origin 访问：

- `POST /sc/v1/handshake`
- `POST /sc/v1/handshake/finish`
- `POST /sc/v1/message`

预检只需开放 `POST`、`OPTIONS` 和 `Content-Type`。CORS Filter 必须早于服务端安全
消息 Filter；不要开放外层 `code`。如果页面和服务端分别使用 `localhost` 与
`127.0.0.1`，它们是不同 Origin，必须按浏览器实际地址配置。

## 本地源码调试

终端一持续构建 SDK：

```bash
cd client/javascript
npm ci
npm run dev
```

终端二在业务 Demo 根目录启动静态服务器，例如：

```bash
python3 -m http.server 8888
```

仅本机联调时可以显式配置：

```js
const client = createSecureClient({
  baseUrl: 'http://127.0.0.1:11099',
  appId: 'my-web-dev',
  deviceType: 'H5',
  serverTrustAnchors: {
    'server-key-dev': '<P-256 SPKI Base64URL>'
  },
  allowInsecureForTesting: true
});
```

服务端也必须关闭本地 TLS 强制并允许 Demo 的精确 Origin。`allowInsecureForTesting`
不得出现在生产配置中。浏览器必须通过 HTTP/HTTPS 页面打开，不能直接使用 `file://`。

## 会话、重试和关闭

`request` 会在没有有效会话时调用 `initialize`。SDK 不自动重试业务 POST；网络超时
或 `SC_UNKNOWN_SESSION` 后，由业务层决定是否重新调用。每次重试使用新的 request
ID 和密文，但保留业务 message ID、batch ID 和幂等字段。

```js
client.closeSession();
```

关闭只清除内存会话，不删除 IndexedDB 中的安装身份。

## 验证与生产检查

```bash
npm ci
npm test
```

生产发布前确认 HTTPS、CORS、信任锚、appId、逻辑路由和请求大小限制一致，并扫描
页面和 sourcemap，确保没有私钥、注册令牌或调试入口暴露到 `window`。

完整联调和错误排查见[接入与调试指南](../../docs/接入与调试指南.md)，协议
字段见[协议 v1 规范](../../docs/protocol/协议v1.md)。低层 `createV1Codec` 只用于协议测试
和受控传输适配，业务集成应使用 `createSecureClient`。
