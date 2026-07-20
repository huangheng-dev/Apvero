# ADR 0002: Immutable ReleaseBundle / 不可变发布包

- Status / 状态: Accepted / 已接受
- Date / 日期: 2026-07-19

## Decision / 决策

Production runs reference an immutable `ReleaseBundle`. The manifest pins every runtime dependency, is canonicalized and receives a SHA-256 digest. PostgreSQL rejects update and delete operations on release rows.

生产运行必须引用不可变 `ReleaseBundle`。Manifest 固定所有运行依赖，规范化后生成 SHA-256；PostgreSQL 拒绝更新和删除发布记录。

Promotion and rollback move an environment pointer between release IDs. They never mutate a release. A correction creates a new version and digest.

晋级与回滚只切换环境指针，不修改 Release；修正必须创建新版本和新摘要。

## Consequences / 影响

- Runs remain reproducible and auditable.
- Rollbacks are fast and explicit.
- Storage grows append-only; retention requires an auditable archival design.
