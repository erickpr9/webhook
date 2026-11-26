package com.estudos.webhook.application.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Webhook autenticado, publicado na esteira para ser processado de forma assíncrona.
 *
 * <p>É a mensagem que trafega no tópico de eventos: o payload vai como está, e os demais campos
 * como metadados (no Kafka, headers da mensagem).
 *
 * @param provider        provedor de origem
 * @param providerEventId identificador do evento no provedor (chave de idempotência)
 * @param eventType       tipo do evento, mesmo que desconhecido
 * @param occurredAt      instante em que o evento ocorreu no provedor
 * @param receivedAt      instante em que a plataforma recebeu o webhook
 * @param partitionKey    chave de ordenação: eventos com a mesma chave são processados em ordem
 *                        (o pedido, quando o payload informa; senão, o próprio evento)
 * @param payload         corpo JSON original
 */
public record ReceivedWebhookEvent(
        String provider,
        String providerEventId,
        String eventType,
        Instant occurredAt,
        Instant receivedAt,
        String partitionKey,
        String payload) {

    public ReceivedWebhookEvent {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerEventId, "providerEventId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(partitionKey, "partitionKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
