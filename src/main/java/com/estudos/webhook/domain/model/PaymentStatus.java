package com.estudos.webhook.domain.model;

/**
 * Estados de um pagamento e as transições permitidas entre eles.
 *
 * <pre>
 * PENDING ──confirmed──▶ CONFIRMED ──refunded──▶ REFUNDED
 *    │
 *    └────refused─────▶ REFUSED
 * </pre>
 */
public enum PaymentStatus {
    PENDING,
    CONFIRMED,
    REFUSED,
    REFUNDED;

    /**
     * Indica se a transição deste estado para {@code target} é permitida.
     */
    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case PENDING -> target == CONFIRMED || target == REFUSED;
            case CONFIRMED -> target == REFUNDED;
            case REFUSED, REFUNDED -> false;
        };
    }
}
