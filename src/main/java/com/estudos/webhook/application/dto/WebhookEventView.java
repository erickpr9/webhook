package com.estudos.webhook.application.dto;

import com.estudos.webhook.domain.model.WebhookEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Visão de leitura de um evento da inbox, para consultas administrativas.
 */
public record WebhookEventView(
        UUID id,
        String provider,
        String providerEventId,
        String eventType,
        String status,
        int attempts,
        Instant occurredAt,
        Instant receivedAt,
        Instant nextAttemptAt,
        Instant processedAt,
        String processingNote,
        String lastError) {

    public static WebhookEventView from(WebhookEvent event) {
        return new WebhookEventView(event.id(), event.provider(), event.providerEventId(), event.eventType(),
                event.status().name(), event.attempts(), event.occurredAt(), event.receivedAt(),
                event.nextAttemptAt(), event.processedAt(), event.processingNote(), event.lastError());
    }
}
