# ADR 0001: Modular monolith first / 优先模块化单体

- Status / 状态: Accepted / 已接受
- Date / 日期: 2026-07-19

## Decision / 决策

Apvero begins as a Spring Modulith modular monolith. PostgreSQL is the source of truth. The Python worker is separate only because document and evaluation libraries require a different runtime; it remains stateless.

Apvero 首先采用 Spring Modulith 模块化单体，PostgreSQL 是业务事实来源。Python Worker 只因为文档与评测库需要不同运行时而独立，并保持无状态。

A module can become a deployable only when measurements prove independent scaling, security isolation, runtime or failure boundaries. A new ADR must define data ownership, consistency, migration, observability, rollback and operational cost.

模块只有在指标证明其需要独立扩缩容、安全隔离、运行时或故障边界时才允许拆分；新 ADR 必须说明数据所有权、一致性、迁移、观测、回滚和运维成本。

## Consequences / 影响

- Local installation remains understandable.
- Transactions close the initial application/release/run workflow safely.
- Module boundaries are enforced by Spring Modulith and ArchUnit.
- Distributed-system complexity is paid only when justified.
