package com.estudos.webhook.api.response;

import com.estudos.webhook.application.dto.WebhookEventView;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento da inbox na API administrativa. O payload não é exposto.
 */
public record WebhookEventResponse(
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

    public static WebhookEventResponse from(WebhookEventView view) {
        return new WebhookEventResponse(view.id(), view.provider(), view.providerEventId(), view.eventType(),
                view.status(), view.attempts(), view.occurredAt(), view.receivedAt(), view.nextAttemptAt(),
                view.processedAt(), view.processingNote(), view.lastError());
    }
}
