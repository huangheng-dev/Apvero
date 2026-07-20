import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { pageFixtures, type DemoRow } from "../app/catalog";
import { groupForPage, type PageId } from "../app/navigation";
import { api } from "../lib/api";
import { DataBadge, Drawer, EmptyState, ErrorPanel, Field, Modal, PageHeader, Panel, StatCard, StatusBadge, Toast, formatDate } from "../components/ui";
import { useApplications, useReleaseCount, useRuns } from "./LivePages";

function localizeDemo(value: string, chinese: boolean) {
  if (!chinese) return value;
  const exact: Record<string, string> = {
    "Today": "今天", "Yesterday": "昨天", "Now": "刚刚", "Human review": "人工审核", "Global": "全局", "Engineering": "研发", "Operations": "运维", "Procurement": "采购", "Legal": "法务", "Sales": "销售", "Finance": "财务", "Security": "安全", "Product": "产品", "Knowledge": "知识", "Integration": "集成", "Platform": "平台", "Tenant": "租户", "Production": "生产环境", "Community": "社区", "Customer Success": "客户成功", "Supply Chain": "供应链", "AI Platform": "AI 平台", "Data": "数据团队", "Manufacturing": "制造", "Migration": "迁移项目", "Compliance": "合规",
    "+3 this month": "本月新增 3", "Across this workspace": "当前工作区", "Last 15 minutes": "最近 15 分钟", "Needs review": "需要检查",
  };
  if (exact[value]) return exact[value];
  return value
    .replace(/(\d+) min ago/, "$1 分钟前")
    .replace(/(\d+) hours? ago/, "$1 小时前")
    .replace(/(\d+) days? ago/, "$1 天前")
    .replace(/(\d+) tools/, "$1 个工具")
    .replace(/(\d[\d,.]*k?) calls/, "$1 次调用")
    .replace(/(\d[\d,.]*k?) cases/, "$1 个案例")
    .replace(/(\d+) warnings?/, "$1 个警告")
    .replace(/(\d+) permissions?/, "$1 项权限")
    .replace(/(\d+) workspaces?/, "$1 个工作区");
}

