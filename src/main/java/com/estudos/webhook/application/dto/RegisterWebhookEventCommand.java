package com.estudos.webhook.application.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Dados de um webhook já autenticado, prontos para entrar na esteira.
 *
 * @param provider        provedor de origem
 * @param providerEventId identificador do evento no provedor
 * @param eventType       tipo do evento
 * @param occurredAt      instante em que o evento ocorreu no provedor
 * @param partitionKey    chave de ordenação (o pedido, quando o payload informa; senão, o evento)
 * @param payload         corpo JSON original
 */
public record RegisterWebhookEventCommand(
        String provider,
        String providerEventId,
        String eventType,
        Instant occurredAt,
        String partitionKey,
        String payload) {

    public RegisterWebhookEventCommand {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerEventId, "providerEventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(partitionKey, "partitionKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
