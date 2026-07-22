# 快速开始

## 前置条件

- Docker 29 或更高版本
- Docker Compose v2
- 至少 4 核 CPU 与 6 GB 可用内存

默认自托管模式不要求本机安装 Java、Node.js 或 Python。

## 启动

在仓库根目录执行：

```bash
Copy-Item .env.example .env
docker compose -f deploy/compose/compose.yaml up --build
```

访问 <http://localhost:3000>。数据库会写入本地 Tenant、默认 Workspace、3 个应用、3 个不可变发布包和 12 条确定性运行记录，因此每个页面都有可以检查的数据。

所有映射端口默认只绑定 `127.0.0.1`。开发模式会为本机使用提供本地管理员身份。对外开放任何端口前，必须设置 `APVERO_SECURITY_MODE=enforced`，提供高熵 `APVERO_BOOTSTRAP_ADMIN_TOKEN`，并通过可信 TLS 反向代理暴露服务。

## 完成第一条闭环

1. 打开“应用”，创建一个 AI Application。
2. 打开“Playground”，绑定预置的确定性路由与 Prompt，并执行预览。
3. 打开“发布”，选择该应用并创建 `1.0.0`。
4. 选择该 Release，输入消息并执行。
5. 打开“运行记录”或“用量与成本”，检查 Provider、Token、延迟、成本与 Trace ID。
6. 在“用量与成本”创建月度费用与每分钟请求预算；策略会在调用 Provider 前执行。
7. 打开“审计日志”，检查带操作主体的变更记录，并配置输入输出留存与脱敏。
8. 打开“系统健康”，查看服务端确认的模型路由就绪状态，不会产生付费探测。

这条链路使用 `local-deterministic@1.0.0`，不需要模型密钥，也绝不会伪装成外部模型结果。

## 服务地址

| 服务 | 地址 |
|---|---|
| 控制台 | <http://localhost:3000> |
| 平台信息 | <http://localhost:8080/api/v1/platform> |
| 平台健康检查 | <http://localhost:8080/actuator/health> |

默认 Profile 有意不启动 AI Worker。它没有宿主机端口、Console 代理或公开 OpenAPI 页面。P2 实施验证可以使用 `docker compose --profile knowledge ...` 启动它；此时也只有 Platform Server 能通过 `knowledge-internal` 访问其健康检查和内部契约。

如果从曾经发布 `8090` 端口的旧版本升级，第一次使用新配置前先停止旧容器，避免旧端口映射因 Profile 未启用而继续存在：

```bash
docker compose --profile knowledge -f deploy/compose/compose.yaml stop ai-worker
```

明确需要 Knowledge 验证时，执行 `docker compose --profile knowledge -f deploy/compose/compose.yaml up -d --build ai-worker`，它会按仅私有网络的新配置重建 Worker。

## 停止与清空

停止服务但保留数据：

```bash
docker compose -f deploy/compose/compose.yaml down
```

只有在明确需要恢复初始数据时，才删除 Compose 数据卷：

```bash
docker compose -f deploy/compose/compose.yaml down --volumes
```

## 启用真实 Provider

真实网络 Provider 默认关闭。设置 `APVERO_REAL_PROVIDER_ENABLED=true`，通过环境变量（例如 `OPENAI_API_KEY`）提供密钥，然后在控制台依次创建 Secret Reference、Provider、模型、路由与 Prompt Version。把版本化路由和 Prompt 绑定到 Application，先通过预览验证，再正式发布。私有地址或回环地址默认禁止；只有在明确采用本地模型部署时，才应设置 `APVERO_ALLOW_PRIVATE_MODEL_ENDPOINTS=true`。

## 安全提醒

开发模式仅用于本地工程，不得暴露到不可信网络。Enforced 模式接受 Bootstrap 管理员 Token 或经过哈希存储、带作用域的 API Credential。Secret 值由进程环境提供，数据库和 API 只保存引用。
