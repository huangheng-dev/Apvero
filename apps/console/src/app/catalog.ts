import type { PageId } from "./navigation";

export interface DemoRow {
  id: string;
  name: string;
  type: string;
  status: "ACTIVE" | "HEALTHY" | "DRAFT" | "WARNING" | "BLOCKED" | "DISABLED" | "PUBLISHED" | "CONNECTED";
  owner: string;
  metric: string;
  updated: string;
  detail: string;
}

export interface PageFixture {
  stats: Array<{ label: "total" | "active" | "healthy" | "warnings"; value: string; delta: string; tone?: "good" | "warn" }>;
  tabs: string[];
  rows: DemoRow[];
}

const row = (id: string, name: string, type: string, status: DemoRow["status"], owner: string, metric: string, updated: string, detail: string): DemoRow =>
  ({ id, name, type, status, owner, metric, updated, detail });

const baseStats = (total: string, active: string, healthy: string, warnings: string): PageFixture["stats"] => [
  { label: "total", value: total, delta: "+3 this month" },
  { label: "active", value: active, delta: "Across this workspace", tone: "good" },
  { label: "healthy", value: healthy, delta: "Last 15 minutes", tone: "good" },
  { label: "warnings", value: warnings, delta: "Needs review", tone: warnings === "0" ? "good" : "warn" },
];

