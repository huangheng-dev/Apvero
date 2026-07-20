import { useEffect, useMemo, useState, type FormEvent } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "../lib/api";
import type { AiApplication, ReleaseBundle, RunRecord, RuntimeMode } from "../lib/types";
import { Drawer, EmptyState, ErrorPanel, Field, LoadingState, Modal, PageHeader, Panel, StatCard, StatusBadge, Toast, formatDate } from "../components/ui";

export function useApplications() {
  return useQuery({ queryKey: ["applications"], queryFn: api.applications });
}

export function useRuns() {
  return useQuery({ queryKey: ["runs"], queryFn: api.runs });
}

export function useReleaseCount(applications: AiApplication[]) {
  const queries = useQueries({ queries: applications.map((app) => ({ queryKey: ["releases", app.id], queryFn: () => api.releases(app.id), staleTime: 10_000 })) });
  return { count: queries.reduce((sum, query) => sum + (query.data?.length ?? 0), 0), error: queries.find((query) => query.error)?.error };
}

function initialRuntimeMode(): RuntimeMode | "ALL" {
  const query = window.location.hash.split("?")[1];
  const mode = query ? new URLSearchParams(query).get("mode") : null;
  return (["CHAT", "RAG", "STRUCTURED", "TOOL", "AGENTIC", "WORKFLOW"] as const).includes(mode as RuntimeMode) ? mode as RuntimeMode : "ALL";
}

export function ApplicationsPage() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const applications = useApplications();
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState<AiApplication | null>(null);
  const [toast, setToast] = useState(false);
  const [runtimeMode, setRuntimeMode] = useState<RuntimeMode | "ALL">(initialRuntimeMode);
  const [form, setForm] = useState({ slug: "", name: "", description: "", runtimeMode: "CHAT" as RuntimeMode });
  const create = useMutation({
    mutationFn: api.createApplication,
    onSuccess: async () => {
      setOpen(false);
      setToast(true);
      setForm({ slug: "", name: "", description: "", runtimeMode: "CHAT" });
      await queryClient.invalidateQueries({ queryKey: ["applications"] });
      window.setTimeout(() => setToast(false), 2600);
    },
  });
  const submit = (event: FormEvent) => { event.preventDefault(); create.mutate(form); };
  const published = applications.data?.filter((app) => app.status === "PUBLISHED").length ?? 0;
  const visibleApplications = (applications.data ?? []).filter((app) => runtimeMode === "ALL" || app.runtimeMode === runtimeMode);

  return <>
    <PageHeader title={t("nav.applications")} description={t("pages.applications")} group={t("nav.build")} mode="live" action={<button className="button primary" onClick={() => setOpen(true)}>+ {t("liveApplications.add")}</button>} />
    {applications.error && <ErrorPanel error={applications.error} />}
    <div className="stats-grid compact">
      <StatCard label={t("common.total")} value={applications.data?.length ?? "—"} delta={t("common.lastUpdated")} />
      <StatCard label={t("common.active")} value={published} delta="PUBLISHED" tone="good" />
      <StatCard label={t("common.type")} value="6" delta={t("common.runtimeModes")} />
      <StatCard label={t("common.warnings")} value="0" delta={t("common.serverConfirmed")} tone="good" />
    </div>
    <div className="tabs" role="tablist">{(["ALL", "CHAT", "RAG", "STRUCTURED", "TOOL", "AGENTIC", "WORKFLOW"] as const).map((mode) => <button key={mode} role="tab" aria-selected={runtimeMode === mode} className={runtimeMode === mode ? "active" : ""} onClick={() => setRuntimeMode(mode)}>{mode === "ALL" ? t("common.all") : mode}</button>)}</div>
    <div className="toolbar"><div className="search-control"><span>⌕</span><input placeholder={t("common.search")} /></div><select aria-label={t("common.filter")}><option>{t("common.allStatus")}</option><option>DRAFT</option><option>PUBLISHED</option></select><button className="button ghost">{t("common.export")}</button></div>
    {applications.isLoading ? <LoadingState /> : <div className="card-grid">
      {visibleApplications.map((app) => <button className="entity-card" key={app.id} onClick={() => setSelected(app)}>
        <div className="entity-top"><span className="entity-glyph">{app.runtimeMode.slice(0, 2)}</span><StatusBadge status={app.status} /></div>
        <h2>{app.name}</h2><p>{app.description}</p>
        <div className="entity-meta"><span><small>{t("liveApplications.slug")}</small><code>{app.slug}</code></span><span><small>{t("common.version")}</small><strong>v{app.version}</strong></span></div>
      </button>)}
      {!visibleApplications.length && <EmptyState>{t("liveApplications.empty")}</EmptyState>}
    </div>}
    {open && <Modal title={t("liveApplications.add")} onClose={() => setOpen(false)}><form className="modal-form" onSubmit={submit}>
      <Field label={t("liveApplications.slug")}><input required pattern="[a-z0-9]+(?:-[a-z0-9]+)*" value={form.slug} onChange={(event) => setForm({ ...form, slug: event.target.value })} placeholder="support-copilot" /></Field>
      <Field label={t("liveApplications.name")}><input required value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="Support Copilot" /></Field>
      <Field label={t("liveApplications.mode")}><select value={form.runtimeMode} onChange={(event) => setForm({ ...form, runtimeMode: event.target.value as RuntimeMode })}>{["CHAT", "RAG", "STRUCTURED", "TOOL", "AGENTIC", "WORKFLOW"].map((mode) => <option key={mode}>{mode}</option>)}</select></Field>
      <Field label={t("liveApplications.description")}><textarea value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} /></Field>
      {create.error && <ErrorPanel error={create.error} />}
      <div className="modal-actions"><button type="button" className="button ghost" onClick={() => setOpen(false)}>{t("common.cancel")}</button><button className="button primary" disabled={create.isPending}>{t("common.create")}</button></div>
    </form></Modal>}
    {selected && <Drawer title={selected.name} onClose={() => setSelected(null)}><div className="drawer-body"><StatusBadge status={selected.status} /><h3>{selected.description}</h3><dl className="detail-list"><div><dt>ID</dt><dd><code>{selected.id}</code></dd></div><div><dt>{t("liveApplications.mode")}</dt><dd>{selected.runtimeMode}</dd></div><div><dt>{t("common.updated")}</dt><dd>{formatDate(selected.updatedAt, i18n.language)}</dd></div><div><dt>{t("common.workspaceId")}</dt><dd><code>{selected.workspaceId}</code></dd></div></dl><div className="notice live"><strong>{t("dataMode.live")}</strong><p>{t("pages.applications")}</p></div></div></Drawer>}
    {toast && <Toast>{t("liveApplications.created")}</Toast>}
  </>;
}