export function DemoCatalogPage({ page }: { page: PageId }) {
  const { t, i18n } = useTranslation();
  const fixture = pageFixtures[page];
  const chinese = i18n.language === "zh-CN";
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("ALL");
  const [activeTab, setActiveTab] = useState(fixture?.tabs[0] ?? "overview");
  const [selected, setSelected] = useState<DemoRow | null>(null);
  const [modal, setModal] = useState(false);
  const [toast, setToast] = useState(false);
  const [localRows, setLocalRows] = useState<DemoRow[]>([]);
  const [form, setForm] = useState({ name: "", type: "CUSTOM", description: "" });
  if (!fixture) return <EmptyState>{t("common.noData")}</EmptyState>;
  const rows = [...localRows, ...fixture.rows];
  const filtered = rows.filter((item) => {
    const matchesQuery = `${item.name} ${item.type} ${item.owner} ${item.metric}`.toLowerCase().includes(query.toLowerCase());
    return matchesQuery && (status === "ALL" || item.status === status);
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    setLocalRows((current) => [
      { id: `preview-${Date.now()}`, name: form.name, type: form.type, status: "DRAFT", owner: t("shell.workspace"), metric: t("demo.added"), updated: t("common.lastUpdated"), detail: form.description || t("common.localOnly") },
      ...current,
    ]);
    setModal(false); setToast(true); setForm({ name: "", type: "CUSTOM", description: "" });
    window.setTimeout(() => setToast(false), 2600);
  };
  return <>
    <PageHeader title={t(`nav.${page}`)} description={t(`pages.${page}`)} group={t(`nav.${groupForPage(page)}`)} mode="demo" action={<><button className="button ghost">{t("common.export")}</button><button className="button primary" onClick={() => setModal(true)}>+ {t("common.create")}</button></>} />
    <div className="notice demo"><strong>{t("dataMode.demo")}</strong><p>{t("demo.backendPlanned")}</p></div>
    <div className="stats-grid compact">{fixture.stats.map((stat) => <StatCard key={stat.label} label={t(`common.${stat.label}`)} value={stat.value} delta={localizeDemo(stat.delta, chinese)} tone={stat.tone} />)}</div>
    <div className="tabs" role="tablist">{fixture.tabs.map((tab) => <button key={tab} role="tab" aria-selected={activeTab === tab} className={activeTab === tab ? "active" : ""} onClick={() => setActiveTab(tab)}>{t(`tabs.${tab}`)}</button>)}</div>
    <div className="toolbar"><div className="search-control"><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("common.search")} /></div><select value={status} onChange={(event) => setStatus(event.target.value)} aria-label={t("common.filter")}><option value="ALL">{t("common.allStatus")}</option><option>ACTIVE</option><option>HEALTHY</option><option>DRAFT</option><option>WARNING</option></select><span className="toolbar-note">{t(`tabs.${activeTab}`)} · {filtered.length}</span></div>
    <div className="table-shell"><table><thead><tr><th>{t("common.name")}</th><th>{t("common.type")}</th><th>{t("common.status")}</th><th>{t("common.owner")}</th><th>{t("common.metric")}</th><th>{t("common.updated")}</th></tr></thead><tbody>{filtered.map((item) => <tr key={item.id} onClick={() => setSelected(item)}><td><strong>{item.name}</strong><small>{item.id}</small></td><td><code>{item.type}</code></td><td><StatusBadge status={item.status} /></td><td>{localizeDemo(item.owner, chinese)}</td><td>{localizeDemo(item.metric, chinese)}</td><td>{localizeDemo(item.updated, chinese)}</td></tr>)}</tbody></table>{!filtered.length && <EmptyState>{t("demo.empty")}</EmptyState>}</div>
    {selected && <Drawer title={selected.name} onClose={() => setSelected(null)}><div className="drawer-body"><div className="drawer-status"><StatusBadge status={selected.status} /><DataBadge mode="demo" /></div><h3>{chinese ? t(`pages.${page}`) : selected.detail}</h3><dl className="detail-list"><div><dt>ID</dt><dd><code>{selected.id}</code></dd></div><div><dt>{t("common.type")}</dt><dd>{selected.type}</dd></div><div><dt>{t("common.owner")}</dt><dd>{localizeDemo(selected.owner, chinese)}</dd></div><div><dt>{t("common.metric")}</dt><dd>{localizeDemo(selected.metric, chinese)}</dd></div><div><dt>{t("common.updated")}</dt><dd>{localizeDemo(selected.updated, chinese)}</dd></div></dl><div className="notice demo"><strong>{t("demo.title")}</strong><p>{t("common.localOnly")}</p></div><button className="button primary wide-button">{t("common.configure")}</button></div></Drawer>}
    {modal && <Modal title={`${t("common.create")} ${t(`nav.${page}`)}`} onClose={() => setModal(false)}><form className="modal-form" onSubmit={submit}><div className="notice demo"><strong>{t("dataMode.demo")}</strong><p>{t("common.localOnly")}</p></div><Field label={t("common.recordName")}><input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></Field><Field label={t("common.recordType")}><input value={form.type} onChange={(event) => setForm({ ...form, type: event.target.value.toUpperCase() })} /></Field><Field label={t("common.description")}><textarea value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} /></Field><div className="modal-actions"><button type="button" className="button ghost" onClick={() => setModal(false)}>{t("common.cancel")}</button><button className="button primary">{t("common.savePreview")}</button></div></form></Modal>}
    {toast && <Toast>{t("common.previewSaved")}</Toast>}
  </>;
}

