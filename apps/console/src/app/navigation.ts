export type Role = "SYSTEM_ADMIN" | "TENANT_ADMIN" | "AI_ADMIN" | "DEVELOPER" | "OPERATOR" | "MEMBER";

export type PageId =
  | "overview" | "applications" | "models" | "prompts" | "knowledge" | "capabilities" | "evaluations" | "playground"
  | "releases" | "gateway" | "runs" | "integrations" | "usage" | "guardrails" | "audit"
  | "workspaces" | "accessControl" | "apiKeys" | "secrets" | "organizations" | "extensions" | "health" | "settings";

export type LegacyPageId = "agents" | "workflows" | "tools" | "mcp" | "memory" | "feedback" | "budgets" | "members" | "roles" | "marketplace";
export type DataMode = "live" | "mixed" | "demo";

export interface NavigationItem {
  id: PageId;
  glyph: string;
  dataMode: DataMode;
  badge?: "conditional";
}

export interface NavigationGroup {
  id: "build" | "operate" | "govern" | "organization" | "system";
  items: NavigationItem[];
}

export const roles: Role[] = ["SYSTEM_ADMIN", "TENANT_ADMIN", "AI_ADMIN", "DEVELOPER", "OPERATOR", "MEMBER"];
export const overviewItem: NavigationItem = { id: "overview", glyph: "OV", dataMode: "mixed" };

export const navigationGroups: NavigationGroup[] = [
  { id: "build", items: [
    { id: "applications", glyph: "AP", dataMode: "live" },
    { id: "models", glyph: "MO", dataMode: "live" },
    { id: "prompts", glyph: "PR", dataMode: "live" },
    { id: "knowledge", glyph: "KN", dataMode: "demo" },
    { id: "capabilities", glyph: "TM", dataMode: "demo" },
    { id: "evaluations", glyph: "EV", dataMode: "demo" },
    { id: "playground", glyph: "PL", dataMode: "live" },
  ]},
  { id: "operate", items: [
    { id: "releases", glyph: "RE", dataMode: "live" },
    { id: "gateway", glyph: "GW", dataMode: "demo" },
    { id: "runs", glyph: "RT", dataMode: "live" },
    { id: "integrations", glyph: "IN", dataMode: "demo" },
  ]},
  { id: "govern", items: [
    { id: "usage", glyph: "UC", dataMode: "live" },
    { id: "guardrails", glyph: "GU", dataMode: "demo" },
    { id: "audit", glyph: "AU", dataMode: "demo" },
  ]},
  { id: "organization", items: [
    { id: "workspaces", glyph: "WS", dataMode: "demo" },
    { id: "accessControl", glyph: "AC", dataMode: "demo" },
    { id: "apiKeys", glyph: "AK", dataMode: "live" },
    { id: "secrets", glyph: "SE", dataMode: "live" },
  ]},
  { id: "system", items: [
    { id: "organizations", glyph: "OR", dataMode: "demo", badge: "conditional" },
    { id: "extensions", glyph: "EX", dataMode: "demo" },
    { id: "health", glyph: "HE", dataMode: "mixed" },
    { id: "settings", glyph: "ST", dataMode: "demo" },
  ]},
];

const rolePages: Record<Role, Set<PageId>> = {
  SYSTEM_ADMIN: new Set([overviewItem, ...navigationGroups.flatMap((group) => group.items)].map((item) => item.id)),
  TENANT_ADMIN: new Set(["overview", "applications", "models", "prompts", "knowledge", "capabilities", "evaluations", "playground", "releases", "gateway", "runs", "integrations", "usage", "guardrails", "audit", "workspaces", "accessControl", "apiKeys", "secrets", "extensions", "health", "settings"]),
  AI_ADMIN: new Set(["overview", "applications", "models", "prompts", "knowledge", "capabilities", "evaluations", "playground", "releases", "gateway", "runs", "integrations", "usage", "guardrails", "audit", "workspaces", "accessControl", "apiKeys", "secrets", "extensions"]),
  DEVELOPER: new Set(["overview", "applications", "models", "prompts", "knowledge", "capabilities", "evaluations", "playground", "releases", "runs", "integrations", "usage", "apiKeys"]),
  OPERATOR: new Set(["overview", "releases", "gateway", "runs", "integrations", "usage", "guardrails", "audit", "health"]),
  MEMBER: new Set(["overview", "applications", "evaluations", "playground", "runs"]),
};

export const legacyRedirects: Record<LegacyPageId, PageId> = {
  agents: "applications",
  workflows: "applications",
  tools: "capabilities",
  mcp: "capabilities",
  memory: "capabilities",
  feedback: "evaluations",
  budgets: "usage",
  members: "accessControl",
  roles: "accessControl",
  marketplace: "extensions",
};

export const allPageIds = [overviewItem.id, ...navigationGroups.flatMap((group) => group.items.map((item) => item.id))];

export function isPageId(value: string): value is PageId {
  return allPageIds.includes(value as PageId);
}

export function resolvePageId(value: string): PageId | null {
  const route = value.split("?")[0] ?? "";
  if (isPageId(route)) return route;
  return legacyRedirects[route as LegacyPageId] ?? null;
}

export function canView(role: Role, page: PageId): boolean {
  return rolePages[role].has(page);
}

export function visibleGroups(role: Role): NavigationGroup[] {
  return navigationGroups
    .map((group) => ({ ...group, items: group.items.filter((item) => canView(role, item.id)) }))
    .filter((group) => group.items.length > 0);
}

export function findNavigationItem(page: PageId): NavigationItem {
  if (page === "overview") return overviewItem;
  return navigationGroups.flatMap((group) => group.items).find((item) => item.id === page) ?? overviewItem;
}

export function groupForPage(page: PageId): NavigationGroup["id"] | "overview" {
  return navigationGroups.find((group) => group.items.some((item) => item.id === page))?.id ?? "overview";
}
