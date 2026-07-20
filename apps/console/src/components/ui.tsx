import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { DataMode } from "../app/navigation";

export function PageHeader({ title, description, group, mode, action }: { title: string; description: string; group: string; mode: DataMode; action?: ReactNode }) {
  const { t } = useTranslation();
  return <header className="page-header">
    <div>
      <div className="page-kicker"><span>{group}</span><DataBadge mode={mode} /></div>
      <h1>{title}</h1>
      <p>{description}</p>
    </div>
    {action && <div className="page-actions">{action}</div>}
  </header>;
}

export function DataBadge({ mode }: { mode: DataMode }) {
  const { t } = useTranslation();
  return <span className={`data-badge ${mode}`}><i />{t(`dataMode.${mode}`)}</span>;
}

export function StatCard({ label, value, delta, tone }: { label: string; value: ReactNode; delta: string; tone?: "good" | "warn" }) {
  return <article className={`stat-card ${tone ?? ""}`}><span>{label}</span><strong>{value}</strong><small>{delta}</small></article>;
}

export function Panel({ title, meta, children, className = "" }: { title: string; meta?: string; children: ReactNode; className?: string }) {
  return <section className={`panel ${className}`}><div className="panel-head"><h2>{title}</h2>{meta && <span>{meta}</span>}</div>{children}</section>;
}

export function StatusBadge({ status }: { status: string }) {
  const tone = ["HEALTHY", "ACTIVE", "CONNECTED", "PUBLISHED", "SUCCEEDED", "READY"].includes(status) ? "positive" : status === "WARNING" ? "warning" : status === "BLOCKED" || status === "FAILED" ? "negative" : "neutral";
  return <span className={`status-badge ${tone}`}><i />{status}</span>;
}

export function EmptyState({ children }: { children: ReactNode }) { return <div className="empty-state"><span>◇</span><p>{children}</p></div>; }
export function LoadingState() { const { t } = useTranslation(); return <div className="loading-state"><i /><span>{t("common.loading")}</span></div>; }

export function ErrorPanel({ error }: { error: unknown }) {
  const { t } = useTranslation();
  const message = error instanceof Error ? error.message : String(error);
  return <div className="error-panel"><strong>{t("error.title")}</strong><p>{message}</p><small>{t("error.hint")}</small></div>;
}

export function Modal({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const { t } = useTranslation();
  return <div className="overlay" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="modal" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal-head"><h2>{title}</h2><button aria-label={t("common.close")} onClick={onClose}>×</button></div>
      {children}
    </section>
  </div>;
}

export function Drawer({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const { t } = useTranslation();
  return <div className="drawer-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <aside className="drawer" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal-head"><h2>{title}</h2><button aria-label={t("common.close")} onClick={onClose}>×</button></div>
      {children}
    </aside>
  </div>;
}

export function Toast({ children }: { children: ReactNode }) { return <div className="toast" role="status"><span>✓</span>{children}</div>; }

export function Field({ label, children }: { label: string; children: ReactNode }) { return <div className="field"><label>{label}</label>{children}</div>; }

export function formatDate(value: string, locale: string) {
  return new Intl.DateTimeFormat(locale, { month: "short", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
