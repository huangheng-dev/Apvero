# P2.2b 有 Scope 的不可变持久化——验证记录

状态：实现候选；等待维护者验收

目标：P2 / P2.2b

## 已交付边界

P2.2b 只实现 ADR-0006 与维护者批准的 P2.2 实施计划所授权的持久化切片。它不启用
Embedding 执行、Index Build Runner、原子发布 API、Retrieval Lab 或 Knowledge 产品页面。

向前 V10 迁移只增加以下七张已批准表：

- `retrieval_policy_version`；
- `knowledge_index`；
- `knowledge_index_build`；
- `knowledge_index_build_revision`；
- `knowledge_index_entry`；
- `knowledge_index_version`；
- `execution_reservation_component`。

PostgreSQL 18 与 pgvector 仍是唯一强制有状态依赖。没有新增模块、Deployable、Queue、
Framework、Provider SDK 或公开 REST 契约。

## 已强制执行的不变量

- 每一行都重复 Tenant 与 Workspace Scope。
- Composite Foreign Key 保持 Base、Source、Revision、Document、Chunk、Index、Build、Route
  与 Reservation Scope。
- Build Route Reference 与复制的 Embedding Profile 必须匹配准确的不可变 Embedding Route。
- Build Revision Snapshot 要求 Active Source、READY Ingestion Result、匹配的不可变
  Revision Digest，以及版本一致的持久化 Document 与 Chunk。
- Entry 的 Dimension、Route Identity、Source Lineage、Vector Dimension 与非零 Norm 由数据库
  约束强制执行。
- Version 的 Route、Dimension、Source Count 与 Chunk Count 必须与发布 Build 完全一致。
- Retrieval Policy、Build Revision、Entry 与 Index Version 全部 Insert-only。
- Published Build 拒绝 Update/Delete，也拒绝继续插入 Entry。
- Failed 且未发布的 Build 保持持久、可检查。
- Governance Component Identity 在同一 Reservation 内唯一且不可变，只允许从 Reserved
  前进到 Dispatched，再进入 Terminal Outcome。
- 现有 P1 Reservation 回填为 `APPLICATION_RUN`；P1 Admission 路径现在也显式写入同一
  Subject Identity。

## Repository 边界

Knowledge 与 Governance 分别拥有独立的内部 Repository。每个操作的第一个参数都是
`WorkspaceScope`。Knowledge 不读写 Governance Table，两个 Repository 都不会通过公共模块
API 暴露数据库 Record。

## 迁移与回滚

V10 是 Additive、Forward-only。验证覆盖 Clean Install，以及包含现有 P1 Execution
Reservation、连续执行 V9 与 V10 的真实 V8-to-head Upgrade。回滚使用之前的兼容 Binary
并保留 V10 Row；不提供
破坏性 Down Migration 或自动 Vector 删除。

当 `APVERO_KNOWLEDGE_ENABLED=false` 时，新表不参与运行，现有 P1 与 P2.1 Runtime 行为保持
不变。

## 验证命令

```text
gradlew :modules:knowledge:test :modules:governance:test
gradlew :apps:platform-server:test --tests "*P22b*"
gradlew test :apps:platform-server:bootJar --no-daemon
```

P2.2b Integration Suite 证明：

- Clean Migration Shape、批准的 Table 数量、Composite Foreign Key、Index 与 Trigger；
- V8-to-head 状态保留、CHAT Route 回填及 P1 Reservation Subject 回填；
- 双 Tenant/双 Workspace 的 Repository 与数据库 Fail-closed 行为；
- 准确 Route/Source/Chunk/Vector Lineage 持久化；
- Vector Dimension 拒绝；
- Published Artifact 不可变与 Failed Unpublished Build 持久可检查；
- Component Idempotency、Scope Isolation、Forward Transition 与 Terminal Immutability。

## 诚实限制

P2.2b 保存了已批准的数据形状，但不宣称现在已经能通过 Live Workflow 产生 Index Version。
P2.2c 必须实现受治理的 Embedding 执行；P2.2d 必须实现 Leased Build Execution 与原子发布。
在这些切片通过前，直接数据库 Fixture 只属于验证证据，Knowledge 页面继续保持
Contract-only。
