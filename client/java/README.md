# Secure Communication Java SDK 1.1

标准 Java 17 客户端，适用于 Host、Server、Emulator、桌面和服务进程。SDK 实现协议 v1 的
服务端身份固定、P-256 握手、HKDF-SHA256、AES-256-GCM、严格响应验证和线程安全
会话序号。Android 应继续使用独立的 Android AAR。

`pom.xml` 当前声明 `1.1.0-SNAPSHOT`。该声明只代表源码版本，不证明制品已发布到公共
Maven 仓库。

## Maven 依赖

```xml
<dependency>
  <groupId>com.coolxer.securecommunication</groupId>
  <artifactId>secure-communication-java</artifactId>
  <version>1.1.0-SNAPSHOT</version>
</dependency>
```

尚未发布时，可在本目录执行 `mvn install` 后由本机项目引用。

## 创建客户端

```java
import com.coolxer.securecommunication.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

Path identityPath = applicationDataDirectory.resolve("secure-communication/identity.json");
SecureClientConfig config = SecureClientConfig.builder()
        .baseUrl(URI.create("https://api.example.com"))
        .appId("my-java-agent")
        .deviceType("SERVER")
        .serverTrustAnchors(Map.of(
                "server-key-2026", "<P-256 SPKI Base64URL>"))
        .identityStore(new FileIdentityStore(identityPath))
        .requestTimeout(Duration.ofSeconds(15))
        .allowedClockSkew(Duration.ofMinutes(2))
        .build();

SecureClient client = SecureClients.create(config);
```

身份存储必须显式配置，SDK 不会自行选择用户目录。信任锚必须由可信构建或受签配置
分发，不能从当前待连接服务端下载后直接信任。

## 注册和请求

新安装先从宿主运行时取得短时、单次令牌：

```java
client.enroll(enrollmentToken);
client.initialize();
```

已经注册的安装直接调用 `initialize`。注册成功后令牌会从内存清除。

```java
SecureRequest request = SecureRequest.builder()
        .method("POST")
        .logicalPath("/events/upload")
        .contentType("application/json")
        .protectedHeaders(Map.of("code", "protected-business-code"))
        .body("{\"events\":[]}".getBytes(StandardCharsets.UTF_8))
        .build();

SecureResponse response = client.request(request);
System.out.println(response.getStatus() + " " + response.bodyAsUtf8());
```

空 request ID 由 SDK 生成。`request` 会按需初始化会话，但不会自动重发业务请求。

## 异步、超时和取消

```java
ExecutionOptions options = ExecutionOptions.builder()
        .timeout(Duration.ofSeconds(5))
        .build();

var future = client.requestAsync(request, options);
future.thenAccept(response -> consume(response.getBody()));

// 宿主取消时会向正在执行的 JDK HTTP future 传播取消。
future.cancel(true);
```

同步调用响应线程中断并返回 `SC_REQUEST_CANCELLED`；HTTP deadline 返回
`SC_REQUEST_TIMEOUT`。主动取消 `CompletableFuture` 时，调用方按照 Java 约定收到
`CancellationException`。多个并发初始化共享一次握手，取消一个等待者不会中止其他
调用需要的共享握手。

## 身份文件安全

`FileIdentityStore` 保存格式版本、app ID、device ID、PKCS#8 P-256 私钥和配对 SPKI 公钥，
使用同目录临时文件、落盘同步和原子移动。POSIX 环境强制目录 `0700`、身份文件和锁
文件 `0600`；支持 ACL 的非 POSIX 环境只允许 owner。符号链接、损坏文件、非 P-256
密钥、密钥对不匹配或无法建立安全权限时失败关闭。

身份文件必须位于当前应用/用户的私有数据目录，不能放在共享目录、源码仓库或容器
镜像层。更高安全要求应实现 `IdentityStore`，把 `InstallationIdentity.sign` 接到
系统 KeyStore、HSM 或 KMS，私钥无需导出给 SDK。

## HTTP(S)、TLS 和错误

`baseUrl` 可使用 HTTP 或 HTTPS，生产环境建议使用 HTTPS。默认 transport 禁止重定向；
使用 HTTPS 时限定 TLS 1.2/1.3 并执行正常证书校验。SDK 不提供 trust-all TLS。

```java
try {
    client.request(request);
} catch (SecureError error) {
    log.warn("secure request failed: code={}, status={}, traceId={}",
            error.getCode(), error.getHttpStatus(), error.getTraceId());
}
```

日志不得记录正文、注册令牌、私钥、会话材料或完整信封。收到
`SC_UNKNOWN_SESSION` 时调用 `closeSession` 后重新握手，再由业务依据幂等语义决定
是否重发。重发必须重新调用 `request`，不能缓存并原样发送旧密文。
未收到 HTTP 响应时 `SecureError.getHttpStatus()` 返回 `0`。

## 构建验证

```bash
cd client/java
mvn test
mvn package
```

测试覆盖跨语言 AES-GCM 向量、严格 JSON、身份文件、完整握手、并发初始化和并发序号。
统一公共接口语义见[客户端接口契约](../../docs/客户端接口契约.md)，线协议见
[协议 v1](../../docs/protocol/协议v1.md)。
