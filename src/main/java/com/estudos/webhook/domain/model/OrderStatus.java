package com.estudos.webhook.domain.model;

/**
 * Estados de um pedido e as transições permitidas.
 *
 * <pre>
 * PENDING_PAYMENT ──▶ PAID ──▶ REFUNDED
 * </pre>
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    REFUNDED;

    /**
     * Indica se a transição deste estado para {@code target} é permitida.
     */
    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING_PAYMENT -> target == PAID;
            case PAID -> target == REFUNDED;
            case REFUNDED -> false;
        };
    }
}
