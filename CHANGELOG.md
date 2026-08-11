# Changelog

## 1.0.0

首个正式安全通信协议版本。

### Added

- `P-256 + HKDF-SHA256 + AES-256-GCM` 双向认证会话协议。
- Spring Boot、Go `net/http`、Python FastAPI/ASGI 服务端，以及 JavaScript、纯 Go、Android、iOS 客户端。
- Redis 会话、注册身份、一次性注册令牌和重放窗口实现。
- `/sc/v1/handshake`、`/sc/v1/handshake/finish`、`/sc/v1/message` 统一入口。
- 跨语言测试向量、协议说明、威胁模型和发布检查。

### Security

- 删除旧共享密钥、SM4-ECB、同步 Socket、固定主机和降级路径。
- SDK 不提供旧接口兼容层，也不会自动重试业务请求。
- TLS 1.2+、服务端身份固定、非导出安装密钥、AEAD 完整性和传输序号重放保护。
