# Secure Communication Spring Boot Starter 1.0

Starter 为 Servlet 应用提供协议 v1 握手、请求解密、逻辑路由授权、请求包装、响应
加密、会话仓库和重放保护。项目总览见[根 README](../../../README.md)。

## 1. 引入依赖

```xml
<dependency>
  <groupId>com.coolxer.securecommunication</groupId>
  <artifactId>secure-communication-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

使用 Starter 内置 Redis 实现时，宿主还需要：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

尚未发布到制品仓库时，可以先安装本地版本：

```bash
cd server/spring-boot/spring-boot-starter-sc
mvn clean install
```

要求 JDK 17、Spring Boot 3.2.x 和 Servlet Web 应用。

## 2. 配置协议 v1

下面的限制适合最大 256 KiB 业务 body；如果业务上限不同，应重新进行边界测试：

```yaml
spring:
  sc:
    enabled: true
    v1:
      enabled: true
      prefix: /sc/v1/message
      require-tls: false
      allowed-suites:
        - P256_HKDF_SHA256_AES256_GCM
      clock-skew: 2m
      replay-ttl: 10m
      session-ttl: 10m
      max-envelope-bytes: 600000
      max-plaintext-bytes: 400000
      max-body-bytes: 262144
```

`max-body-bytes` 限制解密后的业务 body；`max-plaintext-bytes` 还包含保护对象和
Base64URL 膨胀；`max-envelope-bytes` 再包含 AEAD 密文和外层信封。三者必须按顺序
递增。`require-tls` 默认为 `false`，HTTP 和 HTTPS 均可使用；企业需要只接受 HTTPS 时
显式设置为 `true`。生产环境仍建议使用 TLS 1.2+。

`spring.sc.enabled` 默认是 `false`。启用后，消息过滤器注册顺序为 `-200`，固定
处理配置的 `/sc/v1/message`；握手 Controller 固定处理另外两个握手入口。

## 3. 提供宿主 Bean

Starter 默认失败关闭。只设置 `spring.sc.enabled=true` 不能形成可用服务：没有
`ServerIdentityProvider` 时不会创建握手入口，默认握手授权、注册令牌和逻辑路由
也都会拒绝请求。

| Bean / SPI | 集成责任 |
| --- | --- |
| `ServerIdentityProvider` | 提供 `keyId`、公开 SPKI 并对握手 transcript 签名 |
| `SessionRepository` | 保存 pending/active 会话，同时为消息解密提供会话密钥 |
| `ReplayProtector` | 原子认领 `session + direction + seq`，拒绝重复传输 |
| `InstallationRegistry` | 保存和校验已注册安装身份公钥 |
| `EnrollmentTokenService` | 签发并单次消费 Host、Android、iOS 等注册令牌 |
| `HandshakeAuthorizer` | 校验 appId、deviceType、H5 Origin 和业务准入条件 |
| `LogicalRouteAuthorizer` | 只允许必要的 method 与规范化逻辑 path |
| `SessionRecordProtector` | 加密写入 Redis 的会话密钥材料 |

Starter 提供 P-256、AES-GCM 及 Redis 基础实现，宿主负责创建 Bean：

```java
@Bean
ServerIdentityProvider serverIdentityProvider(
        PrivateKey privateKey, PublicKey publicKey) {
    return new P256ServerIdentityProvider(
            "server-key-2026", privateKey, publicKey);
}

@Bean
SessionRecordProtector sessionRecordProtector(byte[] recordKey) {
    return new AesGcmSessionRecordProtector(recordKey); // 必须正好 32 字节
}

@Bean
SessionRepository sessionRepository(
        StringRedisTemplate redis, SessionRecordProtector protector) {
    return new RedisSessionRepository(redis, "sc:v1:session", protector);
}

@Bean
ReplayProtector replayProtector(StringRedisTemplate redis) {
    return new RedisReplayProtector(redis, "sc:v1:replay");
}

@Bean
InstallationRegistry installationRegistry(StringRedisTemplate redis) {
    return new RedisInstallationRegistry(redis, "sc:v1:installation");
}