export const pageFixtures: Partial<Record<PageId, PageFixture>> = {
  models: { stats: baseStats("12", "10", "9", "1"), tabs: ["overview", "providers", "routes", "health"], rows: [
    row("model-01", "GPT-5 Production", "OPENAI", "HEALTHY", "Global", "842 ms p50", "1 min ago", "Primary reasoning route with structured output and tool calling."),
    row("model-02", "Claude Sonnet", "ANTHROPIC", "HEALTHY", "Global", "918 ms p50", "2 min ago", "Fallback route for long-context workloads."),
    row("model-03", "DeepSeek Reasoner", "OPENAI_COMPATIBLE", "ACTIVE", "Engineering", "$0.42 / 1M", "4 min ago", "Cost-controlled reasoning route for engineering applications."),
    row("model-04", "Qwen3 32B", "VLLM", "WARNING", "CN Workspace", "94% availability", "6 min ago", "Private route with one GPU saturation warning."),
  ]},
  prompts: { stats: baseStats("46", "31", "29", "2"), tabs: ["library", "versions", "testing", "publishing"], rows: [
    row("prompt-01", "Customer Support System", "SYSTEM", "PUBLISHED", "Customer Success", "v18", "12 min ago", "Support tone, escalation policy, and citation behavior."),
    row("prompt-02", "Contract Risk Review", "SYSTEM", "ACTIVE", "Legal", "v7", "45 min ago", "Extracts risky clauses into a typed review object."),
    row("prompt-03", "Procurement Comparison", "TEMPLATE", "DRAFT", "Procurement", "12 variables", "2 hours ago", "Supplier comparison template with policy context."),
    row("prompt-04", "Incident Summary", "TEMPLATE", "WARNING", "Operations", "2 failed cases", "Yesterday", "Converts runtime incidents into a postmortem outline."),
  ]},
  knowledge: { stats: baseStats("24", "21", "20", "1"), tabs: ["knowledgeBases", "dataSources", "ingestion", "retrievalLab"], rows: [
    row("kb-01", "Product Documentation", "WEB_GIT", "HEALTHY", "Product", "18,420 chunks", "9 min ago", "Documentation, release notes, and troubleshooting content."),
    row("kb-02", "Procurement Policies", "PDF_SHAREPOINT", "ACTIVE", "Procurement", "4,208 chunks", "26 min ago", "Approved policies, supplier rules, and purchasing thresholds."),
    row("kb-03", "Legal Clause Library", "WORD_CONFLUENCE", "HEALTHY", "Legal", "7,910 chunks", "1 hour ago", "Versioned clauses with jurisdiction and risk metadata."),
    row("kb-04", "Engineering Runbooks", "GIT", "WARNING", "Engineering", "3 sync failures", "2 hours ago", "Operational runbooks with one credential warning."),
  ]},
  capabilities: { stats: baseStats("51", "41", "38", "3"), tabs: ["tools", "mcpServers", "memoryProviders", "permissions"], rows: [
    row("tool-01", "ERP Inventory Query", "TOOL_HTTP", "ACTIVE", "Supply Chain", "18.4k calls", "2 min ago", "Read-only inventory lookup with warehouse and SKU allowlists."),
    row("tool-02", "Analytics SQL", "TOOL_DATABASE", "ACTIVE", "Data", "250 row limit", "11 min ago", "Read-only SQL with schema allowlists and statement timeout."),
    row("mcp-01", "GitHub MCP", "MCP_STREAMABLE_HTTP", "CONNECTED", "Engineering", "21 tools", "1 min ago", "Repository, issue, pull request, and code search capabilities."),
    row("mcp-02", "Jira MCP", "MCP_SSE", "WARNING", "Product", "2 denied calls", "12 min ago", "Issue discovery and controlled workflow transitions."),
    row("memory-01", "Support Conversation", "MEMORY_SESSION", "ACTIVE", "Customer Success", "30 day TTL", "5 min ago", "Session summaries with PII masking and explicit retention."),
  ]},
  evaluations: { stats: baseStats("23", "12", "10", "2"), tabs: ["datasets", "evaluators", "experiments", "feedback", "releaseGates"], rows: [
    row("dataset-01", "Support Regression", "DATASET", "ACTIVE", "Customer Success", "1,000 cases", "18 min ago", "Groundedness, resolution quality, tone, and escalation cases."),
    row("experiment-01", "Contract Prompt v8", "EXPERIMENT", "HEALTHY", "Legal", "94.7% pass", "1 hour ago", "Candidate prompt compared with the production version."),
    row("feedback-01", "Unsupported refund answer", "FEEDBACK", "WARNING", "Customer Success", "42 similar", "28 min ago", "Reviewed production trace ready for dataset promotion."),
    row("gate-01", "Procurement Safety Gate", "RELEASE_GATE", "PUBLISHED", "Procurement", "5 checks", "3 hours ago", "Blocks release when policy or permission evaluation fails."),
  ]},
  gateway: { stats: baseStats("14", "11", "10", "1"), tabs: ["traffic", "routes", "rateLimits", "caching"], rows: [
    row("route-01", "Production Chat Route", "MODEL_ROUTE", "ACTIVE", "Global", "842k calls", "Now", "Weighted provider route with retries and circuit breaking."),
    row("route-02", "CN Private Route", "MODEL_ROUTE", "HEALTHY", "CN Workspace", "184k calls", "1 min ago", "Private inference route for regional data residency."),
    row("policy-01", "Developer Rate Limit", "RATE_LIMIT", "ACTIVE", "Engineering", "60 rpm", "4 min ago", "Workspace and principal-aware request limit."),
    row("cache-01", "Support Semantic Cache", "SEMANTIC_CACHE", "WARNING", "Customer Success", "38% hit rate", "7 min ago", "Version-aware cache with one stale-index warning."),
  ]},
  integrations: { stats: baseStats("16", "13", "12", "1"), tabs: ["webhooks", "destinations", "deliveryLog", "deadLetter"], rows: [
    row("int-01", "ERP Release Hook", "WEBHOOK", "ACTIVE", "Integration", "99.98% delivery", "2 min ago", "Signed production release notifications."),
    row("int-02", "Runtime Events", "KAFKA", "HEALTHY", "Data", "2.1M events", "4 min ago", "Versioned run and usage events for analytics."),
    row("int-03", "Operations Alerts", "DINGTALK", "ACTIVE", "Operations", "184 deliveries", "8 min ago", "Guardrail and health alerts for on-call teams."),
    row("int-04", "Legacy CRM Hook", "WEBHOOK", "WARNING", "Sales", "7 retries", "11 min ago", "Delivery failures waiting in the retry queue."),
  ]},
  usage: { stats: baseStats("28.4M", "$4,286", "$25k", "2"), tabs: ["usage", "costs", "budgets", "forecast", "cacheSavings"], rows: [
    row("cost-01", "Engineering", "DEPARTMENT_USAGE", "ACTIVE", "Engineering", "$1,842 · 12.4M", "Today", "Attributed model, tool, and cache usage."),
    row("cost-02", "Customer Success", "DEPARTMENT_USAGE", "ACTIVE", "Customer Success", "$1,106 · 8.9M", "Today", "Support workloads with semantic-cache savings."),
    row("budget-01", "Procurement Monthly", "BUDGET", "WARNING", "Procurement", "86% used", "18 min ago", "Projected to exceed its monthly threshold by 6.8%."),
    row("forecast-01", "Organization Forecast", "FORECAST", "HEALTHY", "Global", "$18.7k projected", "1 hour ago", "Month-end forecast across all workspaces."),
  ]},
  guardrails: { stats: baseStats("27", "22", "21", "1"), tabs: ["inputPolicies", "outputPolicies", "sensitiveData", "incidents"], rows: [
    row("guard-01", "Prompt Injection Defense", "INPUT_POLICY", "ACTIVE", "Security", "18.7k checks", "1 min ago", "Detects and blocks instruction-override attempts."),
    row("guard-02", "PII Output Masking", "OUTPUT_POLICY", "HEALTHY", "Security", "842 redactions", "3 min ago", "Masks configured personal and financial identifiers."),
    row("guard-03", "SQL Write Deny", "TOOL_POLICY", "ACTIVE", "Data", "17 blocks", "8 min ago", "Denies mutating statements by default."),
    row("guard-04", "Source Citation", "OUTPUT_POLICY", "WARNING", "Legal", "96.2% pass", "14 min ago", "Requires evidence for regulated knowledge answers."),
  ]},
  audit: { stats: baseStats("18.2k", "214", "214", "0"), tabs: ["events", "policyDecisions", "exports"], rows: [
    row("audit-01", "Release created", "RESOURCE_CHANGE", "ACTIVE", "Sara Williams", "release/create", "12 min ago", "Immutable release bundle was created."),
    row("audit-02", "Provider secret rotated", "SECURITY_EVENT", "ACTIVE", "Alex Chen", "secret/rotate", "24 min ago", "Provider secret verifier and metadata changed."),
    row("audit-03", "Tool call denied", "POLICY_DECISION", "BLOCKED", "Policy Engine", "tool/sql.write", "31 min ago", "Requested method was outside the granted scope."),
    row("audit-04", "Role policy updated", "ACCESS_CHANGE", "ACTIVE", "Lin Wei", "role/update", "1 hour ago", "Workspace operator policy was updated."),
  ]},
  workspaces: { stats: baseStats("8", "7", "7", "0"), tabs: ["workspaces", "environments", "members", "quotas"], rows: [
    row("ws-01", "AI Platform", "PRODUCTION", "ACTIVE", "Platform", "42 applications", "Now", "Shared production AI engineering workspace."),
    row("ws-02", "Customer Success", "PRODUCTION", "HEALTHY", "Customer Success", "18 applications", "4 min ago", "Support and customer operations workspace."),
    row("ws-03", "Engineering Sandbox", "DEVELOPMENT", "ACTIVE", "Engineering", "26 members", "12 min ago", "Isolated development workspace with limited quotas."),
  ]},
  accessControl: { stats: baseStats("184", "172", "168", "4"), tabs: ["members", "invitations", "roles", "policies", "identityProviders"], rows: [
    row("member-01", "Alex Chen", "DEVELOPER", "ACTIVE", "Engineering", "3 workspaces", "5 min ago", "Build and test access to engineering applications."),
    row("member-02", "Sara Williams", "AI_ADMIN", "ACTIVE", "AI Platform", "Global workspace", "12 min ago", "Manages shared AI assets and releases."),
    row("role-01", "Application Developer", "ROLE", "ACTIVE", "Organization", "26 permissions", "3 days ago", "Creates, tests, evaluates, and releases assigned applications."),
    row("idp-01", "Corporate OIDC", "IDENTITY_PROVIDER", "DRAFT", "Security", "Configuration review", "Yesterday", "Reserved enterprise identity provider configuration."),
  ]},
  apiKeys: { stats: baseStats("22", "17", "17", "1"), tabs: ["keys", "scopes", "activity"], rows: [
    row("key-01", "ERP Production", "WORKLOAD", "ACTIVE", "Integration", "2 apps · 60 rpm", "3 min ago", "Scoped production credential for procurement applications."),
    row("key-02", "Developer Sandbox", "PERSONAL", "ACTIVE", "Alex Chen", "5 apps · 20 rpm", "18 min ago", "Expiring sandbox credential without production access."),
    row("key-03", "Legacy MES", "WORKLOAD", "WARNING", "Manufacturing", "Expires in 3 days", "1 hour ago", "Rotation is required before expiry."),
  ]},
  secrets: { stats: baseStats("38", "34", "34", "4"), tabs: ["secretReferences", "rotation", "usageRelations"], rows: [
    row("secret-01", "OpenAI Production", "PROVIDER_KEY", "ACTIVE", "AI Platform", "Used by 3 routes", "2 hours ago", "Encrypted reference; plaintext is never returned."),
    row("secret-02", "ERP OAuth Client", "OAUTH_CLIENT", "ACTIVE", "Integration", "Rotated 12d ago", "Yesterday", "Organization-scoped OAuth secret reference."),
    row("secret-03", "Legacy Webhook Secret", "WEBHOOK", "WARNING", "Operations", "Rotate in 5d", "Yesterday", "Rotation policy requires replacement."),
  ]},
  organizations: { stats: baseStats("12", "11", "11", "1"), tabs: ["organizations", "plans", "usage"], rows: [
    row("org-01", "Northstar Group", "ENTERPRISE", "ACTIVE", "Platform", "8 workspaces", "Now", "Primary organization for the current product preview."),
    row("org-02", "Helio Manufacturing", "SELF_HOSTED", "HEALTHY", "Platform", "4 workspaces", "18 min ago", "Self-hosted organization with regional data controls."),
    row("org-03", "Acme Sandbox", "COMMUNITY", "WARNING", "Platform", "Quota review", "1 hour ago", "Conditional system-administrator view for multi-organization deployments."),
  ]},
  extensions: { stats: baseStats("86", "14", "12", "2"), tabs: ["marketplace", "installed", "permissions", "health"], rows: [
    row("ext-01", "SAP S/4HANA Connector", "INTEGRATION", "HEALTHY", "Procurement", "v2.4.1 · signed", "4 min ago", "Installed isolated connector with approved permissions."),
    row("ext-02", "Jira MCP Adapter", "MCP", "ACTIVE", "Engineering", "v1.8.0 · signed", "8 min ago", "Installed adapter connected to two workspaces."),
    row("market-01", "Groundedness Evaluator", "MARKETPLACE_EVALUATOR", "PUBLISHED", "Apvero Labs", "v3.1.2", "3 days ago", "Signed marketplace evaluator for citation coverage."),
    row("market-02", "Database Explorer", "MARKETPLACE_TOOL", "WARNING", "Community", "Review pending", "Today", "New version requests expanded permissions."),
  ]},
};
