package io.apvero.platform.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain platformSecurity(HttpSecurity http, ApiAuthenticationFilter authentication) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, 401, "APVERO_AUTHENTICATION_REQUIRED"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(response, 403, "APVERO_ACCESS_DENIED")))
                .addFilterBefore(authentication, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/api/v1/platform").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_admin")
                        .requestMatchers("/api/v1/api-keys/**").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyAuthority("SCOPE_read", "SCOPE_admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .anyRequest().denyAll())
                .build();
    }

    private static void writeProblem(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"urn:apvero:problem:" + code.toLowerCase(java.util.Locale.ROOT)
                + "\",\"title\":\"" + code + "\",\"status\":" + status + ",\"code\":\"" + code + "\"}");
    }
}
