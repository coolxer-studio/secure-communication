# Changelog

## Client SDK 2.0.0

### Changed

- JavaScript、Go、Android 和 iOS 客户端统一为 `request(SecureRequest)` 正式入口。
- 四端统一配置、响应、错误、身份 SPI、超时/取消和并发初始化语义。
- Go module path 升级为 `/v2`；四端使用全新的 v2 身份 namespace，升级后重新注册。
- 移除散参数请求和公开低层旁路入口，线协议继续保持 protocol v1。

## 1.1.0-SNAPSHOT

### Added

- Java SDK 和 Spring Boot Starter 支持需要一次性注册令牌的 `SERVER` 设备类型。

## 1.0.0

首个正式安全通信协议版本。

### Added

- `P-256 + HKDF-SHA256 + AES-256-GCM` 双向认证会话协议。
- Spring Boot、Go `net/http`、Python FastAPI/ASGI 服务端，以及 JavaScript、纯 Go、标准 Java 17、Android、iOS 客户端。
- 跨端客户端接口契约，以及 Java 的统一 `SecureRequest`、`SecureResponse`、`SecureError`、身份存储 SPI 和同步/异步入口。
- Redis 会话、注册身份、一次性注册令牌和重放窗口实现。
- `/sc/v1/handshake`、`/sc/v1/handshake/finish`、`/sc/v1/message` 统一入口。
- 跨语言测试向量、协议说明、威胁模型和发布检查。

### Security

- 删除旧共享密钥、SM4-ECB、同步 Socket、固定主机和降级路径。
- SDK 不提供旧接口兼容层，也不会自动重试业务请求。
- TLS 1.2+、服务端身份固定、非导出安装密钥、AEAD 完整性和传输序号重放保护。
