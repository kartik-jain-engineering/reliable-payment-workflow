package com.ledgerflow.common.config;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ledgerflow.common.web.CorrelationIdWebFilter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Reactor Context -> MDC bridge {@link ContextPropagationConfig}
 * sets up actually carries a correlation id into a log line emitted after a
 * scheduler hop — the exact failure mode a reactive MDC bridge is prone to
 * (looks correct, silently breaks once logging happens off the subscribing
 * thread). Exercises the same {@link CorrelationIdWebFilter#CONTEXT_KEY}
 * production classes rather than a stand-in, without needing a full Spring
 * context or a real HTTP request.
 */
class ContextPropagationConfigTest {

    private static final Logger LOG = LoggerFactory.getLogger(ContextPropagationConfigTest.class);

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        new ContextPropagationConfig();
        appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LOG).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((ch.qos.logback.classic.Logger) LOG).detachAppender(appender);
    }

    @Test
    void logLine_shouldCarryCorrelationId_afterASchedulerHop() {
        String correlationId = "test-correlation-42";

        Mono.fromRunnable(() -> LOG.info("log line inside the reactive chain"))
                .subscribeOn(Schedulers.boundedElastic())
                .contextWrite(context -> context.put(CorrelationIdWebFilter.CONTEXT_KEY, correlationId))
                .block();

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMDCPropertyMap())
                .as("MDC must carry the Reactor Context's correlationId even though logging happened on a "
                        + "boundedElastic thread, not the subscribing thread")
                .containsEntry(CorrelationIdWebFilter.CONTEXT_KEY, correlationId);
    }

    @Test
    void mdc_shouldBeEmpty_whenNoCorrelationIdIsInTheReactorContext() throws InterruptedException {
        Mono.fromRunnable(() -> LOG.info("log line with no correlation id in context"))
                .subscribeOn(Schedulers.boundedElastic())
                .block();

        TimeUnit.MILLISECONDS.sleep(50);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getMDCPropertyMap()).doesNotContainKey(CorrelationIdWebFilter.CONTEXT_KEY);
    }
}
