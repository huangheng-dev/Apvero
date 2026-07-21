# P1 治理闭环验证

状态：维护者已验收；P1 已完成，P2 成为当前交付阶段。

## 已闭合流程

`凭证 -> 固定路由 -> 调用前准入 -> Provider -> 结算 -> Run -> 用量 -> 审计 -> 留存`

- API Credential 明文只显示一次，之后只保存校验摘要。
- 工作区、应用、模型路由策略会在调用 Provider 前执行月度费用和每分钟请求限制。
- 准入后先预留估算费用，再按实际费用结算；过期未结算预留会自动收敛。
- Run 记录操作主体、路由、Release、治理预留、Trace、结果、Token、成本与延迟。
- 留存策略控制输入输出是否保存，并递归脱敏敏感字段。
- 审计记录成功/失败变更、策略拒绝和跨工作区拒绝，不保存请求正文与密钥。
- 数据库拒绝审计记录更新/删除；只有 governance 的到期清理事务可以删除过期事件。
- 模型路由就绪状态与 Micrometer 运行指标均由服务端确认，不需要计费探测。

## 验证证据

2026-07-21 已验证：

- `gradle test --no-daemon`：通过，包含空 PostgreSQL 的 V1→V7 迁移和 P1 集成闭环。
- P1 边界：未认证 `401`；跨工作区 `403`；预算与限流拒绝 `429`；拒绝时不生成预留；拒绝审计存在。
- 输入输出脱敏、操作主体归因、预留结算、路由就绪、审计不可变、受控到期清理、过期状态维护：通过。
- `pnpm typecheck`、`pnpm test`、`pnpm i18n:check`、`pnpm build`：通过；英文与简体中文各 405 个叶子键。
- AI Worker 测试与 Ruff：通过。
- OpenAPI 3.1：有效。仅保留一条既有提示：公开的平台信息接口没有人为添加无意义的 4XX 响应。
- Compose 配置：有效。

## 阶段决议

- 候选提交 `ba4f5d5` 已发布到 [PR #1](https://github.com/huangheng-dev/Apvero/pull/1)。
- [GitHub CI 运行 29812538566](https://github.com/huangheng-dev/Apvero/actions/runs/29812538566) 的五项任务全部通过：Backend、Console、Worker、Contracts 与 Compose 配置。
- 维护者已于 2026-07-21 批准 P1 验收以及 P1→P2 阶段跃迁。
- 本地 Docker Hub 授权令牌失败继续作为外部环境限制留档；项目没有因此修改既定 JDK、镜像、Dockerfile 或架构基线。
