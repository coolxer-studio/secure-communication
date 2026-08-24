# Secure Communication Android SDK 2.0

Android API 21+ 客户端，线协议保持 v1，并按系统版本使用原生 EC Keystore 或
Keystore 包装的软件 P-256 身份。

Gradle 当前声明版本为 `2.0.0`。仓库本身不能证明 Maven 制品已经发布；从源码接入时
先发布到本机 Maven 仓库：

```bash
cd client/android
./gradlew :secure-communication:publishToMavenLocal
```

消费项目启用 `mavenLocal()` 后再添加依赖：

```groovy
dependencies {
    implementation 'com.coolxer.securecommunication:secure-communication:2.0.0'
}
```

```java
SecureClientConfig config = SecureClientConfig.builder()
        .baseUrl("https://api.example.com")
        .appId("my-android-agent")
        .deviceType("ANDROID")
        .serverTrustAnchors(Collections.singletonMap(
                "server-key-2026", "<P-256 SPKI Base64URL>"))
        .build();
SecureClient client = new SecureClient(applicationContext, config);

// 仅首次安装需要；令牌由受信运行时提供，不能写入源码。
client.enroll(enrollmentToken);

SecureRequest request = new SecureRequest(
        "POST",
        "/events/upload",
        "application/json",
        Collections.singletonMap("code", "business-code"),
        "{\"events\":[]}".getBytes(StandardCharsets.UTF_8),
        null);

client.newCall(request).enqueue(new SecureCall.Callback() {
    @Override public void onResponse(SecureResponse response) {
        String body = response.bodyAsUtf8();
    }
    @Override public void onFailure(SecureError error) {
        String code = error.getCode();
    }
});
```

同步 `initialize()` 和 `request(SecureRequest)` 禁止在主线程调用。`SecureCall` 支持
`execute`、`enqueue` 和 `cancel`，并覆盖自动初始化与业务请求的完整生命周期。取消一个
等待共享握手的 SecureCall 不会取消其他调用的握手。

默认 IdentityStore 使用 v2 Keystore alias 和 `secure-communication-v2` preferences。
API 23+ 直接使用不可导出的 EC Keystore 私钥；API 21–22 生成软件 P-256 私钥，并由
不可导出的 Keystore RSA 私钥包装后保存到应用私有 preferences。不读取或删除 1.x
身份，升级后首次运行必须重新 enrollment。也可注入实现
`IdentityStore.loadOrCreate(appId)` 的自定义硬件身份存储。

`baseUrl` 可使用 HTTP 或 HTTPS，生产环境建议使用 HTTPS。Android 9 及更高版本可能
需要宿主通过 Network Security Configuration 显式允许目标域名的明文流量；该策略不由
SDK 绕过。SDK 不重试业务请求，统一错误字段为 code、httpStatus、traceId 和 cause；
未收到 HTTP 响应时 httpStatus 为 `0`。

2.0 删除 `SecureClient.Config`、散参数 request 和公开低层客户端入口。迁移说明见
[客户端 2.0 迁移指南](../../docs/客户端2.0迁移指南.md)。

```bash
./gradlew :secure-communication:testDebugUnitTest :secure-communication:assembleRelease
```
