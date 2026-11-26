package com.estudos.webhook.infrastructure.metrics;

import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.model.PaymentEventType;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas da esteira no Micrometer (expostas em {@code /actuator/prometheus}).
 *
 * <p>Tipos de evento desconhecidos viram {@code unknown} na tag, para evitar explosão de
 * cardinalidade caso o provedor envie tipos arbitrários.
 */
@Component
public class MicrometerWebhookMetrics implements WebhookMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public MicrometerWebhookMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void eventReceived(String eventType, String outcome) {
        registry.counter("webhook.received", "type", normalize(eventType), "outcome", outcome).increment();
    }

    @Override
    public void eventCompleted(String eventType, WebhookEventStatus status) {
        registry.counter("webhook.processed", "type", normalize(eventType), "status", status.name()).increment();
    }

    @Override
    public void eventRetryScheduled(String eventType) {
        registry.counter("webhook.failed", "type", normalize(eventType), "outcome", "retry").increment();
    }

    @Override
    public void eventDead(String eventType) {
        registry.counter("webhook.failed", "type", normalize(eventType), "outcome", "dead").increment();
        registry.counter("webhook.dead", "type", normalize(eventType)).increment();
    }

    @Override
    public void processingTime(String eventType, Duration duration) {
        Timer.builder("webhook.processing.time")
                .tag("type", normalize(eventType))
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    @Override
    public void notificationSent(String notificationType) {
        registry.counter("webhook.notification.sent", "type", notificationType).increment();
    }

    @Override
    public void notificationFailed(String notificationType, boolean dead) {
        registry.counter("webhook.notification.failed", "type", notificationType,
                "outcome", dead ? "dead" : "retry").increment();
    }

    private static String normalize(String eventType) {
        return PaymentEventType.fromCode(eventType).map(PaymentEventType::code).orElse(UNKNOWN);
    }
}
