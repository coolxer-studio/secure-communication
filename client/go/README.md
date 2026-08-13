# Secure Communication Go Client 2.0

纯 Go 协议 v1 客户端，适用于 HOST、SERVER 和 EMULATOR，支持 `CGO_ENABLED=0`。

```bash
go get github.com/coolxer/secure-communication-go/v2@v2.0.0
```

```go
package main

import (
    "context"
    "fmt"
    "path/filepath"

    securecommunication "github.com/coolxer/secure-communication-go/v2"
)

func send(dataDir, token string) error {
    client, err := securecommunication.New(securecommunication.Config{
        BaseURL:    "https://api.example.com",
        AppID:      "my-host",
        DeviceType: "HOST",
        ServerTrustAnchors: map[string]string{
            "server-key-2026": "<P-256 SPKI Base64URL>",
        },
        IdentityStore: securecommunication.FileIdentityStore{
            Path: filepath.Join(dataDir, "secure-communication", "identity-v2.json"),
        },
    })
    if err != nil { return err }
    if token != "" {
        if err := client.Enroll(token); err != nil { return err }
    }
    response, err := client.Request(context.Background(), securecommunication.Request{
        Method:           "POST",
        LogicalPath:      "/events/upload",
        ContentType:      "application/json",
        ProtectedHeaders: map[string]string{"code": "business-code"},
        Body:             []byte(`{"events":[]}`),
    })
    if err != nil { return err }
    fmt.Printf("status=%d body=%s\n", response.Status, response.Body)
    return nil
}
```

`RequestID` 留空时由 SDK 每次调用生成。超时和取消使用 `context.Context`；默认请求超时
15 秒。通过 `errors.As` 读取 `*securecommunication.Error` 的 `Code`、`HTTPStatus`、
`TraceID` 和 `Cause`。

`IdentityStore.LoadOrCreate(appID)` 返回仅暴露 `DeviceID()`、`PublicKeySPKI()` 和
`Sign(data)` 的身份。FileIdentityStore 只接受 v2 格式文件；如路径已有 1.x 文件会拒绝
覆盖，升级时必须配置新路径并重新 enrollment。

生产只允许 HTTPS。本地测试使用 `AllowInsecureLoopbackForTesting`，且仅允许 loopback。
HTTP redirect 和业务自动重试均被关闭。`SC_UNKNOWN_SESSION` 会清除会话，本次调用失败。

2.0 module path 带 `/v2`，不再提供散参数 Request。迁移说明见
[客户端 2.0 迁移指南](../../docs/客户端2.0迁移指南.md)。

```bash
go test ./...
CGO_ENABLED=0 go build ./...
```
