package com.estudos.webhook.domain.exception;

/**
 * Pedido não encontrado.
 */
public class OrderNotFoundException extends ResourceNotFoundException {

    public OrderNotFoundException(String orderId) {
        super("Order '" + orderId + "' not found");
    }
}
