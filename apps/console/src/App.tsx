import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import i18n from "./i18n";
import { canView, findNavigationItem, overviewItem, resolvePageId, roles, visibleGroups, type PageId, type Role } from "./app/navigation";
import { ApplicationsPage, ReleasesPage, RunsPage } from "./pages/LivePages";
import { DemoCatalogPage, HealthPage, OverviewPage, SettingsPage } from "./pages/PrototypePages";
import { ApiKeysPage, AuditPage, LivePlaygroundPage, ModelsPage, PromptsPage, SecretsPage, UsagePage } from "./pages/ConfigurationPages";
import { applyLocale } from "./lib/locale";

function initialPage(): PageId {
  const value = window.location.hash.replace(/^#\/?/, "");
  const resolved = resolvePageId(value);
  if (resolved && value.split("?")[0] !== resolved) window.history.replaceState(null, "", `#${resolved}`);
  return resolved ?? "overview";
}

export function App() {
  const { t } = useTranslation();
  const [page, setPage] = useState<PageId>(initialPage);
  const [role, setRole] = useState<Role>(() => (localStorage.getItem("apvero.preview.role") as Role | null) ?? "SYSTEM_ADMIN");
  const [navQuery, setNavQuery] = useState("");
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [theme, setTheme] = useState(() => localStorage.getItem("apvero.theme") ?? "dark");
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());
  const groups = useMemo(() => visibleGroups(role), [role]);
  const item = findNavigationItem(page);

  useEffect(() => {
    const onHash = () => setPage(initialPage());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  useEffect(() => { document.documentElement.dataset.theme = theme; localStorage.setItem("apvero.theme", theme); }, [theme]);
  useEffect(() => {
    if (!canView(role, page)) navigate("overview");
    localStorage.setItem("apvero.preview.role", role);
  }, [role, page]);

  const navigate = (next: PageId) => {
    window.location.hash = next;
    setPage(next);
    setMobileOpen(false);
  };
  const changeLanguage = async () => {
    const next = i18n.language === "zh-CN" ? "en" : "zh-CN";
    await applyLocale(next, (locale) => i18n.changeLanguage(locale));
  };
  const toggleGroup = (group: string) => setCollapsedGroups((current) => {
    const next = new Set(current);
    if (next.has(group)) next.delete(group); else next.add(group);
    return next;
  });
  const matches = (id: PageId) => t(`nav.${id}`).toLowerCase().includes(navQuery.toLowerCase());

  return <div className={`app-shell ${collapsed ? "nav-collapsed" : ""}`}>
    {mobileOpen && <button className="mobile-scrim" aria-label={t("common.close")} onClick={() => setMobileOpen(false)} />}
    <aside className={`sidebar ${mobileOpen ? "mobile-open" : ""}`}>
      <div className="sidebar-brand-row"><button className="brand" onClick={() => navigate("overview")} aria-label={t("brand.home")}><span className="brand-mark">A</span><span className="brand-copy"><strong>Apvero</strong><small>{t("brand.subtitle")}</small></span></button><button className="collapse-button" onClick={() => setCollapsed(!collapsed)} aria-label={t("shell.collapse")}>{collapsed ? "›" : "‹"}</button></div>
      <nav className="primary-nav" aria-label={t("shell.primaryNavigation")}>
        {matches("overview") && <NavButton item={overviewItem} active={page === "overview"} collapsed={collapsed} onClick={() => navigate("overview")} />}
        {groups.map((group) => {
          const visible = group.items.filter((entry) => matches(entry.id));
          if (!visible.length) return null;
          const isCollapsed = collapsedGroups.has(group.id) && !navQuery;
          return <section className="nav-group" key={group.id}><button className="nav-group-title" onClick={() => toggleGroup(group.id)}><span>{t(`nav.${group.id}`)}</span><i>{isCollapsed ? "+" : "−"}</i></button>{!isCollapsed && visible.map((entry) => <NavButton key={entry.id} item={entry} active={page === entry.id} collapsed={collapsed} onClick={() => navigate(entry.id)} />)}</section>;
        })}
      </nav>
    </aside>
    <main className="main-surface">
      <header className="topbar"><div className="topbar-context"><button className="mobile-menu" onClick={() => setMobileOpen(true)} aria-label={t("shell.menu")}>☰</button><div className="top-search"><AppIcon name="search" /><input value={navQuery} onChange={(event) => setNavQuery(event.target.value)} placeholder={t("shell.search")} aria-label={t("shell.search")} /><kbd>⌘K</kbd></div></div><div className="top-actions"><button className="utility-button" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}><AppIcon name={theme === "dark" ? "moon" : "sun"} /><span>{t("shell.theme")}</span></button><button className="utility-button language-button" onClick={changeLanguage}><AppIcon name="language" /><span>{i18n.language === "zh-CN" ? "English" : "中文"}</span></button><details className="account-menu"><summary aria-label={t("shell.accountMenu")}><span className="avatar">AC</span><span className="account-summary"><strong>{t("shell.currentUser")}</strong><small>{t(`role.${role}`)}</small></span><AppIcon name="chevron" /></summary><div className="account-popover"><div className="account-identity"><span className="avatar large">AC</span><span><strong>{t("shell.currentUser")}</strong><small>{t(`role.${role}`)}</small></span></div><label className="role-preview"><span>{t("shell.rolePreview")}</span><select value={role} onChange={(event) => setRole(event.target.value as Role)}>{roles.map((entry) => <option value={entry} key={entry}>{t(`role.${entry}`)}</option>)}</select></label><a className="account-link" href="https://github.com/huangheng-dev/Apvero" target="_blank" rel="noreferrer"><AppIcon name="book" /><span>{t("shell.documentation")}</span></a></div></details></div></header>
      <div className="page-surface"><PageContent page={page} navigate={navigate} /></div>
      <footer className="product-footer"><span>Apvero v0.1.0-SNAPSHOT</span><span>{t(`dataMode.${item.dataMode}`)}</span><span>Apache-2.0</span></footer>
    </main>
  </div>;
}

function AppIcon({ name }: { name: "search" | "moon" | "sun" | "language" | "chevron" | "book" }) {
  const paths = {
    search: <><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></>,
    moon: <path d="M20 15.2A8.5 8.5 0 0 1 8.8 4a8.5 8.5 0 1 0 11.2 11.2Z" />,
    sun: <><circle cx="12" cy="12" r="3.5" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>,
    language: <><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.2 2.5 3.3 5.5 3.3 9s-1.1 6.5-3.3 9c-2.2-2.5-3.3-5.5-3.3-9S9.8 5.5 12 3Z" /></>,
    chevron: <path d="m8 10 4 4 4-4" />,
    book: <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v16H6.5A2.5 2.5 0 0 0 4 21.5Z" /><path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v16h4.5a2.5 2.5 0 0 1 2.5 2.5Z" /></>,
  };
  return <svg className="app-icon" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

function NavButton({ item, active, collapsed, onClick }: { item: ReturnType<typeof findNavigationItem>; active: boolean; collapsed: boolean; onClick: () => void }) {
  const { t } = useTranslation();
  return <button className={`nav-item ${active ? "active" : ""}`} onClick={onClick} title={collapsed ? t(`nav.${item.id}`) : undefined}><span className="nav-glyph">{item.glyph}</span><span className="nav-label">{t(`nav.${item.id}`)}</span>{item.badge && <small>{t(`common.${item.badge}`)}</small>}{item.dataMode === "live" && <i className="live-pin" />}</button>;
}

function PageContent({ page, navigate }: { page: PageId; navigate: (page: PageId) => void }) {
  if (page === "overview") return <OverviewPage navigate={navigate} />;
  if (page === "applications") return <ApplicationsPage />;
  if (page === "models") return <ModelsPage />;
  if (page === "prompts") return <PromptsPage />;
  if (page === "releases") return <ReleasesPage />;
  if (page === "runs") return <RunsPage />;
  if (page === "playground") return <LivePlaygroundPage />;
  if (page === "usage") return <UsagePage />;
  if (page === "audit") return <AuditPage />;
  if (page === "apiKeys") return <ApiKeysPage />;
  if (page === "secrets") return <SecretsPage />;
  if (page === "health") return <HealthPage />;
  if (page === "settings") return <SettingsPage />;
  return <DemoCatalogPage page={page} />;
}
