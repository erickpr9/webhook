package com.estudos.webhook.infrastructure.metrics;

import com.estudos.webhook.domain.model.WebhookEventStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerWebhookMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerWebhookMetrics metrics = new MicrometerWebhookMetrics(registry);

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    void countsLifecycleEvents() {
        metrics.eventReceived("payment.confirmed", "accepted");
        metrics.eventReceived("payment.confirmed", "accepted");
        metrics.eventCompleted("payment.confirmed", WebhookEventStatus.PROCESSED);
        metrics.eventRetryScheduled("payment.refused");
        metrics.eventDead("payment.refunded");
        metrics.notificationSent("PAYMENT_CONFIRMED");
        metrics.notificationFailed("PAYMENT_CONFIRMED", true);

        assertThat(count("webhook.received", "type", "payment.confirmed", "outcome", "accepted")).isEqualTo(2);
        assertThat(count("webhook.processed", "type", "payment.confirmed", "status", "PROCESSED")).isEqualTo(1);
        assertThat(count("webhook.failed", "type", "payment.refused", "outcome", "retry")).isEqualTo(1);
        assertThat(count("webhook.dead", "type", "payment.refunded")).isEqualTo(1);
        assertThat(count("webhook.notification.sent", "type", "PAYMENT_CONFIRMED")).isEqualTo(1);
        assertThat(count("webhook.notification.failed", "type", "PAYMENT_CONFIRMED", "outcome", "dead")).isEqualTo(1);
    }

    @Test
    void unknownEventTypesShareOneTagValue() {
        metrics.eventReceived("anything.goes", "accepted");
        metrics.eventReceived("whatever.else", "accepted");

        assertThat(count("webhook.received", "type", "unknown", "outcome", "accepted")).isEqualTo(2);
    }

    @Test
    void recordsProcessingTime() {
        metrics.processingTime("payment.confirmed", Duration.ofMillis(25));

        assertThat(registry.get("webhook.processing.time").timer().count()).isEqualTo(1);
    }
}