export function OverviewPage({ navigate }: { navigate: (page: PageId) => void }) {
  const { t, i18n } = useTranslation();
  const applications = useApplications();
  const runs = useRuns();
  const releases = useReleaseCount(applications.data ?? []);
  const success = runs.data?.filter((run) => run.status === "SUCCEEDED").length ?? 0;
  const successRate = runs.data?.length ? Math.round((success / runs.data.length) * 100) : 0;
  const names = new Map((applications.data ?? []).map((app) => [app.id, app.name]));
  const chart = [42, 58, 51, 68, 76, 83, 72, 91, 88, 102, 110, 118];
  return <>
    <PageHeader title={t("overview.title")} description={t("pages.overview")} group={t("nav.overview")} mode="mixed" action={<select className="range-select"><option>{t("overview.range")}</option><option>{t("overview.sevenDays")}</option><option>{t("overview.ninetyDays")}</option></select>} />
    {(applications.error || runs.error || releases.error) && <ErrorPanel error={applications.error ?? runs.error ?? releases.error} />}
    <div className="section-label"><span>{t("dataMode.live")}</span><p>{t("overview.liveSpine")}</p></div>
    <div className="stats-grid"><StatCard label={t("overview.applications")} value={applications.data?.length ?? "—"} delta={t("common.lastUpdated")} /><StatCard label={t("overview.releases")} value={releases.count} delta="SHA-256" tone="good" /><StatCard label={t("overview.runs")} value={runs.data?.length ?? "—"} delta={t("runs.latest")} /><StatCard label={t("overview.success")} value={`${successRate}%`} delta="SUCCEEDED / TOTAL" tone="good" /></div>
    <div className="section-label demo-section"><span>{t("dataMode.demo")}</span><p>{t("overview.projected")}</p></div>
    <div className="stats-grid compact"><StatCard label={t("overview.calls")} value="1.42M" delta="+18.4%" tone="good" /><StatCard label={t("overview.tokens")} value="28.4M" delta="+11.2%" /><StatCard label={t("overview.cost")} value="$4,286" delta={t("overview.budgetUsed")} tone="warn" /><StatCard label={t("overview.modelHealth")} value="9 / 10" delta={t("overview.oneWarning")} tone="warn" /></div>
    <div className="dashboard-grid"><Panel title={t("overview.costTrend")} meta={t("overview.trendMonths")} className="trend-panel"><div className="bar-chart">{chart.map((value, index) => <div key={index}><i style={{ height: `${Math.round(value / 1.3)}%` }} /><span>{index % 2 === 0 ? `W${index + 1}` : ""}</span></div>)}</div><div className="chart-legend"><span><i className="green-dot" />{t("overview.chartTokens")}</span><strong>$4,286 <small>{t("overview.projectedLabel")}</small></strong></div></Panel>
      <Panel title={t("overview.recentRuns")} meta={t("dataMode.live")}><div className="activity-list">{(runs.data ?? []).slice(0, 6).map((run) => <button key={run.id} onClick={() => navigate("runs")}><span className="activity-icon">↗</span><p><strong>{names.get(run.applicationId) ?? t("overview.fallbackApplication")}</strong><small>{run.providerId} · {run.promptTokens + run.completionTokens} {t("overview.tokenUnit")}</small></p><time>{formatDate(run.createdAt, i18n.language)}</time></button>)}{!runs.data?.length && <EmptyState>{t("common.noActivity")}</EmptyState>}</div></Panel>
      <Panel title={t("overview.alerts")} meta={t("dataMode.demo")}><div className="alert-list"><button onClick={() => navigate("models")}><StatusBadge status="WARNING" /><span><strong>Qwen3 32B</strong><small>{t("overview.modelAlert")}</small></span><b>→</b></button><button onClick={() => navigate("usage")}><StatusBadge status="WARNING" /><span><strong>{t("overview.budgetAlertTitle")}</strong><small>{t("overview.budgetAlert")}</small></span><b>→</b></button><button onClick={() => navigate("integrations")}><StatusBadge status="WARNING" /><span><strong>{t("overview.integrationAlertTitle")}</strong><small>{t("overview.integrationAlert")}</small></span><b>→</b></button></div></Panel>
      <Panel title={t("overview.governance")} meta="4 / 4"><div className="posture-list">{[["immutable", "releases"], ["scope", "workspaces"], ["providerNeutral", "models"], ["bilingual", "settings"]].map(([key, page]) => <button key={key} onClick={() => navigate(page as PageId)}><span>✓</span><p>{t(`overview.${key}`)}</p><strong>{t("overview.enforced")}</strong></button>)}</div></Panel>
    </div><div className="notice demo overview-notice"><strong>{t("dataMode.demo")}</strong><p>{t("overview.demoNotice")}</p></div>
  </>;
}

