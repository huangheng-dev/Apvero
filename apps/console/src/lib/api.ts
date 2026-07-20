import type { AiApplication, ApiCredential, HealthStatus, IssuedApiCredential, ModelDefinition, ModelProvider, ModelRoute, PlatformInfo, PromptAsset, PromptVersion, ReleaseBundle, RunRecord, RuntimeMode, SecretReference, UsageSummary } from "./types";

export const WORKSPACE_ID = "00000000-0000-0000-0000-000000000101";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = sessionStorage.getItem("apvero.session.token");
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-Apvero-Workspace-Id": WORKSPACE_ID,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { code?: string; detail?: string };
    throw new ApiError(response.status, problem.code ?? "APVERO_REQUEST_FAILED", problem.detail ?? response.statusText);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
  platform: () => request<PlatformInfo>("/api/v1/platform"),
  platformHealth: () => request<HealthStatus>("/actuator/health"),
  workerHealth: () => request<HealthStatus>("/worker/health"),
  applications: () => request<AiApplication[]>("/api/v1/applications"),
  createApplication: (input: { slug: string; name: string; description: string; runtimeMode: RuntimeMode }) =>
    request<AiApplication>("/api/v1/applications", { method: "POST", body: JSON.stringify(input) }),
  bindApplicationDraft: (applicationId: string, modelRouteId: string, promptVersionId: string) =>
    request<AiApplication>(`/api/v1/applications/${applicationId}/draft`, { method: "PATCH", body: JSON.stringify({ modelRouteId, promptVersionId }) }),
  releases: (applicationId: string) =>
    request<ReleaseBundle[]>(`/api/v1/applications/${applicationId}/releases`),
  createRelease: (applicationId: string, version: string) =>
    request<ReleaseBundle>(`/api/v1/applications/${applicationId}/releases`, {
      method: "POST",
      body: JSON.stringify({ version }),
    }),
  runs: () => request<RunRecord[]>("/api/v1/runs"),
  execute: (applicationId: string, releaseId: string, message: string) =>
    request<RunRecord>(`/api/v1/applications/${applicationId}/runs`, {
      method: "POST",
      body: JSON.stringify({ releaseId, input: { message } }),
    }),
  preview: (applicationId: string, input: Record<string, unknown>) =>
    request<RunRecord>(`/api/v1/applications/${applicationId}/preview-runs`, { method: "POST", body: JSON.stringify({ input }) }),
  usage: () => request<UsageSummary>("/api/v1/usage"),
  secrets: () => request<SecretReference[]>("/api/v1/secrets"),
  createSecret: (input: { name: string; environmentVariable: string }) => request<SecretReference>("/api/v1/secrets", { method: "POST", body: JSON.stringify(input) }),
  modelProviders: () => request<ModelProvider[]>("/api/v1/model-providers"),
  createModelProvider: (input: { name: string; providerType: ModelProvider["providerType"]; baseUrl: string; secretReferenceId?: string }) => request<ModelProvider>("/api/v1/model-providers", { method: "POST", body: JSON.stringify(input) }),
  models: () => request<ModelDefinition[]>("/api/v1/models"),
  createModel: (input: { providerId: string; modelKey: string; name: string; capabilities: string[]; inputCostMicrosPerMillion: number; outputCostMicrosPerMillion: number }) => request<ModelDefinition>("/api/v1/models", { method: "POST", body: JSON.stringify(input) }),
  modelRoutes: () => request<ModelRoute[]>("/api/v1/model-routes"),
  createModelRoute: (input: { name: string; modelId: string; timeoutMs: number; maxOutputTokens: number; temperature?: number }) => request<ModelRoute>("/api/v1/model-routes", { method: "POST", body: JSON.stringify(input) }),
  prompts: () => request<PromptAsset[]>("/api/v1/prompts"),
  createPrompt: (input: { slug: string; name: string; description: string }) => request<PromptAsset>("/api/v1/prompts", { method: "POST", body: JSON.stringify(input) }),
  promptVersions: (promptId: string) => request<PromptVersion[]>(`/api/v1/prompts/${promptId}/versions`),
  createPromptVersion: (promptId: string, input: { systemPrompt: string; variables: string[] }) => request<PromptVersion>(`/api/v1/prompts/${promptId}/versions`, { method: "POST", body: JSON.stringify(input) }),
  apiKeys: () => request<ApiCredential[]>("/api/v1/api-keys"),
  issueApiKey: (input: { name: string; scopes: string[]; expiresAt?: string }) => request<IssuedApiCredential>("/api/v1/api-keys", { method: "POST", body: JSON.stringify(input) }),
  revokeApiKey: (id: string) => request<void>(`/api/v1/api-keys/${id}`, { method: "DELETE" }),
};

export const defaultManifest = {
  schemaVersion: "1.0",
  modelRouteVersion: "local-deterministic@1",
  promptVersion: "apvero-baseline@1",
  outputSchemaVersion: "output@1.0.0",
  knowledgeIndexVersions: ["knowledge@1.0.0"],
  capabilityVersions: ["core.read@1.0.0"],
  policyVersions: ["baseline@1.0.0"],
  memoryPolicyVersion: "session@1.0.0",
  evaluationReportVersion: "smoke@1.0.0",
  runtimeParameters: { temperature: 0, maxOutputTokens: 512 },
};
