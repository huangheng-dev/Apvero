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

@Component
final class ApiAuthenticationFilter extends OncePerRequestFilter {
    private final ApiCredentialAuthenticator credentials;
    private final String mode;
    private final String bootstrapToken;

    ApiAuthenticationFilter(
            ApiCredentialAuthenticator credentials,
            @Value("${apvero.security.mode:development}") String mode,
            @Value("${apvero.security.bootstrap-token:}") String bootstrapToken) {
        this.credentials = credentials;
        this.mode = mode;
        this.bootstrapToken = bootstrapToken;
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
        String workspaceHeader = request.getHeader("X-Apvero-Workspace-Id");
        if (authentication != null && authentication.getDetails() instanceof UUID allowedWorkspace && workspaceHeader != null) {
            try {
                if (!allowedWorkspace.equals(UUID.fromString(workspaceHeader))) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            } catch (IllegalArgumentException exception) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
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
}
