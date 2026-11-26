package com.estudos.webhook.application.dto;

/**
 * Identificadores do pedido e do pagamento recém-criados.
 */
public record CreatedOrder(String orderId, String paymentId) {
}
