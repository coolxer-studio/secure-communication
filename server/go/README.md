# Secure Communication Go Server（protocol v1）

本目录是协议 v1 的 Go 服务端实现，结构对应 Spring Boot 的 Starter 与 Demo：根包提供协议与 SPI，`httpadapter` 提供 `net/http` 接入，`redisstore` 提供生产状态实现，`cmd/demo` 是最小宿主。

## 工程结构

| 路径 | 作用 |
| --- | --- |
| 根包 | 协议模型、严格 codec、握手、P-256/HKDF/AES-GCM、安全策略和 SPI |
| `httpadapter` | 固定握手入口、消息 Handler、逻辑请求重写和响应加密 |
| `redisstore` | 会话、安装身份、注册令牌、重放保护和会话记录加密 |
| `cmd/demo` | 默认关闭安全通信的最小 `net/http` 宿主 |

## 要求与安装

- Go 1.21 或更高版本；
- 支持 HTTP 和 HTTPS，生产入口建议使用 TLS 1.2+；
- 集群部署使用 Redis，并用 32 字节记录密钥加密会话材料。

`go.mod` 声明 module `github.com/coolxer/secure-communication-server-go`，但不包含源码
版本且当前仓库没有 `v1.0.0` tag。从本地源码接入消费项目：

```bash
go mod edit -require=github.com/coolxer/secure-communication-server-go@v0.0.0
go mod edit -replace=github.com/coolxer/secure-communication-server-go=/absolute/path/to/secure-communication/server/go
```

## 最小接入

```go
config := securecommunication.DefaultConfig()
config.Enabled = true
sessions := securecommunication.NewMemorySessionRepository(nil) // 仅开发
replay := securecommunication.NewMemoryReplayProtector(nil)     // 仅开发
installations := securecommunication.NewMemoryInstallationRegistry()
messages, _ := securecommunication.NewMessageService(sessions, replay, config)
handshakes := &securecommunication.HandshakeService{
    Identity: identity, Sessions: sessions, Installations: installations,
    Tokens: tokens, Authorizer: handshakeAuthorizer, Config: config,
}
handler := httpadapter.New(config, handshakes, messages, routeAuthorizer, businessHandler)
```

必须显式提供服务端身份、握手授权、注册令牌和精确逻辑路由白名单。默认拒绝实现保持失败关闭。外层入口固定为 `/sc/v1/handshake`、`/sc/v1/handshake/finish` 和 `/sc/v1/message`。
官方客户端固定访问这些入口；若修改 `Config.Prefix`，必须由外部路由继续暴露固定
消息入口，否则无法与官方客户端互操作。

## 扩展接口

| Interface | 宿主责任 |
| --- | --- |
| `ServerIdentityProvider` | 提供稳定 `keyId`、公开 SPKI 并签署握手 transcript |
| `SessionRepository` / `KeyProvider` | 保存 pending/active 会话并提供消息密钥 |
| `ReplayProtector` | 原子认领 `session + direction + seq` |
| `InstallationRegistry` | 保存 app/device 对应的安装身份公钥 |
| `EnrollmentTokenService` | 签发并单次消费原生端注册令牌 |
| `HandshakeAuthorizer` | 校验 appId、deviceType、Origin 和业务准入 |
| `LogicalRouteAuthorizer` | 只允许必要的 method 与规范化逻辑 path |
| `SessionRecordProtector` | 对写入共享状态的会话密钥材料二次加密 |
| `AlgorithmProvider` / `SecurityPolicy` | 扩展经过审计的套件或宿主安全策略 |

现有握手和全部客户端固定为国际套件。`AlgorithmProvider` 只是消息层扩展边界，不能
通过单独注册实现启用另一套端到端 suite。

`HandshakeAuthorizerFunc` 与 `LogicalRouteAuthorizerFunc` 可直接包装函数。业务 Handler
可以通过 `httpadapter.TransportTrustFrom(request.Context())` 读取认证后的套件和 sid。

## Redis 生产配置

```go
redisClient := redis.NewClient(&redis.Options{Addr: "127.0.0.1:6379"})
recordProtector, err := redisstore.NewAESGCMRecordProtector(recordKey)
if err != nil {
    return err
}
sessions := &redisstore.SessionRepository{
    Redis: redisClient, Prefix: "sc:v1:session", Protector: recordProtector,
}
replay := redisstore.ReplayProtector{Redis: redisClient, Prefix: "sc:v1:replay"}
installations := redisstore.InstallationRegistry{
    Redis: redisClient, Prefix: "sc:v1:installation",
}
tokens := redisstore.EnrollmentTokens{
    Redis: redisClient, Prefix: "sc:v1:enrollment",
}
```

生产环境使用 `redisstore.SessionRepository`、`ReplayProtector`、`InstallationRegistry` 与 `EnrollmentTokens`。四类数据使用独立前缀；会话仓库需要 `NewAESGCMRecordProtector(recordKey)`，`recordKey` 必须正好 32 字节且不得进入源码、镜像或日志。

库默认与 Spring 属性一致：安全通信关闭、v1 开启、不强制 TLS、时钟偏差 5 分钟、会话及重放 TTL 10 分钟、信封上限 1,400,000 字节、明文及 body 上限 1,048,576 字节。企业需要只接受 HTTPS 时设置 `RequireTLS=true`。若业务 body 最大 256 KiB，建议分别设置 `MaxEnvelopeBytes=600000`、`MaxPlaintextBytes=400000`、`MaxBodyBytes=262144`。

启用 `RequireTLS` 后，库只根据 `http.Request.TLS` 判断安全连接，不信任 `X-Forwarded-Proto`。代理后的 TLS 状态应由宿主受信代理配置建立。H5 CORS 预检需要在本 Handler 之前处理或交给下游，且只开放可信 Origin。

## Demo 与验证

```bash
go run ./cmd/demo
go test ./...
```

Demo 监听 `127.0.0.1:6789`，`SC_ENABLED` 默认 false。它不包含生产身份、授权或 Redis，不能仅打开开关后用于端到端或生产部署。

## 相关文档

- [接入与调试指南](../../docs/接入与调试指南.md)
- [协议 v1](../../docs/protocol/协议v1.md)
- [协议错误码](../../docs/protocol/协议错误码.md)
- [兼容性策略](../../docs/兼容性策略.md)
