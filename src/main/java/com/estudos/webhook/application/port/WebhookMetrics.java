package com.estudos.webhook.application.port;

import com.estudos.webhook.domain.model.WebhookEventStatus;

import java.time.Duration;

/**
 * Porta de métricas da esteira de webhooks.
 */
public interface WebhookMetrics {

    /** Evento recebido pela API; {@code outcome} é {@code accepted} ou {@code duplicate}. */
    void eventReceived(String eventType, String outcome);

    /** Processamento concluído com {@code status} PROCESSED ou IGNORED. */
    void eventCompleted(String eventType, WebhookEventStatus status);

    void eventRetryScheduled(String eventType);

    void eventDead(String eventType);

    void processingTime(String eventType, Duration duration);

    void notificationSent(String notificationType);

    void notificationFailed(String notificationType, boolean dead);
}
