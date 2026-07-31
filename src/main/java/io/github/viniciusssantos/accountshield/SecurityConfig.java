package io.github.viniciusssantos.accountshield;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
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
