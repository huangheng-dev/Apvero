package io.apvero.platform.governance.api;

import io.apvero.platform.governance.AuditEvent;
import io.apvero.platform.governance.AuditEventCatalog;
import io.apvero.platform.governance.BudgetPolicy;
import io.apvero.platform.governance.BudgetPolicyCatalog;
import io.apvero.platform.governance.BudgetScopeType;
import io.apvero.platform.governance.CreateBudgetPolicyCommand;
import io.apvero.platform.governance.RetentionPolicy;
import io.apvero.platform.governance.RetentionPolicyCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
final class GovernanceController {
    private final BudgetPolicyCatalog budgets;
    private final RetentionPolicyCatalog retention;
    private final AuditEventCatalog audit;

    GovernanceController(BudgetPolicyCatalog budgets, RetentionPolicyCatalog retention, AuditEventCatalog audit) {
        this.budgets = budgets;
        this.retention = retention;
        this.audit = audit;
    }

    @GetMapping("/budget-policies")
    List<BudgetPolicy> budgets(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return budgets.listBudgets(workspaceId);
    }

    @PostMapping("/budget-policies")
    ResponseEntity<BudgetPolicy> createBudget(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody CreateBudgetRequest request) {
        BudgetPolicy created = budgets.create(workspaceId, new CreateBudgetPolicyCommand(request.name(),
                request.scopeType(), request.scopeId(), request.monthlyCostLimitMicros(), request.requestsPerMinute()));
        return ResponseEntity.created(URI.create("/api/v1/budget-policies/" + created.id())).body(created);
    }

    @GetMapping("/retention-policy")
    RetentionPolicy retention(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return retention.get(workspaceId);
    }

    @PutMapping("/retention-policy")
    RetentionPolicy updateRetention(
            @RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId,
            @Valid @RequestBody UpdateRetentionRequest request) {
        return retention.update(workspaceId, request.runRetentionDays(), request.auditRetentionDays(),
                request.retainPayloads(), request.maskSensitiveFields());
    }

    @GetMapping("/audit-events")
    List<AuditEvent> audit(@RequestHeader("X-Apvero-Workspace-Id") UUID workspaceId) {
        return audit.listAuditEvents(workspaceId);
    }

    record CreateBudgetRequest(
            @NotBlank @Size(max = 160) String name,
            @NotNull BudgetScopeType scopeType,
            UUID scopeId,
            @Min(0) Long monthlyCostLimitMicros,
            @Min(1) Integer requestsPerMinute) {}

    record UpdateRetentionRequest(
            @Min(1) @Max(3650) int runRetentionDays,
            @Min(30) @Max(3650) int auditRetentionDays,
            boolean retainPayloads,
            boolean maskSensitiveFields) {}
}
