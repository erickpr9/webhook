package com.estudos.webhook.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEventTypeTest {

    @ParameterizedTest
    @CsvSource({
            "payment.confirmed, PAYMENT_CONFIRMED, CONFIRMED",
            "payment.refused,   PAYMENT_REFUSED,   REFUSED",
            "payment.refunded,  PAYMENT_REFUNDED,  REFUNDED"
    })
    void resolvesKnownCodes(String code, PaymentEventType expected, PaymentStatus target) {
        assertThat(PaymentEventType.fromCode(code)).contains(expected);
        assertThat(expected.code()).isEqualTo(code);
        assertThat(expected.targetStatus()).isEqualTo(target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment.created", "PAYMENT.CONFIRMED", "", "refund.created"})
    void unknownCodesResolveToEmpty(String code) {
        assertThat(PaymentEventType.fromCode(code)).isEmpty();
    }
}
