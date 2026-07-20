import "@testing-library/jest-dom/vitest";
import { describe, expect, it } from "vitest";
import { pageFixtures } from "./app/catalog";
import { allPageIds, canView, findNavigationItem, legacyRedirects, navigationGroups, resolvePageId, visibleGroups, type PageId } from "./app/navigation";
import { defaultManifest } from "./lib/api";

describe("release manifest", () => {
  it("pins every reproducibility dependency", () => {
    expect(defaultManifest.modelRouteVersion).not.toContain("latest");
    expect(defaultManifest).toHaveProperty("promptVersion");
    expect(defaultManifest).toHaveProperty("evaluationReportVersion");
    expect(defaultManifest).toHaveProperty("runtimeParameters");
  });
});

describe("product navigation contract", () => {
  it("keeps one unique route for every approved product page", () => {
    expect(allPageIds).toHaveLength(23);
    expect(new Set(allPageIds).size).toBe(allPageIds.length);
    expect(navigationGroups.map((group) => group.id)).toEqual(["build", "operate", "govern", "organization", "system"]);
  });

  it("gives system administrators the complete surface and members a bounded surface", () => {
    expect(visibleGroups("SYSTEM_ADMIN").flatMap((group) => group.items)).toHaveLength(22);
    expect(canView("MEMBER", "playground")).toBe(true);
    expect(canView("MEMBER", "evaluations")).toBe(true);
    expect(canView("MEMBER", "secrets")).toBe(false);
    expect(canView("OPERATOR", "health")).toBe(true);
    expect(canView("SYSTEM_ADMIN", "organizations")).toBe(true);
    expect(canView("TENANT_ADMIN", "organizations")).toBe(false);
  });

  it("keeps reserved features behind canonical pages and marks real pages explicitly", () => {
    expect(legacyRedirects.agents).toBe("applications");
    expect(legacyRedirects.workflows).toBe("applications");
    expect(legacyRedirects.mcp).toBe("capabilities");
    expect(resolvePageId("agents")).toBe("applications");
    expect(resolvePageId("budgets?tab=forecast")).toBe("usage");
    expect(resolvePageId("unknown")).toBeNull();
    expect(findNavigationItem("applications").dataMode).toBe("live");
    expect(findNavigationItem("models").dataMode).toBe("live");
    expect(findNavigationItem("prompts").dataMode).toBe("live");
    expect(findNavigationItem("playground").dataMode).toBe("live");
    expect(findNavigationItem("usage").dataMode).toBe("live");
    expect(findNavigationItem("apiKeys").dataMode).toBe("live");
    expect(findNavigationItem("secrets").dataMode).toBe("live");
    expect(findNavigationItem("releases").dataMode).toBe("live");
    expect(findNavigationItem("runs").dataMode).toBe("live");
  });

  it("provides populated fixtures for every generic prototype page", () => {
    const specialized = new Set<PageId>(["overview", "applications", "models", "prompts", "playground", "releases", "runs", "usage", "apiKeys", "secrets", "health", "settings"]);
    const generic = allPageIds.filter((page) => !specialized.has(page));
    expect(generic).toHaveLength(11);
    for (const page of generic) {
      expect(pageFixtures[page]?.rows.length, page).toBeGreaterThanOrEqual(3);
      expect(pageFixtures[page]?.stats).toHaveLength(4);
    }
  });
});
