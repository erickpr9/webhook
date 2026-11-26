package com.estudos.webhook.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento recebido do provedor e guardado na inbox antes de qualquer processamento.
 *
 * @param id              identificador interno
 * @param provider        provedor de origem
 * @param providerEventId identificador do evento no provedor (chave de idempotência)
 * @param eventType       tipo informado pelo provedor, mesmo que desconhecido
 * @param payload         corpo JSON recebido
 * @param status          estado atual na inbox
 * @param attempts        tentativas de processamento já iniciadas
 * @param occurredAt      instante em que o evento ocorreu no provedor
 * @param receivedAt      instante em que o evento chegou
 * @param nextAttemptAt   instante a partir do qual o evento pode ser processado
 * @param processedAt     instante em que o processamento terminou, se terminou
 * @param processingNote  observação do processamento (ex.: transição ignorada)
 * @param lastError       último erro de processamento, se houve
 */
public record WebhookEvent(
        UUID id,
        String provider,
        String providerEventId,
        String eventType,
        String payload,
        WebhookEventStatus status,
        int attempts,
        Instant occurredAt,
        Instant receivedAt,
        Instant nextAttemptAt,
        Instant processedAt,
        String processingNote,
        String lastError) {

    public WebhookEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerEventId, "providerEventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    /** Cria um evento recém-chegado, pronto para ser processado imediatamente. */
    public static WebhookEvent received(UUID id, String provider, String providerEventId, String eventType,
                                        String payload, Instant occurredAt, Instant receivedAt) {
        return new WebhookEvent(id, provider, providerEventId, eventType, payload, WebhookEventStatus.RECEIVED,
                0, occurredAt, receivedAt, receivedAt, null, null, null);
    }
}
