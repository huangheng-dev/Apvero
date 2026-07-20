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
  status: "SUCCEEDED" | "FAILED";
  providerId: string;
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
