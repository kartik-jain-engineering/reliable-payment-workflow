package com.ledgerflow.common.web;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String CONTEXT_KEY = "correlationId";

    private static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(HEADER_NAME))
                .filter(candidate -> ACCEPTED_ID.matcher(candidate).matches())
                .orElseGet(() -> UUID.randomUUID().toString());

        exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);
        return chain.filter(exchange)
                .contextWrite(context -> context.put(CONTEXT_KEY, correlationId));
    }
}
