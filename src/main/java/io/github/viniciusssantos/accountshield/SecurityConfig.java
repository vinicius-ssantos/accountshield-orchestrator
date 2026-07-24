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
                        // DevTokenController only registers under the "local" profile; harmless elsewhere
                        .requestMatchers("/dev/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OBSERVABILITY_READER")
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").authenticated()
                        .requestMatchers("/api/v1/protection-decisions").hasRole("PROTECTION_CLIENT")
                        .requestMatchers("/api/v1/challenges/**").hasRole("PROTECTION_CLIENT")
                        .requestMatchers("/api/v1/recovery", "/api/v1/recovery/*/confirm-identity",
                                "/api/v1/recovery/*/complete").hasRole("PROTECTION_CLIENT")
                        .requestMatchers("/api/v1/recovery/*/review").hasRole("SECURITY_OPERATOR")
                        .requestMatchers("/api/v1/policies/**").hasRole("POLICY_ADMIN")
                        .requestMatchers("/api/v1/simulation/**").hasRole("SIMULATION_ANALYST")
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
