# Secure Communication iOS SDK 2.0

Swift Package 产品名保持 `SecureCommunication`，正式版本标签为 `2.0.0`，支持 iOS 13+
和 macOS 11+。

```swift
import SecureCommunication

let client = try SecureClient(config: SecureClientConfig(
    baseURL: URL(string: "https://api.example.com")!,
    appID: "my-ios-agent",
    serverTrustAnchors: ["server-key-2026": loadPinnedServerSPKI()]
))

try await client.enroll(enrollmentToken) // 仅 v2 新安装

let response = try await client.request(try SecureRequest(
    method: "POST",
    logicalPath: "/events/upload",
    contentType: "application/json",
    protectedHeaders: ["code": "business-code"],
    body: Data(#"{"events":[]}"#.utf8)
))
let body = try response.text()
```

SecureClient 是可长期复用的 actor。并发初始化共享一次握手，取消单个 Swift Task 不会
取消共享握手。`closeSession()` 只清除并失效内存会话，不删除身份或 enrollment token。

默认 KeychainIdentityStore 使用 v2 service、device ID 和序号 namespace，不读取或删除
1.x 身份。升级后首次运行必须重新 enrollment。自定义 IdentityStore 返回具有 deviceID、
`publicKeySPKI()` 和 `sign(data)` 的 InstallationIdentity。

生产只允许 HTTPS。本地明文开关只接受 localhost、127/8 和 IPv6 loopback。错误统一通过
SecureError 的 code、httpStatus、traceID 和 cause 暴露；超时和取消分别映射为
`SC_REQUEST_TIMEOUT`、`SC_REQUEST_CANCELLED`。

2.0 不再支持散参数 request 或公开低层 SecureCommunicationClient。迁移说明见
[客户端 2.0 迁移指南](../../docs/客户端2.0迁移指南.md)。

```bash
swift build
swift test
```
