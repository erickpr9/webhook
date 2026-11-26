package com.estudos.webhook.application.dto;

/**
 * Dados de pagamento extraídos do payload do evento.
 *
 * @param paymentId     identificador do pagamento no provedor
 * @param orderId       pedido ao qual o pagamento pertence
 * @param amountInCents valor em centavos
 * @param currency      moeda ISO 4217
 */
public record PaymentEventData(String paymentId, String orderId, long amountInCents, String currency) {
}
