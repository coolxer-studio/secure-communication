# Secure Communication Spring Boot 接入

本目录提供协议 v1 的 Spring Boot 服务端实现。SDK 的整体定位、协议能力和各端
交付物见[项目主页](../../README.md)。本文只说明 Spring Boot 侧如何选择工程并开始
集成。

## 目录

| 工程 | 用途 | 文档 |
| --- | --- | --- |
| `spring-boot-starter-sc` | 正式 Starter，提供握手、解密转发、响应加密、会话和重放保护扩展点 | [Starter 接入](spring-boot-starter-sc/README.MD) |
| `SpringBootDemo` | 最小 Spring Boot 壳工程，用于验证依赖和启动方式 | [Demo 说明](SpringBootDemo/README.md) |

`SpringBootDemo` 默认没有配置生产级身份、授权和 Redis Bean，不是开箱即用的
端到端服务。真实业务应在自己的 Spring Boot 应用中引入 Starter，并参考完整接入
指南补齐宿主能力。

## 环境要求

- JDK 17；
- Spring Boot 3.2.x；
- Servlet Web 应用；
- 生产或集群环境使用 Redis 保存会话、重放、安装身份和一次性注册令牌；
- 支持 HTTP 和 HTTPS，生产入口建议启用 TLS 1.2 或更高版本。

## 推荐接入顺序

1. 在宿主应用中引入 `secure-communication-spring-boot-starter:1.1.0-SNAPSHOT`。
2. 生成或加载 P-256 服务端身份密钥，并确定稳定的 `serverKeyId`。
3. 配置 `spring.sc.*` 属性和独立的 Redis key 前缀。
4. 提供身份、握手授权、安装注册、会话、重放和逻辑路由授权 Bean。
5. 为 H5 配置 `/sc/v1/**` CORS，并保证预检过滤器早于安全消息过滤器。
6. 将服务端公开 SPKI 信任锚通过可信渠道交付给客户端。
7. 验证握手、注册、篡改、重放、过期、请求大小和密钥轮换场景。

协议入口固定为：

- `POST /sc/v1/handshake`
- `POST /sc/v1/handshake/finish`
- `POST /sc/v1/message`

解密后的逻辑请求可以继续进入现有 Controller。必须同时通过网关、网络边界或应用
安全规则阻止外部调用原明文 Controller，避免绕过安全入口。

## 构建

```bash
cd spring-boot-starter-sc
mvn test
mvn clean install
```

本地安装后，其他 Maven 工程即可引用 `1.1.0-SNAPSHOT`。完整的 Bean 示例、密钥生成、CORS、
注册令牌和故障排查见[接入与调试指南](../../docs/接入与调试指南.md)。

协议字段与统一错误语义见[协议 v1](../../docs/protocol/协议v1.md)和
[协议错误码](../../docs/protocol/协议错误码.md)。其他服务端框架见
[Go Server SDK](../go/README.md)与[Python Server SDK](../python/README.md)。
