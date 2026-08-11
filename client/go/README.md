# Secure Communication Go Client 1.0

纯 Go 协议 v1 客户端，适用于 Host 和 Emulator，支持 `CGO_ENABLED=0`。SDK 提供
`New`、`Enroll`、`Initialize`、`Request` 和 `CloseSession`。项目总览见
[根 README](../../README.md)。

## 引入模块

```bash
go get github.com/coolxer/secure-communication-go@v1.0.0
```

尚未发布 tag 时，可在宿主 `go.mod` 中临时指向本地源码：

```go
replace github.com/coolxer/secure-communication-go => ../secure-communication/client/go
```

## 创建客户端

```go
package main

import (
    "context"
    "fmt"
    "path/filepath"
    "time"

    securecommunication "github.com/coolxer/secure-communication-go"
)

func send(dataDir, enrollmentToken string) error {
    client, err := securecommunication.New(securecommunication.Config{
        BaseURL:    "https://api.example.com",
        AppID:      "my-host",
        DeviceType: "HOST",
        ServerTrustAnchors: map[string]string{
            "server-key-2026": "<P-256 SPKI Base64URL>",
        },
        IdentityStore: securecommunication.FileIdentityStore{
            Path: filepath.Join(dataDir, "secure-communication", "identity.json"),
        },
    })
    if err != nil {
        return err
    }

    // 仅首次安装需要。已注册设备应传入空字符串并跳过 Enroll。
    if enrollmentToken != "" {
        if err := client.Enroll(enrollmentToken); err != nil {
            return err
        }
    }

    ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
    defer cancel()
    if err := client.Initialize(ctx); err != nil {
        return err
    }

    response, err := client.Request(
        ctx,
        "POST",
        "/events/upload",
        map[string]string{"code": "my-protected-business-code"},
        []byte(`{"events":[]}`),
        "", // 留空时由 SDK 生成新的密码学随机 request ID
    )
    if err != nil {
        return err
    }
    fmt.Printf("status=%d body=%s\n", response.Status, response.Body)
    return nil
}
```

信任锚的 key 必须匹配服务端 `kid`，value 是 P-256 SPKI Base64URL。不得从当前待
连接服务端下载公钥后直接加入信任。逻辑 path 必须已加入服务端白名单。

## 安装身份与注册令牌

首次安装使用短时、单次令牌：

```go
if err := client.Enroll(os.Getenv("SC_ENROLLMENT_TOKEN")); err != nil {
    return err
}
```

令牌只保存在内存中，注册成功后立即从宿主环境清除。不要把令牌写入配置文件、日志、
命令行参数或安装身份文件。后续握手使用持久化安装私钥，不再需要令牌。

`FileIdentityStore` 在 Unix 创建 `0700` 目录和 `0600` 文件。路径必须位于当前用户的
应用数据目录，不能放在共享目录。Windows 宿主还应为文件设置只允许当前用户访问的
ACL；更高安全要求可以实现自定义 `IdentityStore` 对接系统密钥存储。

## 本机 HTTP 调试

生产 `BaseURL` 必须是 HTTPS。只有连接本机开发服务时才设置：

```go
securecommunication.Config{
    BaseURL:                 "http://127.0.0.1:11099",
    AllowInsecureForTesting: true,
    // 其余必填字段省略
}
```

调用方必须自行保证该选项只用于 `localhost` 或 loopback 地址，不得通过生产配置
开启。正式 HTTP transport 应设置合理超时、TLS 1.2+，并关闭业务 POST 自动重试。

## 会话和重试

SDK 不自动重试 `Request`。发生网络超时或 `SC_UNKNOWN_SESSION` 时，宿主可以调用
`CloseSession` 后重新握手，再由业务层判断是否重发：

```go
client.CloseSession()
```

重试应让 request ID 留空或生成新值，使 SDK 使用新的传输 ID、seq 和密文；原业务
message ID、batch ID 和幂等字段保持不变。不要缓存并原样重发加密信封。

可以通过 `errors.As(err, &secureErr)` 读取 `*securecommunication.Error` 的 `Code`、
`Status` 和 `TraceID`，日志不得记录正文、令牌、私钥或完整信封。

## 验证和构建

```bash
go test ./...
CGO_ENABLED=0 go build ./...
```

发布 Host 时至少验证目标 Darwin、Linux、Windows 的 amd64/arm64 构建，并确认身份
文件权限、注册后重启、会话过期和服务端密钥轮换。

完整注册流程和错误排查见[接入与调试指南](../../docs/接入与调试指南.md)，
协议字段见[协议 v1 规范](../../docs/protocol/协议v1.md)。
