package com.estudos.webhook.domain.exception;

import com.estudos.webhook.domain.model.OrderStatus;

/**
 * Tentativa de mudar o pedido para um estado não permitido a partir do estado atual.
 */
public class InvalidOrderTransitionException extends BusinessException {

    public InvalidOrderTransitionException(String orderId, OrderStatus from, OrderStatus to) {
        super("Order '" + orderId + "' cannot transition from " + from + " to " + to);
    }
}