export function PlaygroundPage() {
  const { t } = useTranslation();
  const [running, setRunning] = useState(false);
  const [output, setOutput] = useState("");
  const [toast, setToast] = useState(false);
  const [input, setInput] = useState("Compare our refund policy with the customer request and cite the source.");
  const run = () => { setRunning(true); setOutput(""); window.setTimeout(() => { setOutput(t("playground.response")); setRunning(false); }, 900); };
  const save = () => { setToast(true); window.setTimeout(() => setToast(false), 2400); };
  return <>
    <PageHeader title={t("nav.playground")} description={t("pages.playground")} group={t("nav.build")} mode="demo" />
    <div className="notice demo"><strong>{t("dataMode.demo")}</strong><p>{t("common.localOnly")}</p></div>
    <div className="playground-layout"><Panel title={t("tabs.configuration")} meta={t("playground.applicationPreview")}><div className="playground-config"><Field label={t("playground.model")}><select><option>Production Chat Route · v12</option><option>CN Private Route · v4</option></select></Field><Field label={t("playground.prompt")}><select><option>Customer Support System · v18</option><option>Contract Risk Review · v7</option></select></Field><Field label={t("playground.knowledge")}><select><option>Product Documentation · index v42</option><option>Procurement Policies · index v11</option></select></Field><Field label={t("playground.tools")}><div className="check-row"><label><input type="checkbox" defaultChecked /> CRM Search</label><label><input type="checkbox" defaultChecked /> GitHub MCP</label><label><input type="checkbox" /> Email</label></div></Field><div className="dual-fields"><Field label={t("playground.temperature")}><input type="number" defaultValue="0.2" min="0" max="2" step="0.1" /></Field><Field label={t("playground.maxTokens")}><input type="number" defaultValue="1024" /></Field></div></div></Panel>
      <Panel title={t("nav.playground")} meta={running ? t("playground.running") : t("playground.ready")}><div className="playground-work"><Field label={t("playground.input")}><textarea className="prompt-input" value={input} onChange={(event) => setInput(event.target.value)} /></Field><div className="playground-actions"><button className="button primary" onClick={run} disabled={running}>{running ? t("playground.running") : t("playground.run")}</button><button className="button ghost" onClick={() => { setRunning(false); setOutput(""); }}>{t("playground.stop")}</button></div><div className={`output-box ${running ? "running" : ""}`}><div className="output-head"><span>{t("playground.output")}</span><DataBadge mode="demo" /></div>{running ? <div className="typing"><i /><i /><i /></div> : <p>{output || t("playground.ready")}</p>}</div><div className="usage-strip"><span><small>{t("playground.usage")}</small><strong>1,284 tokens · $0.0028</strong></span><span><small>{t("playground.trace")}</small><code>preview_7f2c91a4</code></span><button className="button ghost" onClick={save}>{t("playground.saveCase")}</button></div></div></Panel></div>
    {toast && <Toast>{t("playground.saved")}</Toast>}
  </>;
}

export function HealthPage() {
  const { t } = useTranslation();
  const platform = useQuery({ queryKey: ["platform"], queryFn: api.platform });
  const platformHealth = useQuery({ queryKey: ["platform-health"], queryFn: api.platformHealth });
  const workerHealth = useQuery({ queryKey: ["worker-health"], queryFn: api.workerHealth });
  const refresh = () => { void platform.refetch(); void platformHealth.refetch(); void workerHealth.refetch(); };
  const error = platform.error ?? platformHealth.error ?? workerHealth.error;
  const services: Array<[string, string, string, string]> = [
    [t("health.platform"), platformHealth.data?.status ?? "CHECKING", "Java 25 · Spring Boot 4.1", "8080"],
    [t("health.database"), platformHealth.data?.status ?? "CHECKING", "PostgreSQL 18 · pgvector", "5432"],
    [t("health.worker"), workerHealth.data?.status?.toUpperCase() ?? "CHECKING", "Python 3.14 · FastAPI", "8090"],
    [t("health.console"), "HEALTHY", "React 19 · Nginx", "3000"],
  ];
  return <>
    <PageHeader title={t("nav.health")} description={t("pages.health")} group={t("nav.system")} mode="mixed" action={<button className="button primary" onClick={refresh}>↻ {t("health.refresh")}</button>} />
    {error && <ErrorPanel error={error} />}
    <div className="section-label"><span>{t("dataMode.live")}</span><p>{t("health.liveServices")}</p></div><div className="health-grid">{services.map(([name, status, stack, port]) => <article key={name}><div><span className="service-mark">{name.slice(0, 2).toUpperCase()}</span><StatusBadge status={status === "UP" ? "HEALTHY" : status} /></div><h2>{name}</h2><p>{stack}</p><footer><code>127.0.0.1:{port}</code><span>{t("common.lastUpdated")}</span></footer></article>)}</div>
    <div className="section-label demo-section"><span>{t("dataMode.demo")}</span><p>{t("health.projectedServices")}</p></div><div className="health-grid projected"><article><div><span className="service-mark">MO</span><StatusBadge status="WARNING" /></div><h2>{t("health.providers")}</h2><p>{t("health.providerSummary")}</p><footer><code>{t("health.routeCount")}</code><span>{t("dataMode.demo")}</span></footer></article><article><div><span className="service-mark">MC</span><StatusBadge status="HEALTHY" /></div><h2>{t("health.mcpRegistry")}</h2><p>{t("health.mcpSummary")}</p><footer><code>p95 184 ms</code><span>{t("dataMode.demo")}</span></footer></article><article><div><span className="service-mark">QU</span><StatusBadge status="DRAFT" /></div><h2>{t("health.backgroundJobs")}</h2><p>{t("health.jobsSummary")}</p><footer><code>{t("health.queued")}</code><span>{t("dataMode.demo")}</span></footer></article></div>
    <Panel title={t("health.buildIdentity")} meta={t("dataMode.live")}><dl className="inline-details"><div><dt>{t("common.product")}</dt><dd>{platform.data?.name ?? "—"}</dd></div><div><dt>{t("common.version")}</dt><dd>{platform.data?.version ?? "—"}</dd></div><div><dt>{t("common.locales")}</dt><dd>{platform.data?.supportedLocales.join(", ") ?? "—"}</dd></div><div><dt>{t("common.status")}</dt><dd>{platform.data?.status ?? "—"}</dd></div></dl></Panel>
  </>;
}