export function ReleasesPage() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const applications = useApplications();
  const [applicationId, setApplicationId] = useState("");
  const [version, setVersion] = useState("1.0.0");
  const [selected, setSelected] = useState<ReleaseBundle | null>(null);
  const [message, setMessage] = useState("Explain the Apvero release invariant.");
  const [result, setResult] = useState<RunRecord | null>(null);
  const [toast, setToast] = useState("");
  useEffect(() => { if (!applicationId && applications.data?.[0]) setApplicationId(applications.data[0].id); }, [applicationId, applications.data]);
  const releases = useQuery({ queryKey: ["releases", applicationId], queryFn: () => api.releases(applicationId), enabled: Boolean(applicationId) });
  const create = useMutation({ mutationFn: () => api.createRelease(applicationId, version), onSuccess: async (release) => { setSelected(release); setToast(t("releases.created")); await queryClient.invalidateQueries({ queryKey: ["releases", applicationId] }); window.setTimeout(() => setToast(""), 2600); } });
  const execute = useMutation({ mutationFn: () => api.execute(applicationId, selected!.id, message), onSuccess: async (run) => { setResult(run); await queryClient.invalidateQueries({ queryKey: ["runs"] }); } });
  const error = applications.error ?? releases.error ?? create.error ?? execute.error;

  return <>
    <PageHeader title={t("nav.releases")} description={t("pages.releases")} group={t("nav.operate")} mode="live" />
    {error && <ErrorPanel error={error} />}
    <div className="release-controls"><Field label={t("releases.choose")}><select value={applicationId} onChange={(event) => { setApplicationId(event.target.value); setSelected(null); setResult(null); }}>{(applications.data ?? []).map((app) => <option value={app.id} key={app.id}>{app.name}</option>)}</select></Field><Field label={t("common.version")}><input value={version} onChange={(event) => setVersion(event.target.value)} pattern="[0-9]+\.[0-9]+\.[0-9]+(?:-[a-z0-9.-]+)?" /></Field><button className="button primary" disabled={!applicationId || create.isPending} onClick={() => create.mutate()}>+ {t("releases.add")}</button></div>
    <div className="split-view"><section className="record-list">{releases.isLoading && <LoadingState />}{(releases.data ?? []).map((release) => <button key={release.id} className={selected?.id === release.id ? "selected" : ""} onClick={() => { setSelected(release); setResult(null); }}><span><strong>v{release.version}</strong><small>{formatDate(release.createdAt, i18n.language)}</small></span><code>{release.artifactDigest.slice(0, 12)}</code><StatusBadge status={release.status} /></button>)}{!releases.isLoading && !releases.data?.length && <EmptyState>{t("releases.empty")}</EmptyState>}</section>
      <Panel title={selected ? `${t("common.release")} v${selected.version}` : t("common.select")} meta={selected?.status} className="release-detail">{selected ? <div className="detail-content"><Field label={t("releases.digest")}><code className="break-code">sha256:{selected.artifactDigest}</code></Field><Field label={t("releases.manifest")}><pre>{JSON.stringify(selected.manifest, null, 2)}</pre></Field><div className="execute-panel"><h3>{t("releases.execute")}</h3><textarea value={message} onChange={(event) => setMessage(event.target.value)} /><button className="button primary" disabled={execute.isPending} onClick={() => execute.mutate()}>{t("releases.run")}</button></div>{result && <Field label={t("releases.result")}><pre>{JSON.stringify(result.output, null, 2)}</pre></Field>}</div> : <EmptyState>{t("common.select")}</EmptyState>}</Panel>
    </div>{toast && <Toast>{toast}</Toast>}
  </>;
}

