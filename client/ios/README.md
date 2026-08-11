# Secure Communication iOS SDK 1.0

正式交付物为 Swift Package `SecureCommunication`。入口 `SecureClient` 完成服务端
身份固定、P-256 握手、HKDF-SHA256、AES-256-GCM 请求/响应保护和会话序号管理；
安装身份保存在 Keychain。项目总览见[根 README](../../README.md)。

## 引入 Swift Package

在 Xcode 中选择 **File → Add Package Dependencies**，使用正式发布仓库和固定的
`1.0.0` tag。源码联调也可以添加本地目录 `client/ios`。

在另一个 Swift Package 中使用本地依赖：

```swift
dependencies: [
    .package(path: "../secure-communication/client/ios")
],
targets: [
    .target(
        name: "MyAgent",
        dependencies: [
            .product(name: "SecureCommunication", package: "ios")
        ]
    )
]
```

如果宿主使用远程仓库，`package` 标识应以实际仓库包身份为准。当前 Package 支持
iOS 13+ 和 macOS 11+，需要 Swift 5.9 兼容工具链。

## 创建和调用客户端

```swift
import Foundation
import SecureCommunication

let serverSPKI: Data = loadPinnedServerSPKI()
let client = try SecureClient(config: SecureClientConfig(
    baseURL: URL(string: "https://api.example.com")!,
    appID: "my-ios-agent",
    serverTrustAnchors: ["server-key-2026": serverSPKI]
))

// 新安装先执行 try await client.enroll(token)，已注册安装直接 initialize。
try await client.initialize()

let response = try await client.request(
    "POST",
    logicalPath: "/events/upload",
    protectedHeaders: ["code": "my-protected-business-code"],
    body: Data(#"{"events":[]}"#.utf8),
    requestID: UUID().uuidString.lowercased()
)

let body = try response.text()
```

`serverSPKI` 是 Base64URL 信任锚解码后的 SPKI DER，不是 PEM 文本。字典 key 必须
匹配服务端 `kid`。信任锚应随受信构建或远程受签配置分发，不能从当前待连接服务端
下载后直接信任。逻辑 path 必须加入服务端白名单。

## 首次注册和 Keychain

宿主应用通过运行时回调取得短时、单次令牌：

```swift
try await client.enroll(enrollmentToken)
try await client.initialize()
```

注册成功后 SDK 清除内存令牌。安装私钥由 `InstallationIdentityStore` 保存到 Keychain，
使用 `AfterFirstUnlockThisDeviceOnly`，不会随备份迁移到其他设备。不要把令牌、私钥
或会话材料写入 Info.plist、UserDefaults、应用包、日志或崩溃报告。

当前 1.0 默认使用 Keychain 保存 CryptoKit P-256 私钥材料，并未承诺 Secure Enclave
实现。需要 Secure Enclave 时应作为经过兼容性和迁移设计的后续身份存储实现接入。

## TLS、本地调试和生命周期

客户端只接受 `https://` base URL，默认使用 ephemeral `URLSession`、最低 TLS 1.2，
不会自动重试业务请求。开发服务应使用设备信任的证书，不得加入 trust-all delegate
或 ATS 全局放行。

`SecureClient` 是 actor，可以由应用持有为长生命周期实例。关闭当前会话：

```swift
await client.closeSession()
```

关闭不删除 Keychain 安装身份。发生 `SecureError.unknownSession` 时重新握手，再由
业务层决定是否重发。每次重试使用新的 request ID 和密文，保留原业务 message ID、
batch ID 和幂等字段。

## 错误和验证

`SecureError` 区分配置、信封、版本、套件、会话、过期、重放、路由、认证和 transport
错误。日志可以记录错误分类、HTTP 状态和 trace ID，不记录正文、令牌、私钥或完整
信封。

```bash
cd client/ios
swift test
```

本机验证需要完整且与 SDK 匹配的 Xcode toolchain。正式发布前验证注册后重启、
Keychain 状态、会话过期、错误信任锚、网络超时和服务端密钥轮换。

完整注册、服务端配置和错误排查见
[接入与调试指南](../../docs/接入与调试指南.md)，协议字段见
[协议 v1 规范](../../docs/protocol/协议v1.md)。
