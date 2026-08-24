# Secure Communication SDK

Secure Communication SDK 为已有 HTTP 业务增加应用层安全通信能力。客户端把逻辑
method、path、受保护 header 和 body 放入加密信封；服务端完成身份握手、解密、重放
检查、逻辑路由授权和响应加密。业务 Controller、请求模型和幂等语义可以继续复用。

当前线协议为 **protocol v1**，密码套件为
`P256_HKDF_SHA256_AES256_GCM`。protocol v1 可以运行在 HTTP 或 HTTPS 上；生产环境仍
建议使用 TLS 1.2 或更高版本。应用层协议不隐藏目标地址、流量大小、时序以及未纳入
信封的传输元数据。

> 本仓库没有 Git tag。下表中的版本来自当前源码 manifest 或 module path，只说明源码
> 状态，不代表对应制品已发布到 npm、Maven、PyPI 或其他公共仓库。

## 当前源码版本

| 平台 | 当前源码声明 | 适用场景 | 接入文档 |
| --- | --- | --- | --- |
| Spring Boot | `secure-communication-spring-boot-starter:1.1.0-SNAPSHOT` | Servlet 服务端 | [Spring Boot](server/spring-boot/README.md) |
| Go Server | module `github.com/coolxer/secure-communication-server-go`，未声明发布版本 | `net/http` 服务端 | [Go Server](server/go/README.md) |
| Python Server | `secure-communication-server` `1.0.0` | FastAPI / ASGI 服务端 | [Python Server](server/python/README.md) |
| JavaScript | `@coolxer/secure-communication-js` `2.0.0` | Web / H5 | [JavaScript](client/javascript/README.md) |
| Go Client | module `github.com/coolxer/secure-communication-go/v2`，未提供 tag | Host / Server / Emulator | [Go](client/go/README.md) |
| Java | `secure-communication-java:1.1.0-SNAPSHOT` | Java Host / Server / Emulator | [Java](client/java/README.md) |
| Android | `secure-communication` `2.0.0` | Android Agent | [Android](client/android/README.md) |
| iOS | Swift Package `SecureCommunication`，Package.swift 未声明版本 | iOS 13+ / macOS 11+ | [iOS](client/ios/README.md) |

客户端 API 版本和线协议版本彼此独立：JavaScript、Go、Android、iOS 使用统一的 2.x
高层接口语义，Java 当前为 1.1；它们在线上仍交换 protocol v1 信封。

## 最短接入路径

1. 选择 Spring Boot、Go 或 Python 服务端实现。
2. 配置 P-256 服务端身份、会话、重放、安装注册、握手授权和逻辑路由白名单。
3. 通过可信构建或配置渠道把服务端 `keyId` 和公开 SPKI 信任锚交付给客户端。
4. H5 通过精确 Origin 白名单准入，不调用 `enroll`；其他新安装取得短时、单次注册
   令牌并调用 `enroll(token)`。
5. 调用 `initialize` 建立会话，或直接调用会按需初始化的 `request(SecureRequest)`。
6. 长期复用客户端实例；只在主动失效或会话错误后调用 `closeSession`。

以下 JavaScript 示例展示统一请求模型。源码消费和各平台安装方法见对应 README：

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
  protectedHeaders: { code: 'business-code' },
  body: JSON.stringify({ orderId: '10001' })
}));

const result = response.json();
```

统一客户端生命周期为 `enroll`、`initialize`、`request` 和 `closeSession`。配置、请求、
响应和错误字段见[客户端接口契约](docs/客户端接口契约.md)。

## 协议与安全边界

外层入口固定为：

- `POST /sc/v1/handshake`
- `POST /sc/v1/handshake/finish`
- `POST /sc/v1/message`

官方客户端固定访问这些路径。服务端虽然提供消息 `prefix` 配置，但若没有网关映射，
改变对外消息路径会使官方客户端无法互操作；外层信封中的认证路径仍固定为
`/sc/v1/message`。

SDK 提供：

- P-256 服务端身份固定和安装身份持有证明；
- P-256 ECDH、HKDF-SHA256 和方向隔离的 AES-256-GCM 会话；
- 时间戳、会话序号和原子重放保护；
- 加密后的逻辑 method、path、业务 header 和 body；
- Spring Boot、Go 和 Python 服务端逻辑路由白名单；
- 内存开发实现与 Redis 生产扩展。

当前客户端只接通国际套件。`SM2_SM3_SM4_GCM` 只是服务端 Provider 扩展标识，仓库中
未交付可启用的国密协议实现；未导出、未接入公共运行时的辅助源码也不构成受支持能力。

SDK 不提供旧协议降级、TCP/UDP/WebSocket 适配、流量伪装、URL 混淆、客户端防逆向、
业务权限控制或业务幂等。原明文业务入口必须在网关、网络或应用边界被阻断。

## 请求、会话与重试

`requestId` 是协议传输关联 ID，不替代业务 message ID、batch ID 或幂等键。SDK 不自动
重试业务请求。发生网络失败或 `SC_UNKNOWN_SESSION` 后，调用方必须根据业务幂等语义
决定是否重新调用 `request`；新的调用会使用新序号、新 nonce 和新 request ID，不能
缓存并原样重放旧密文。

客户端统一暴露 `SecureError.code`、HTTP status、可选 trace ID 和底层 cause。没有收到
HTTP 响应时，Java、Go、Android 和 iOS 使用 `0` 表示缺失的 HTTP status；JavaScript
对应字段为未定义。完整协议错误见[协议错误码](docs/protocol/协议错误码.md)。

## 文档导航

- [文档中心](docs/文档中心.md)
- [完整接入与调试指南](docs/接入与调试指南.md)
- [客户端接口契约](docs/客户端接口契约.md)
- [客户端 2.0 迁移指南](docs/客户端2.0迁移指南.md)
- [协议 v1 规范](docs/protocol/协议v1.md)
- [协议错误码](docs/protocol/协议错误码.md)
- [兼容性策略](docs/兼容性策略.md)
- [威胁模型](docs/security/威胁模型.md)
- [Security Policy](SECURITY.md)
- [Changelog](CHANGELOG.md)

## 源码验证

```bash
cd client/javascript && npm ci && npm test
cd client/go && go test ./...
cd client/java && mvn test package
cd client/android && ./gradlew :secure-communication:testDebugUnitTest :secure-communication:assembleRelease
cd client/ios && swift test
cd server/spring-boot/spring-boot-starter-sc && mvn test
cd server/go && go test ./...
cd server/python && python -m pytest && python -m build
```

部分命令需要预先安装平台 SDK、测试依赖或允许本地测试进程监听端口。测试向量位于
[`protocol/test-vectors`](protocol/test-vectors/)。
