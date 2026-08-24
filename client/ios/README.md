# Secure Communication iOS SDK（2.x API）

Swift Package 产品名为 `SecureCommunication`，支持 iOS 13+ 和 macOS 11+。
`Package.swift` 不声明包版本，且当前仓库没有 `2.0.0` tag；源码接入时在 Xcode 中选择
`client/ios` 作为 Local Package，或在宿主 Package.swift 中使用本地路径：

```swift
.package(path: "/absolute/path/to/secure-communication/client/ios")
```

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

默认 KeychainIdentityStore 使用 v2 service、device ID 和序号 namespace，把可导出的
P-256 `rawRepresentation` 作为 Generic Password 数据存入 Keychain；它默认不使用
Secure Enclave，也不读取或删除 1.x 身份。升级后首次运行必须重新 enrollment。
自定义 IdentityStore 返回具有 deviceID、
`publicKeySPKI()` 和 `sign(data)` 的 InstallationIdentity。

`baseURL` 可使用 HTTP 或 HTTPS，生产环境建议使用 HTTPS。HTTP 地址可能需要宿主在
App Transport Security 中配置最小范围的例外；该策略不由 SDK 绕过。错误统一通过
SecureError 的 code、httpStatus、traceID 和 cause 暴露；超时和取消分别映射为
`SC_REQUEST_TIMEOUT`、`SC_REQUEST_CANCELLED`；未收到 HTTP 响应时 httpStatus 为 `0`。

2.0 不再支持散参数 request 或公开低层 SecureCommunicationClient。迁移说明见
[客户端 2.0 迁移指南](../../docs/客户端2.0迁移指南.md)。

```bash
swift build
swift test
```
