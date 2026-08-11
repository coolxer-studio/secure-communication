# Secure Communication Android SDK 1.0

Android SDK 的正式入口是 `SecureClient`。它完成服务端身份固定、P-256 握手、
HKDF-SHA256、AES-256-GCM 请求/响应保护和会话序号管理；安装私钥保存在 Android
Keystore。项目总览见[根 README](../../README.md)。

## 集成依赖

发布到 Maven 仓库后，在应用模块中引入：

```groovy
dependencies {
    implementation 'com.coolxer.securecommunication:secure-communication-android:1.0.0'
}
```

源码联调可以像本仓库一样把 `secure-communication` 作为 Gradle module 引入：

```groovy
dependencies {
    implementation project(':secure-communication')
}
```

构建本地 AAR：

```bash
cd client/android
./gradlew :secure-communication:assembleRelease
```

SDK 要求 Android API 21+、Java 8 字节码和 `INTERNET` 权限，HTTP transport 使用
OkHttp 4.12.0。生产应用应保持 `android:usesCleartextTraffic="false"`。

## 创建和调用客户端

```java
import com.coolxer.securecommunication.SecureClient;
import com.coolxer.securecommunication.SecureResponse;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

ExecutorService secureExecutor = Executors.newSingleThreadExecutor();

SecureClient client = new SecureClient(
        applicationContext,
        new SecureClient.Config(
                "https://api.example.com",
                "my-android-agent",
                Collections.singletonMap(
                        "server-key-2026",
                        "<P-256 SPKI Base64URL>")));

secureExecutor.execute(() -> {
    try {
        // 新安装先执行 client.enroll(token)，已注册安装直接 initialize。
        client.initialize();
        SecureResponse response = client.request(
                "POST",
                "/events/upload",
                Collections.singletonMap("code", "my-protected-business-code"),
                "{\"events\":[]}".getBytes(StandardCharsets.UTF_8),
                UUID.randomUUID().toString());
        String body = response.bodyAsUtf8();
        // 切回主线程更新 UI。
    } catch (Exception error) {
        // 只记录安全错误分类，不记录正文、令牌或完整信封。
    }
});
```

`initialize` 和 `request` 当前执行同步网络 I/O，禁止在 Android 主线程调用。可以使用
业务已有的 Executor、协程 IO dispatcher 或其他后台任务框架封装。

信任锚的 key 必须匹配服务端 `kid`，value 是 P-256 SPKI Base64URL。逻辑 path 必须
加入服务端白名单。`code` 等 header 通过 `protectedHeaders` 加密，不要放到外层
OkHttp header。

## 首次注册和身份存储

首次安装必须从宿主运行时回调取得短时、单次令牌：

```java
client.enroll(enrollmentToken);
client.initialize();
```

注册成功后 SDK 清除内存令牌，后续握手通过 Android Keystore 中的安装私钥证明
身份。不得把令牌或私钥写入 APK/AAB、BuildConfig、资源文件、SharedPreferences、
日志或崩溃报告。卸载应用或清除相关 Keystore 身份后，应按新安装重新注册。

## TLS 和本地调试

Android `SecureClient` 强制 `https://`，OkHttp 最低 TLS 1.2，并关闭连接失败自动重试。
本机开发服务应配置受信开发证书；正式构建不得启用 trust-all、忽略主机名校验或
明文流量。

模拟器访问开发机时通常使用 `10.0.2.2`，但仍应为该地址准备受信 HTTPS 配置。
如果业务确需临时验证明文服务，应由独立 debug-only 应用适配层完成，不能修改或
放宽正式 SDK 和 release manifest。

## 会话、错误和重试

```java
client.closeSession();
```

关闭只清理内存会话，不删除 Keystore 安装身份。`SecureError` 提供 `getCode()`、
`getHttpStatus()` 和 `getTraceId()`。收到 `SC_UNKNOWN_SESSION` 时先关闭并重新握手，
再由业务层决定是否重发。

SDK 不自动重试业务 POST。每次重试生成新的 request ID 和密文，同时保留原业务
message ID、batch ID 与幂等字段；不要缓存后原样重发加密信封。

## 验证

```bash
cd client/android
./gradlew :secure-communication:test :secure-communication:assembleRelease
```

正式发布前验证注册后重启、会话过期、错误信任锚、网络超时、服务端密钥轮换和
API 21+ 目标设备。Android 示例工程见 [Demo README](app/README.md)。

完整注册、服务端配置和错误排查见
[接入与调试指南](../../docs/接入与调试指南.md)，协议字段见
[协议 v1 规范](../../docs/protocol/协议v1.md)。
