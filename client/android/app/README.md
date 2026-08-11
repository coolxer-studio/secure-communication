# Android Demo App

本模块是 Android SDK 的依赖和 UI 壳工程。它当前只展示一个提示页面，不包含服务端
地址、信任锚、注册令牌或完整的网络调用，因此不能直接上报业务数据。SDK 接入方式见
[Android README](../README.md)，产品定位见[根 README](../../../README.md)。

## 运行

准备 JDK 17 和 Android SDK，然后执行：

```bash
cd client/android
./gradlew :app:assembleDebug
```

也可以用 Android Studio 打开 `client/android`，选择 `app` configuration 后运行到
API 21+ 设备或模拟器。

当前应用的 `network_security_config.xml` 禁止明文流量，这与生产安全要求一致。

## 完成真实联调需要的改造

1. 在应用初始化层读取 `baseUrl`、appId、服务端 keyId 和公开 SPKI 信任锚。
2. 创建单例 `SecureClient`，不要为每条业务消息重复创建安装身份和会话。
3. 由宿主回调在首次安装时提供短时注册令牌，并在后台线程调用 `enroll`、
   `initialize`。
4. 将按钮或业务队列接到 `request`，把逻辑 path、受保护 header 和 JSON body 传入。
5. 切回主线程展示结果；日志只记录 request ID、错误码、HTTP 状态和 trace ID。
6. 使用可访问且证书受信的 HTTPS 开发服务，服务端允许相同 appId、设备类型和逻辑
   路由。

不要在这个 Demo 中提交真实服务端私钥、安装私钥或注册令牌。完整端到端调试顺序见
[接入与调试指南](../../../docs/接入与调试指南.md)。
