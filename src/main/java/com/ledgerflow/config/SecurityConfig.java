package com.ledgerflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/orders").hasAuthority("SCOPE_order:create")
                        .pathMatchers(HttpMethod.GET, "/api/v1/orders/{orderId}").hasAuthority("SCOPE_order:read")
                        .pathMatchers(HttpMethod.POST, "/api/v1/orders/{orderId}/cancel")
                        .hasAuthority("SCOPE_order:cancel")
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments").hasAuthority("SCOPE_payment:process")
                        .pathMatchers(HttpMethod.GET, "/api/v1/payments/{paymentId}")
                        .hasAuthority("SCOPE_payment:read")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
