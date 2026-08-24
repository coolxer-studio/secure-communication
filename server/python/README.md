# Secure Communication Python Server 1.0.0（protocol v1）

本目录是协议 v1 的 Python/FastAPI 服务端实现。`secure_communication_server` 提供异步 SPI、握手和密码核心、ASGI 中间件、FastAPI 握手路由及 Redis 状态实现；`demo/app.py` 是最小宿主。

## 工程结构

| 路径 | 作用 |
| --- | --- |
| `core.py` / `types.py` | 严格 codec、信封、AAD、AES-GCM、消息服务和协议类型 |
| `handshake.py` | P-256 身份、transcript、HKDF 与 pending/active 会话流程 |
| `spi.py` / `memory.py` | 可替换 Protocol 和开发用异步内存实现 |
| `middleware.py` | FastAPI 握手路由与通用 ASGI 消息中间件 |
| `redis_store.py` | 异步 Redis 会话、注册、重放和记录加密 |
| `demo/app.py` | 默认关闭安全通信的最小 FastAPI 宿主 |

## 要求与安装

- Python 3.9 或更高版本；
- FastAPI/ASGI；
- 支持 HTTP 和 HTTPS，生产入口建议使用 TLS 1.2+；集群状态使用 Redis。

`pyproject.toml` 当前声明 `1.0.0`，但仓库本身不能证明 PyPI 制品已经发布。从源码安装：

```bash
cd server/python
python -m pip install '.[test]'
```

## 最小接入

```python
from fastapi import FastAPI
from secure_communication_server import Config, MessageService, SecureCommunicationMiddleware
from secure_communication_server.middleware import install_fastapi_routes

config = Config(enabled=True)
messages = MessageService(sessions, replay, config)
handshakes = HandshakeService(identity, sessions, installations, tokens, authorizer, config)
app = FastAPI()
install_fastapi_routes(app, handshakes, config)
app.add_middleware(SecureCommunicationMiddleware, config=config, messages=messages, routes=route_authorizer)
```

必须提供服务端 P-256 身份、握手授权、注册令牌和精确逻辑路由白名单。默认拒绝实现不会开放任何安全请求。Redis 生产实现位于 `secure_communication_server.redis_store`，使用 `redis.asyncio`；会话仓库必须配置独立 32 字节 AES-GCM 记录密钥。
官方客户端固定访问 `/sc/v1/*`。如果修改 `Config.prefix`，必须由外部路由继续暴露
`/sc/v1/message`，否则无法与官方客户端互操作；信封认证 path 始终是固定入口。

## 扩展接口

Python 使用 `typing.Protocol` 定义与 Spring/Go 对等的接口：

| Protocol | 宿主责任 |
| --- | --- |
| `ServerIdentityProvider` | 提供 `key_id`、公开 SPKI 和 transcript 签名 |
| `SessionRepository` / `KeyProvider` | 异步维护 pending/active 会话 |
| `ReplayProtector` | 异步原子认领传输序号 |
| `InstallationRegistry` | 保存和校验安装身份公钥 |
| `EnrollmentTokenService` | 签发并单次消费注册令牌 |
| `HandshakeAuthorizer` | 执行 app、设备、Origin 与业务准入 |
| `LogicalRouteAuthorizer` | 授权规范化 method/path |
| `SessionRecordProtector` | 加密共享状态中的会话密钥材料 |
| `AlgorithmProvider` / `SecurityPolicy` | 扩展套件和策略 |

现有握手和全部客户端固定为国际套件。算法 Protocol 只是消息层扩展边界，不能通过
单独注入对象启用另一套端到端 suite。

业务应用可以从 ASGI `scope["sc.transportTrust"]` 和 `scope["sc.sessionId"]` 读取
认证上下文。

## Redis 生产配置

```python
from redis.asyncio import Redis
from secure_communication_server.redis_store import (
    AESGCMRecordProtector, RedisEnrollmentTokens,
    RedisInstallationRegistry, RedisReplayProtector,
    RedisSessionRepository,
)

redis = Redis.from_url("redis://127.0.0.1:6379")
protector = AESGCMRecordProtector(record_key)  # 必须正好 32 字节
sessions = RedisSessionRepository(redis, "sc:v1:session", protector)
replay = RedisReplayProtector(redis, "sc:v1:replay")
installations = RedisInstallationRegistry(redis, "sc:v1:installation")
tokens = RedisEnrollmentTokens(redis, "sc:v1:enrollment")
```

默认配置与 Spring 完全一致，不强制 TLS；企业需要只接受 HTTPS 时设置
`require_tls=True`。对于最大 256 KiB 的业务 body，建议设置
`max_envelope_bytes=600000`、`max_plaintext_bytes=400000`、`max_body_bytes=262144`。

启用 `require_tls` 后，ASGI 中间件只信任 `scope["scheme"] == "https"`，不会读取
`X-Forwarded-Proto`。代理头解析只能由受信代理层完成。H5 CORS 应置于安全中间件之外，
并限制 Origin、POST、OPTIONS 与 Content-Type。

## Demo 与验证

```bash
uvicorn demo.app:app --host 127.0.0.1 --port 6789
python -m pytest
python -m build
```

Demo 的 `SC_ENABLED` 默认 false，不包含生产身份、授权和 Redis，行为与 Spring Boot Demo 相同。

## 相关文档

- [接入与调试指南](../../docs/接入与调试指南.md)
- [协议 v1](../../docs/protocol/协议v1.md)
- [协议错误码](../../docs/protocol/协议错误码.md)
- [兼容性策略](../../docs/兼容性策略.md)
