# ADR 0004: International product navigation / 国际化产品导航

- Status / 状态: Accepted / 已接受
- Date / 日期: 2026-07-20

## Context / 背景

The first product prototype exposed 29 primary pages. It preserved the complete capability inventory, but it also promoted runtime modes and closely related views—Agents, Workflows, MCP, Feedback, Budgets and Marketplace—to peer-level navigation. Most planned pages shared one generic prototype surface. This increased cognitive load and made the open-source baseline appear broader than its implemented lifecycle.

首版产品原型展示了 29 个一级页面，完整保留了能力清单，但也把 Agent、Workflow、MCP、Feedback、Budget、Marketplace 等运行模式或关联视图提升为并列导航。多数规划页面共用同一个通用原型，增加了认知负担，也容易让开源用户误判当前真实闭环的完成度。

## Decision / 决策

English is the canonical navigation locale. Primary navigation uses short, internationally recognizable product terms and avoids unnecessary `Hub`, `Center` and `Management` suffixes. The canonical groups are Build, Operate, Govern, Organization and System.

英文是导航规范语言。一级导航使用简短、国际通用的产品词汇，避免不必要的 `Hub`、`Center` 和 `Management` 后缀。规范分组为 Build、Operate、Govern、Organization、System。

Primary pages are:

- Overview;
- Applications, Models, Prompts, Knowledge, Tools & MCP, Evaluations, Playground;
- Releases, AI Gateway, Runs & Traces, Integrations;
- Usage & Costs, Guardrails, Audit Logs;
- Workspaces, Access Control, API Keys, Secrets;
- Organizations (system administrator only), Extensions, System Health, Settings.

Capabilities are preserved as secondary views rather than removed:

- Agents and Workflows remain Application runtime-mode views;
- Tools, MCP Servers and Memory Providers remain under Tools & MCP;
- Datasets, Evaluators, Experiments, A/B Tests, Feedback and Release Gates remain under Evaluations;
- Budgets, Forecast and Cache Savings remain under Usage & Costs;
- Members, Invitations, Roles, Policies and Identity Providers remain under Access Control;
- Marketplace and Installed remain under Extensions.

Backend terminology may retain `tenant`; user-facing navigation uses Organization and Workspace. Stable legacy routes redirect to the new parent page.

后端可以继续使用 `tenant`，用户界面统一使用 Organization 与 Workspace。旧路由通过稳定映射跳转到新的父页面。

## Alternatives / 备选方案

1. Keep all 29 pages as peers. Rejected because it optimizes for feature count and duplicates user tasks.
2. Delete deferred capabilities. Rejected because it would lose approved product scope and future compatibility.
3. Use technical aggregate names such as Capability Registry and Governance Center. Rejected because they expose implementation language rather than user tasks.

## Compatibility and migration / 兼容与迁移

- Public REST contracts, database schemas and module boundaries do not change.
- Legacy hashes remain accepted and redirect to canonical parent routes.
- Product YAML records secondary and conditional capabilities explicitly.
- English and Simplified Chinese labels change together.
- Existing live Applications, Releases and Runs pages retain their API behavior.

## Security / 安全

Navigation visibility remains a presentation concern, not authorization enforcement. API Keys and Secrets share an Organization group but retain separate page contracts, permissions and storage rules. Organizations is visible only to system administrators.

## Operability / 可运维性

The change does not add services, stateful dependencies or deployment requirements. Health, audit, run and gateway operational surfaces remain distinct.

## Open-source impact / 开源影响

The smaller, task-oriented surface makes implemented and planned behavior easier to distinguish, while reserved secondary capabilities keep the long-term platform scope visible in documentation and page contracts.

## Rollback / 回滚

Rollback restores the previous navigation grouping and removes legacy redirects. No data migration or API rollback is required.
