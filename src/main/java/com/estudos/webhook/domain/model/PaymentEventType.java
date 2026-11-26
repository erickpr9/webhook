package com.estudos.webhook.domain.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de evento de pagamento suportados e o estado que cada um produz no pagamento.
 */
public enum PaymentEventType {
    PAYMENT_CONFIRMED("payment.confirmed", PaymentStatus.CONFIRMED),
    PAYMENT_REFUSED("payment.refused", PaymentStatus.REFUSED),
    PAYMENT_REFUNDED("payment.refunded", PaymentStatus.REFUNDED);

    private final String code;
    private final PaymentStatus targetStatus;

    PaymentEventType(String code, PaymentStatus targetStatus) {
        this.code = code;
        this.targetStatus = targetStatus;
    }

    /** Código do evento no contrato do provedor (ex.: {@code payment.confirmed}). */
    public String code() {
        return code;
    }

    /** Estado em que o pagamento fica quando o evento é aplicado. */
    public PaymentStatus targetStatus() {
        return targetStatus;
    }

    /**
     * Resolve o tipo pelo código do provedor. Retorna vazio para tipos desconhecidos, que
     * devem ser ignorados sem erro (o provedor pode criar novos tipos a qualquer momento).
     */
    public static Optional<PaymentEventType> fromCode(String code) {
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst();
    }
}
