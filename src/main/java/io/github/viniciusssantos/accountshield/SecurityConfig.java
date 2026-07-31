package io.github.viniciusssantos.accountshield;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ProblemDetailAuthenticationEntryPoint entryPoint,
            ProblemDetailAccessDeniedHandler deniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        // DevTokenController (POST /dev/tokens) only registers under the "local"
                        // profile; the matcher is scoped to its exact path (not /dev/**) so a future
                        // controller under /dev/* that forgets @Profile("local") is NOT public by default.
                        .requestMatchers("/dev/tokens").permitAll()
                        // simulates an external, unauthenticated receiver verifying its own HMAC signature
                        .requestMatchers("/demo/webhook-receiver").permitAll()
                        // demo/portfolio-scoped operator login (ADR 0046); not a real identity provider
                        .requestMatchers("/auth/session-tokens").permitAll()
                        // refresh presents the caller's still-valid JWT as a normal bearer credential and
                        // is validated by the same oauth2 resource server filter chain as any other
                        // authenticated endpoint, so it needs no special-cased matcher beyond authentication
                        .requestMatchers("/auth/session-tokens/refresh").authenticated()
                        .requestMatchers("/actuator/**").hasRole("OBSERVABILITY_READER")
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").authenticated()
                        .requestMatchers("/api/v1/protection-decisions").hasRole("PROTECTION_CLIENT")
                        // shared by protection step-up, recovery identity, and privileged-operation step-up;
                        // purpose/context/account binding inside the challenge module enforces the real safety
                        .requestMatchers("/api/v1/challenges/**").authenticated()
                        .requestMatchers("/api/v1/recovery", "/api/v1/recovery/*/confirm-identity",
                                "/api/v1/recovery/*/complete").hasRole("PROTECTION_CLIENT")
                        .requestMatchers("/api/v1/recovery/*/review", "/api/v1/recovery/*/review/step-up")
                                .hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/policies/**").hasRole("POLICY_ADMIN")
                        .requestMatchers("/api/v1/simulation/**").hasRole("SIMULATION_ANALYST")
                        .requestMatchers("/api/v1/outbox/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/webhooks/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/audit/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/operator/decisions/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/operator/recoveries/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/operator/policies/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/operator/outbox/**").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/evidence/**").hasRole("SECURITY_OPERATOR")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        // Without this, a malformed/expired/tampered bearer token fails inside the
                        // resource-server filter itself and gets Spring's bare default 401 (a
                        // WWW-Authenticate header, no body) instead of the app's stable Problem Details
                        // shape -- only a missing token reached the shared exceptionHandling() entry
                        // point below without this explicit wiring.
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(LocalJwtKeys.ROLES_CLAIM);
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
