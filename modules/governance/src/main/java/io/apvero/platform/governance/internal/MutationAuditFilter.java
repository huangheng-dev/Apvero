package io.apvero.platform.governance.internal;

import io.apvero.platform.governance.AuditEventCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.apvero.platform.identity.RequestIdentityAttributes;

@Component
final class MutationAuditFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuditEventCatalog audit;

    MutationAuditFilter(AuditEventCatalog audit) {
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            boolean denied = response.getStatus() == 401 || response.getStatus() == 403;
            if ((MUTATIONS.contains(request.getMethod()) || denied) && request.getRequestURI().startsWith("/api/v1/")) {
                UUID workspaceId = auditWorkspace(request);
                String actor = (String) request.getAttribute(RequestIdentityAttributes.ACTOR);
                if (workspaceId != null && actor != null) {
                    String path = request.getRequestURI();
                    String resource = path.substring("/api/v1/".length()).split("/")[0];
                    String outcome = denied ? "DENIED" : response.getStatus() < 400 ? "SUCCEEDED" : "FAILED";
                    audit.append(workspaceId, actor, request.getMethod() + " " + path, resource, path,
                            outcome, request.getRemoteAddr(), null);
                }
            }
        }
    }

    private UUID workspaceId(HttpServletRequest request) {
        try {
            String value = request.getHeader("X-Apvero-Workspace-Id");
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private UUID auditWorkspace(HttpServletRequest request) {
        Object allowed = request.getAttribute(RequestIdentityAttributes.WORKSPACE_ID);
        return allowed instanceof UUID workspaceId ? workspaceId : workspaceId(request);
    }
}
