package com.ledgerflow.common.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

import com.ledgerflow.common.web.CorrelationIdWebFilter;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

@Configuration(proxyBeanMethods = false)
public class ContextPropagationConfig {

    private static final String KEY = CorrelationIdWebFilter.CONTEXT_KEY;

    public ContextPropagationConfig() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
                KEY,
                () -> MDC.get(KEY),
                value -> MDC.put(KEY, value),
                () -> MDC.remove(KEY));
        Hooks.enableAutomaticContextPropagation();
    }
}
