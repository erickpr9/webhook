package com.estudos.webhook.application.dto;

import java.time.Instant;

/**
 * Notificação a ser entregue ao cliente sobre uma mudança no pagamento.
 *
 * @param type          tipo da notificação (ex.: {@code PAYMENT_CONFIRMED})
 * @param paymentId     pagamento afetado
 * @param orderId       pedido afetado
 * @param amountInCents valor em centavos
 * @param currency      moeda ISO 4217
 * @param paymentStatus estado do pagamento após o evento
 * @param occurredAt    instante do evento no provedor
 */
public record NotificationMessage(
        String type,
        String paymentId,
        String orderId,
        long amountInCents,
        String currency,
        String paymentStatus,
        Instant occurredAt) {
}