export function SettingsPage() {
  const { t, i18n } = useTranslation();
  const [toast, setToast] = useState(false);
  const initialSettings = { locale: i18n.language, retention: "90", approvals: true, budgetAlerts: true, weeklyDigest: false, workflows: false };
  const [settings, setSettings] = useState(initialSettings);
  const save = (event: FormEvent) => { event.preventDefault(); setToast(true); window.setTimeout(() => setToast(false), 2400); };
  return <>
    <PageHeader title={t("nav.settings")} description={t("pages.settings")} group={t("nav.system")} mode="demo" />
    <div className="notice demo"><strong>{t("dataMode.demo")}</strong><p>{t("common.localOnly")}</p></div>
    <form className="settings-layout" onSubmit={save}><nav className="settings-nav"><button type="button" className="active">{t("settings.general")}</button><button type="button">{t("settings.notifications")}</button><button type="button">{t("settings.featureControls")}</button></nav><Panel title={t("settings.general")} meta={t("dataMode.demo")}><div className="settings-form"><Field label={t("settings.defaultLocale")}><select value={settings.locale} onChange={(event) => setSettings({ ...settings, locale: event.target.value })}><option value="en">English</option><option value="zh-CN">简体中文</option></select></Field><Field label={t("settings.retention")}><select value={settings.retention} onChange={(event) => setSettings({ ...settings, retention: event.target.value })}><option value="30">30 {t("common.days")}</option><option value="90">90 {t("common.days")}</option><option value="365">365 {t("common.days")}</option></select></Field><Toggle label={t("settings.approvals")} checked={settings.approvals} onChange={(value) => setSettings({ ...settings, approvals: value })} /><Toggle label={t("settings.budgetAlerts")} checked={settings.budgetAlerts} onChange={(value) => setSettings({ ...settings, budgetAlerts: value })} /><Toggle label={t("settings.weeklyDigest")} checked={settings.weeklyDigest} onChange={(value) => setSettings({ ...settings, weeklyDigest: value })} /><Toggle label={t("settings.workflowBeta")} checked={settings.workflows} onChange={(value) => setSettings({ ...settings, workflows: value })} /><div className="modal-actions"><button type="button" className="button ghost" onClick={() => setSettings({ ...initialSettings })}>{t("common.clear")}</button><button className="button primary">{t("common.savePreview")}</button></div></div></Panel></form>
    {toast && <Toast>{t("settings.changed")}</Toast>}
  </>;
}

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: (value: boolean) => void }) {
  const { t } = useTranslation();
  return <label className="toggle-row"><span><strong>{label}</strong><small>{t("common.previewSetting")}</small></span><button type="button" role="switch" aria-checked={checked} className={checked ? "on" : ""} onClick={() => onChange(!checked)}><i /></button></label>;
}
