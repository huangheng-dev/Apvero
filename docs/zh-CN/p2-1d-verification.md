# P2.1d 安全网页采集验证

状态：已合并的实施检查点；已纳入通过验收的 P2.1 里程碑。

## 已交付边界

P2.1d 在不增加新部署单元、框架、消息队列或有状态依赖的前提下，实现已批准的公开网页 Source 路径。网页 Source 将规范化地址保存为受保护、只写的元数据，并创建一个没有 Revision 的持久化 `SNAPSHOTTING` 摄取任务。重新同步请求会创建新的持久化快照任务。

抓取由 Java Knowledge 模块负责，Python Worker 永远不会接收或解析 URL。P2.1f 负责
租约、重试、取消、重启恢复以及自动调用本处理器。

## 网络安全

- 只接受规范化的 HTTP/HTTPS URI，禁止用户信息和 Fragment。
- 国际化域名转换为 ASCII IDN 形式。
- 每次请求和每个重定向都重新执行 DNS 解析。
- DNS 返回的每个地址都必须是公网地址；公网与私网混合答案默认拒绝。
- 拒绝回环、私网、运营商级 NAT、链路本地、组播、文档、基准测试、云 Metadata、IPv6 ULA、过渡及保留地址。
- Socket 直接连接已经验证的 `InetAddress`，不经过 Proxy Selector，也不会发生第二次 DNS 解析。
- HTTPS 在连接固定 IP 的同时，继续使用规范域名执行证书端点验证与 SNI。
- 拒绝 HTTPS 降级到 HTTP 的重定向。
- 对重定向次数、响应头、响应体、连接时间和读取时间设置上限。
- 不请求压缩内容，并拒绝非 identity 的 Content Encoding。
- 只接受有界的 HTML、纯文本和 Markdown 响应。

## 持久化与同步

内容变化时，系统以 SHA-256 摘要和安全采集元数据保存不可变 Source Revision，原子更新 Source 指针，并把摄取任务推进到 `QUEUED/PARSING`，交给 P2.1e。

如果摘要与最新不可变 Revision 相同，系统不会创建重复 Revision。同步任务以 `UNCHANGED` 结果进入 `READY/COMPLETE`，并引用既有 Revision。

原始 URL 和响应正文不会进入指标或审计元数据。低基数采集指标只记录 `captured` 或 `ssrf_denied` Outcome 标签。

## 配置

| 环境变量 | 默认值 | 用途 |
|---|---:|---|
| `APVERO_KNOWLEDGE_WEB_MAX_REDIRECTS` | `5` | 允许的最大重定向跳数，每跳都重新验证 |
| `APVERO_KNOWLEDGE_WEB_MAX_HEADER_BYTES` | `65536` | 最大响应头字节数 |
| `APVERO_KNOWLEDGE_WEB_CONNECT_TIMEOUT` | `2s` | 直连 Socket 超时 |
| `APVERO_KNOWLEDGE_WEB_READ_TIMEOUT` | `5s` | TLS 握手与响应读取超时 |

响应体继续使用现有的 `APVERO_KNOWLEDGE_MAX_SNAPSHOT_BYTES` 上限。

## 验证证据

- Knowledge 与 Platform Server Java 编译。
- 单元测试覆盖规范化、IPv4、IPv6、Metadata 地址、DNS Rebinding、重定向重校验、HTTPS 降级、固定地址连接、响应大小、压缩和超时。
- PostgreSQL 集成测试覆盖网页 Source 创建、受保护规范地址持久化、快照任务创建、安全采集元数据、变化 Revision 创建和未变化 No-op 同步。
- HTTP 测试确认只写网页地址不会出现在响应中。

2026-07-23 已记录以下本地验证：

- `gradlew test`：通过，包括 Spring Modulith/ArchUnit 和 PostgreSQL Testcontainers；
- Console 严格类型检查、Vitest、必需语言覆盖与生产构建：通过；
- Worker pytest、Ruff 与依赖审计：通过（本地项目包按设计未发布到 PyPI）；
- JSON 解析与 Redocly OpenAPI 验证：通过，保留两条既有的 4XX 建议警告；
- 默认及 Knowledge Profile Compose 验证：通过；
- Platform Server 容器构建：通过；
- Diff 空白与变更文件凭据特征检查：通过。

## 回滚

设置 `APVERO_KNOWLEDGE_ENABLED=false` 即可默认拒绝并停用 Knowledge。P2.1d 没有新增数据库迁移，因此回滚应用提交即可移除网页命令与采集处理器。既有网页 Source 和 Job 仍符合已批准的 V8 Schema，并会在 Knowledge 关闭时保持惰性。
