export type RuntimeMode = "CHAT" | "RAG" | "STRUCTURED" | "TOOL" | "AGENTIC" | "WORKFLOW";

export interface AiApplication {
  id: string;
  tenantId: string;
  workspaceId: string;
  slug: string;
  name: string;
  description: string;
  runtimeMode: RuntimeMode;
  status: "DRAFT" | "TESTING" | "PUBLISHED" | "DEPRECATED";
  draftModelRouteId?: string | null;
  draftPromptVersionId?: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReleaseBundle {
  id: string;
  tenantId: string;
  workspaceId: string;
  applicationId: string;
  version: string;
  artifactDigest: string;
  manifest: Record<string, unknown>;
  status: "RELEASED" | "RETIRED";
  purpose: "PRODUCTION" | "PREVIEW";
  expiresAt?: string | null;
  createdAt: string;
}

export interface RunRecord {
  id: string;
  applicationId: string;
  releaseBundleId: string;
  modelRouteId?: string | null;
  status: "SUCCEEDED" | "FAILED";
  providerId: string;
  actorId: string;
  governanceReservationId?: string | null;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  latencyMs: number;
  promptTokens: number;
  completionTokens: number;
  costMicros: number;
  traceId: string;
  failureCategory?: string | null;
  failureMessage?: string | null;
  createdAt: string;
}

export interface BudgetPolicy {
  id: string; name: string; scopeType: "WORKSPACE" | "APPLICATION" | "MODEL_ROUTE"; scopeId?: string | null;
  monthlyCostLimitMicros?: number | null; requestsPerMinute?: number | null; enabled: boolean;
  createdAt: string; updatedAt: string;
}

export interface AuditEvent {
  id: string; occurredAt: string; actorId: string; action: string; resourceType: string;
  resourceId?: string | null; outcome: "SUCCEEDED" | "DENIED" | "FAILED"; sourceIp?: string | null;
  traceId?: string | null; details: Record<string, unknown>;
}

export interface RetentionPolicy {
  workspaceId: string; runRetentionDays: number; auditRetentionDays: number; retainPayloads: boolean;
  maskSensitiveFields: boolean; version: number; createdAt: string; updatedAt: string;
}

export interface ModelRouteReadiness {
  routeId: string; routeReference: string; providerName: string; providerType: string; status: string;
  ready: boolean; reasonCode: string;
}

export interface SecretReference {
  id: string; name: string; kind: "ENVIRONMENT"; locator: string; status: "ACTIVE" | "DISABLED";
  rotatedAt?: string | null; createdAt: string; updatedAt: string;
}

export interface ModelProvider {
  id: string; name: string; providerType: "OPENAI_COMPATIBLE" | "DETERMINISTIC_LOCAL"; baseUrl: string;
  secretReferenceId?: string | null; enabled: boolean; version: number; createdAt: string; updatedAt: string;
}

export interface ModelDefinition {
  id: string; providerId: string; modelKey: string; name: string; capabilities: string[];
  inputCostMicrosPerMillion: number; outputCostMicrosPerMillion: number; enabled: boolean; createdAt: string; updatedAt: string;
}

export interface ModelRoute {
  id: string; name: string; version: number; modelId: string; status: "PUBLISHED" | "DEPRECATED";
  timeoutMs: number; maxOutputTokens: number; temperature?: number | null; createdAt: string;
}

export interface PromptAsset {
  id: string; slug: string; name: string; description: string; createdAt: string; updatedAt: string;
}

export interface PromptVersion {
  id: string; promptAssetId: string; version: number; systemPrompt: string; variables: string[];
  status: "PUBLISHED"; createdAt: string;
}

export interface UsageSummary {
  runs: number; successfulRuns: number; failedRuns: number; promptTokens: number; completionTokens: number;
  totalTokens: number; costMicros: number; averageLatencyMs: number;
}

export interface ApiCredential {
  id: string; name: string; prefix: string; scopes: string[]; status: "ACTIVE" | "REVOKED";
  expiresAt?: string | null; lastUsedAt?: string | null; createdAt: string;
}

export interface IssuedApiCredential { credential: ApiCredential; plaintext: string; }

export interface PlatformInfo {
  name: string;
  version: string;
  status: string;
  sourceLocale: string;
  supportedLocales: string[];
  capabilities: Record<string, boolean>;
  serverTime: string;
}

export interface HealthStatus {
  status: string;
}
