package io.apvero.platform.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.apvero.platform.identity.RequestIdentityAttributes;
import io.apvero.platform.identity.SecurityDecisionEvent;
import org.springframework.context.ApplicationEventPublisher;

@Component
final class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final ApiCredentialAuthenticator credentials;
    private final String mode;
    private final String bootstrapToken;
    private final ApplicationEventPublisher events;

    ApiAuthenticationFilter(
            ApiCredentialAuthenticator credentials,
            @Value("${apvero.security.mode:development}") String mode,
            @Value("${apvero.security.bootstrap-token:}") String bootstrapToken,
            ApplicationEventPublisher events) {
        this.credentials = credentials;
        this.mode = mode;
        this.bootstrapToken = bootstrapToken;
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
            if (bearer != null && !bootstrapToken.isBlank() && CredentialVerifier.constantTimeEquals(bearer, bootstrapToken)) {
                authenticate("bootstrap-admin", null, List.of("read", "write", "admin"));
            } else if (bearer != null) {
                var credential = credentials.authenticate(bearer);
                if (credential != null) authenticate(credential.name(), credential.workspaceId(), credential.scopes().stream().toList());
            } else if ("development".equalsIgnoreCase(mode)) {
                authenticate("local-development-admin", null, List.of("read", "write", "admin"));
            }
        }

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            request.setAttribute(RequestIdentityAttributes.ACTOR, authentication.getName());
            if (authentication.getDetails() instanceof UUID allowedWorkspace) {
                request.setAttribute(RequestIdentityAttributes.WORKSPACE_ID, allowedWorkspace);
            }
        }
        String workspaceHeader = request.getHeader("X-Apvero-Workspace-Id");
        if (authentication != null && authentication.getDetails() instanceof UUID allowedWorkspace && workspaceHeader != null) {
            try {
                if (!allowedWorkspace.equals(UUID.fromString(workspaceHeader))) {
                    events.publishEvent(new SecurityDecisionEvent(allowedWorkspace, authentication.getName(),
                            request.getMethod() + " " + request.getRequestURI(), workspaceHeader,
                            request.getRemoteAddr(), "WORKSPACE_ACCESS_DENIED"));
                    writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "APVERO_WORKSPACE_ACCESS_DENIED");
                    return;
                }
            } catch (IllegalArgumentException exception) {
                writeProblem(response, HttpServletResponse.SC_BAD_REQUEST, "APVERO_WORKSPACE_ID_INVALID");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String name, UUID workspaceId, List<String> scopes) {
        var authorities = scopes.stream().map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(name, null, authorities);
        authentication.setDetails(workspaceId);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void writeProblem(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"urn:apvero:problem:" + code.toLowerCase(java.util.Locale.ROOT)
                + "\",\"title\":\"" + code + "\",\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
