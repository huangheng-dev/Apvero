package io.apvero.platform.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain platformSecurity(HttpSecurity http, ApiAuthenticationFilter authentication) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(401, "Authentication is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                response.sendError(403, "Access is denied")))
                .addFilterBefore(authentication, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/api/v1/platform").permitAll()
                        .requestMatchers("/api/v1/api-keys/**").hasAuthority("SCOPE_admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyAuthority("SCOPE_read", "SCOPE_admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/**").hasAnyAuthority("SCOPE_write", "SCOPE_admin")
                        .anyRequest().denyAll())
                .build();
    }
}
