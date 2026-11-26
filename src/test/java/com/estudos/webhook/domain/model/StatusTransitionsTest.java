package com.estudos.webhook.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matriz completa das transições de pagamento e pedido.
 */
class StatusTransitionsTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
            "PENDING,   CONFIRMED, true",
            "PENDING,   REFUSED,   true",
            "PENDING,   REFUNDED,  false",
            "PENDING,   PENDING,   false",
            "CONFIRMED, REFUNDED,  true",
            "CONFIRMED, REFUSED,   false",
            "CONFIRMED, PENDING,   false",
            "CONFIRMED, CONFIRMED, false",
            "REFUSED,   CONFIRMED, false",
            "REFUSED,   REFUNDED,  false",
            "REFUSED,   PENDING,   false",
            "REFUNDED,  CONFIRMED, false",
            "REFUNDED,  REFUSED,   false",
            "REFUNDED,  PENDING,   false"
    })
    void paymentTransitions(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @CsvSource({
            "PENDING_PAYMENT, PAID,            true",
            "PENDING_PAYMENT, REFUNDED,        false",
            "PENDING_PAYMENT, PENDING_PAYMENT, false",
            "PAID,            REFUNDED,        true",
            "PAID,            PENDING_PAYMENT, false",
            "PAID,            PAID,            false",
            "REFUNDED,        PAID,            false",
            "REFUNDED,        PENDING_PAYMENT, false"
    })
    void orderTransitions(OrderStatus from, OrderStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }
}
