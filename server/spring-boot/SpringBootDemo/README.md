# Spring Boot Demo

这是 Secure Communication Starter 的最小 Spring Boot 壳工程，用于验证 JDK、Maven、
Starter 依赖和普通 Controller 能否正常启动。SDK 总览见[根 README](../../../README.md)，
服务端接入顺序见[Spring Boot README](../README.md)。

## 当前能力边界

Demo 默认配置：

```properties
server.port=6789
spring.sc.enabled=${SC_ENABLED:false}
spring.sc.legacy.enabled=false
```

因此默认启动时安全通信是关闭的。工程当前没有提供生产级
`ServerIdentityProvider`、`HandshakeAuthorizer`、Redis 会话、注册令牌或逻辑路由
白名单，不能只把 `SC_ENABLED` 改为 `true` 就作为完整端到端服务使用。

## 运行基础 Demo

```bash
cd server/spring-boot/SpringBootDemo
mvn spring-boot:run
```

应用监听 `http://127.0.0.1:6789`。这一步只验证 Spring Boot 工程和普通 Controller，
不代表 `/sc/v1/**` 已可握手。

运行测试：

```bash
mvn test
```

## 改造成安全通信示例

1. 按 [Starter 接入文档](../spring-boot-starter-sc/README.MD)配置 `spring.sc.*`。
2. 加载 P-256 服务端身份并注册 `ServerIdentityProvider`。
3. 为开发环境提供明确的 appId、H5 Origin 或设备类型准入规则。
4. 提供会话、重放、安装身份和注册令牌 Bean；生产及集群环境使用 Redis。
5. 只允许 Demo 实际存在的逻辑 Controller 路由。
6. 为 H5 配置早于安全消息 Filter 的 CORS Filter。
7. 把公开 SPKI 信任锚交给对应客户端，再执行握手和消息请求。

生产环境必须启用 HTTPS，服务端私钥、会话记录密钥和注册令牌不得写入本 Demo 的
`application.properties`。完整联调流程见[接入与调试指南](../../../docs/接入与调试指南.md)。
