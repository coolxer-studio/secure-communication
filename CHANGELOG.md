# Changelog

版本标题记录当前源码里程碑，不证明相应制品已经发布到公共仓库。发布状态应通过组织
制品仓库或 Git tag 单独确认；当前仓库没有 Git tag。

## Unreleased

### Changed

- JavaScript、Go、Java、Android 和 iOS 客户端同时接受 HTTP 与 HTTPS base URL，并删除 `allowInsecureLoopbackForTesting` 配置项。
- Spring Boot、Go 和 Python 服务端保留可选 TLS 强制策略，但默认同时接受 HTTP 与 HTTPS。
- HTTPS 继续使用平台 TLS 校验；HTTP 的启用和外围安全策略由宿主企业负责。

## Client API 2.0 source milestone

### Changed

- JavaScript、Go、Android 和 iOS 客户端统一为 `request(SecureRequest)` 正式入口。
- JavaScript 与 Android manifest 声明 `2.0.0`，Go module path 使用 `/v2`；iOS
  Package.swift 不声明版本。
- 四端统一配置、响应、错误、身份 SPI、超时/取消和并发初始化语义。
- Go module path 升级为 `/v2`；四端使用全新的 v2 身份 namespace，升级后重新注册。
- 移除散参数请求和公开低层旁路入口，线协议继续保持 protocol v1。

## 1.1.0-SNAPSHOT

### Added

- Java SDK 和 Spring Boot Starter 支持需要一次性注册令牌的 `SERVER` 设备类型。

## Protocol v1 / 1.0.0 baseline

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
- TLS 1.2+、服务端身份固定、平台适配的安装身份保护、AEAD 完整性和传输序号重放保护。