export function RunsPage() {
  const { t, i18n } = useTranslation();
  const runs = useRuns();
  const applications = useApplications();
  const [selected, setSelected] = useState<RunRecord | null>(null);
  const [query, setQuery] = useState("");
  const [toast, setToast] = useState(false);
  const names = useMemo(() => new Map((applications.data ?? []).map((app) => [app.id, app.name])), [applications.data]);
  const filtered = (runs.data ?? []).filter((run) => `${names.get(run.applicationId)} ${run.providerId} ${run.traceId}`.toLowerCase().includes(query.toLowerCase()));
  const copyTrace = async (traceId: string) => { await navigator.clipboard?.writeText(traceId); setToast(true); window.setTimeout(() => setToast(false), 2200); };

  return <>
    <PageHeader title={t("nav.runs")} description={t("pages.runs")} group={t("nav.operate")} mode="live" action={<button className="button ghost">{t("common.export")}</button>} />
    {(runs.error || applications.error) && <ErrorPanel error={runs.error ?? applications.error} />}
    <div className="toolbar"><div className="search-control"><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t("common.search")} /></div><select><option>{t("common.allStatus")}</option><option>SUCCEEDED</option><option>FAILED</option></select><span className="toolbar-note">{t("runs.latest")}</span></div>
    <div className="table-shell"><table><thead><tr><th>{t("common.name")}</th><th>{t("common.status")}</th><th>{t("runs.provider")}</th><th>{t("runs.tokens")}</th><th>{t("runs.latency")}</th><th>{t("runs.cost")}</th><th>{t("runs.trace")}</th></tr></thead><tbody>{filtered.map((run) => <tr key={run.id} onClick={() => setSelected(run)}><td><strong>{names.get(run.applicationId) ?? run.applicationId.slice(0, 8)}</strong><small>{formatDate(run.createdAt, i18n.language)}</small></td><td><StatusBadge status={run.status} /></td><td><code>{run.providerId}</code></td><td>{run.promptTokens + run.completionTokens}</td><td>{run.latencyMs} ms</td><td>${(run.costMicros / 1_000_000).toFixed(4)}</td><td><code>{run.traceId.slice(0, 10)}</code></td></tr>)}</tbody></table>{!runs.isLoading && !filtered.length && <EmptyState>{t("common.noData")}</EmptyState>}</div>
    {selected && <Drawer title={names.get(selected.applicationId) ?? selected.id} onClose={() => setSelected(null)}><div className="drawer-body"><StatusBadge status={selected.status} /><dl className="detail-list"><div><dt>{t("common.runId")}</dt><dd><code>{selected.id}</code></dd></div><div><dt>{t("common.release")}</dt><dd><code>{selected.releaseBundleId}</code></dd></div><div><dt>{t("runs.provider")}</dt><dd>{selected.providerId}</dd></div><div><dt>{t("runs.tokens")}</dt><dd>{selected.promptTokens} + {selected.completionTokens}</dd></div><div><dt>{t("runs.trace")}</dt><dd><button className="copy-code" onClick={() => copyTrace(selected.traceId)}>{selected.traceId}</button></dd></div></dl><Field label={t("runs.input")}><pre>{JSON.stringify({ input: selected.input, output: selected.output }, null, 2)}</pre></Field></div></Drawer>}
    {toast && <Toast>{t("common.copiedTrace")}</Toast>}
  </>;
}
