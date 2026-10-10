package com.a1.notify.security;

import com.a1.notify.config.NotifyProperties;
import com.a1.notify.repository.ApiKeyRepository;
import com.a1.notify.repository.ProjectRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/info", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyRepository keys, ProjectRepository projects,
                                                   NotifyProperties props) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/admin/**").hasAuthority("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json");
                            response.getWriter().write(errorJson("UNAUTHENTICATED", "A valid X-Api-Key is required"));
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json");
                            response.getWriter().write(errorJson("FORBIDDEN", "Insufficient permissions"));
                        }))
                .addFilterBefore(new ApiKeyAuthFilter(keys, projects, props), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Same shape as GlobalExceptionHandler.ErrorBody. The correlation id is validated by CorrelationIdFilter, so it is JSON-safe. */
    private static String errorJson(String code, String message) {
        String correlationId = org.slf4j.MDC.get(com.a1.notify.web.CorrelationIdFilter.MDC_KEY);
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"correlationId\":"
                + (correlationId == null ? "null" : "\"" + correlationId + "\"") + "}";
    }
}
