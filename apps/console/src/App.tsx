import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import i18n from "./i18n";
import { canView, findNavigationItem, groupForPage, overviewItem, resolvePageId, roles, visibleGroups, type PageId, type Role } from "./app/navigation";
import { ApplicationsPage, ReleasesPage, RunsPage } from "./pages/LivePages";
import { DemoCatalogPage, HealthPage, OverviewPage, SettingsPage } from "./pages/PrototypePages";
import { ApiKeysPage, LivePlaygroundPage, ModelsPage, PromptsPage, SecretsPage, UsagePage } from "./pages/ConfigurationPages";

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
    localStorage.setItem("apvero.locale", next);
    await i18n.changeLanguage(next);
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
      <div className="workspace-switcher"><span className="workspace-mark">NS</span><div><strong>{t("shell.tenant")}</strong><small>{t("shell.workspace")}</small></div><b>⌄</b></div>
      <div className="nav-search"><span>⌕</span><input value={navQuery} onChange={(event) => setNavQuery(event.target.value)} placeholder={t("shell.search")} aria-label={t("shell.search")} /><kbd>⌘K</kbd></div>
      <nav className="primary-nav" aria-label={t("shell.primaryNavigation")}>
        {matches("overview") && <NavButton item={overviewItem} active={page === "overview"} collapsed={collapsed} onClick={() => navigate("overview")} />}
        {groups.map((group) => {
          const visible = group.items.filter((entry) => matches(entry.id));
          if (!visible.length) return null;
          const isCollapsed = collapsedGroups.has(group.id) && !navQuery;
          return <section className="nav-group" key={group.id}><button className="nav-group-title" onClick={() => toggleGroup(group.id)}><span>{t(`nav.${group.id}`)}</span><i>{isCollapsed ? "+" : "−"}</i></button>{!isCollapsed && visible.map((entry) => <NavButton key={entry.id} item={entry} active={page === entry.id} collapsed={collapsed} onClick={() => navigate(entry.id)} />)}</section>;
        })}
      </nav>
      <footer className="sidebar-footer"><a href="https://github.com/huangheng-dev/Apvero" target="_blank" rel="noreferrer"><span className="footer-glyph">?</span><span>{t("shell.documentation")}</span></a><button onClick={() => setTheme(theme === "dark" ? "light" : "dark")}><span className="footer-glyph">◐</span><span>{t("shell.theme")}</span></button><div className="profile"><span className="avatar">AC</span><span><strong>{t("shell.currentUser")}</strong><small>{t(`role.${role}`)}</small></span><b>•••</b></div></footer>
    </aside>
    <main className="main-surface">
      <header className="topbar"><div className="breadcrumb"><button className="mobile-menu" onClick={() => setMobileOpen(true)} aria-label={t("shell.menu")}>☰</button><span>{t(`nav.${groupForPage(page)}`)}</span><i>/</i><strong>{t(`nav.${page}`)}</strong></div><div className="top-actions"><select className="environment-select" aria-label={t("shell.environment")}><option>{t("shell.environment")}</option></select><label className="role-preview"><span>{t("shell.rolePreview")}</span><select value={role} onChange={(event) => setRole(event.target.value as Role)}>{roles.map((entry) => <option value={entry} key={entry}>{t(`role.${entry}`)}</option>)}</select></label><button className="icon-button notification" aria-label={t("shell.notifications")}>◇<i /></button><button className="language-button" onClick={changeLanguage}>{i18n.language === "zh-CN" ? "EN" : "中文"}</button></div></header>
      <div className="page-surface"><PageContent page={page} navigate={navigate} /></div>
      <footer className="product-footer"><span>Apvero v0.1.0-SNAPSHOT</span><span>{t(`dataMode.${item.dataMode}`)}</span><span>Apache-2.0</span></footer>
    </main>
  </div>;
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
  if (page === "apiKeys") return <ApiKeysPage />;
  if (page === "secrets") return <SecretsPage />;
  if (page === "health") return <HealthPage />;
  if (page === "settings") return <SettingsPage />;
  return <DemoCatalogPage page={page} />;
}