@Bean
EnrollmentTokenService enrollmentTokenService(StringRedisTemplate redis) {
    return new RedisEnrollmentTokenService(redis, "sc:v1:enrollment");
}
```

`privateKey`、`publicKey` 和 `recordKey` 的安全加载由宿主实现。生产环境优先使用
KMS/HSM 或挂载的密钥文件；不得把私钥或会话记录密钥提交到源码、配置模板、镜像层
或日志。四类 Redis 数据必须使用独立前缀，也不能与业务幂等 key 混用。

## 4. 配置准入和逻辑路由

```java
@Bean
HandshakeAuthorizer handshakeAuthorizer() {
    Set<String> appIds = Set.of("my-web", "my-agent");
    Set<String> webOrigins = Set.of("https://console.example.com");
    return context -> {
        boolean appAllowed = appIds.contains(context.appId());
        boolean originAllowed = !"H5".equals(context.deviceType())
                || webOrigins.contains(context.origin());
        if (!appAllowed || !originAllowed) {
            throw new SecureProtocolException(SecureErrorCode.HANDSHAKE_FAILED);
        }
    };
}

@Bean
LogicalRouteAuthorizer logicalRouteAuthorizer() {
    return (method, path) ->
            ("POST".equals(method) && "/orders/query".equals(path))
            || ("POST".equals(method) && "/events/upload".equals(path));
}
```

白名单接收规范化后的 path，不包含 query。不要使用 `startsWith("/")` 或“允许所有
路径”。业务 query、字段权限和业务鉴权仍由 Controller/Service 校验。

安全过滤器会把解密结果包装成原 method、path、header 和 body，再继续 Servlet
链，因此现有 Controller 可以保持原有签名。必须通过网关路由、网络分区或应用规则
拒绝外部明文请求原 Controller；逻辑路由白名单本身不会关闭原明文入口。

## 5. 服务端身份与客户端信任锚

开发环境可以把 Base64URL 编码的 PKCS#8 私钥和 SPKI 公钥解码后创建 P-256 身份：

```java
byte[] privateDer = Base64.getUrlDecoder().decode(privateKeyBase64Url);
byte[] publicDer = Base64.getUrlDecoder().decode(publicKeyBase64Url);
KeyFactory factory = KeyFactory.getInstance("EC");
PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateDer));
PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicDer));
```

把同一公开 SPKI 及其 `keyId` 作为信任锚交付给客户端。密钥轮换期间，客户端信任包
可以同时包含新旧公钥；服务端只使用当前身份签署新握手。

## 6. H5 CORS 与过滤器顺序

浏览器会对 `/sc/v1/**` 发起预检。CORS 只开放受信 Origin、`POST`、`OPTIONS` 和
`Content-Type`，并把 CORS Filter 的顺序设置为小于 `-200`，例如 `-300`。如果
启用 Spring Security，也必须允许 `OPTIONS /sc/v1/**` 在认证前完成。

不要把业务 `code` 等受保护 header 加入外层 CORS allow-list；客户端应通过
`protectedHeaders` 将其放进密文。`Origin`、`Referer` 和 `Sec-Fetch-*` 保留在
外层，只用于浏览器准入，不应被当作密码学身份凭据。

## 7. 注册、请求和错误

H5 不使用注册令牌。其他新安装应由受管理后台调用 `EnrollmentTokenService.issue`
签发 5 至 15 分钟有效、单次使用的令牌，再通过运行时配置交给客户端。管理接口不是
Starter 自动暴露的公网 API，必须由宿主实现并置于内部网络或强管理认证之后。

安全失败使用统一错误码，例如 `SC_ENROLLMENT_REQUIRED`、`SC_UNKNOWN_SESSION`、
`SC_REPLAY_DETECTED`、`SC_REQUEST_EXPIRED` 和 `SC_KEY_PROVIDER_UNAVAILABLE`。不要向
客户端返回算法异常、Redis key、认证细节或密钥信息。

业务 POST 不由 SDK 自动重试。会话失效时重新握手，再由业务层决定是否重发；重发
必须生成新的传输 request ID 和序号，并保留原业务幂等标识。

## 8. 验证

```bash
mvn test
```

上线前至少验证：正常注册和握手、错误信任锚、密文篡改、重复及乱序 seq、过期
请求、请求大小边界、非法逻辑路由、Redis 不可用、服务端密钥轮换和原明文入口拒绝。

跨端完整配置与排错步骤见[接入与调试指南](../../../docs/接入与调试指南.md)，
协议字段见[协议 v1 规范](../../../docs/protocol/协议v1.md)。
