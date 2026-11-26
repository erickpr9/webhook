package com.estudos.webhook.application.dto;

/**
 * Dados para criar um pedido com seu pagamento pendente.
 *
 * @param amountInCents valor em centavos
 * @param currency      moeda ISO 4217
 * @param paymentMethod meio de pagamento (ex.: {@code PIX})
 */
public record CreateOrderCommand(long amountInCents, String currency, String paymentMethod) {
}
