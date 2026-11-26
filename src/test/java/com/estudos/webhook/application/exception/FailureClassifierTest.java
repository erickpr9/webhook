package com.estudos.webhook.application.exception;

import com.estudos.webhook.domain.exception.InvalidOrderTransitionException;
import com.estudos.webhook.domain.exception.PaymentNotFoundException;
import com.estudos.webhook.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void permanentFailures() {
        assertThat(FailureClassifier.isRetryable(new MalformedPayloadException("bad"))).isFalse();
        assertThat(FailureClassifier.isRetryable(new PermanentDeliveryException("401"))).isFalse();
        assertThat(FailureClassifier.isRetryable(
                new InvalidOrderTransitionException("ord", OrderStatus.PAID, OrderStatus.PAID))).isFalse();
    }

    @Test
    void transientFailures() {
        assertThat(FailureClassifier.isRetryable(new PaymentNotFoundException("pay"))).isTrue();
        assertThat(FailureClassifier.isRetryable(new ConcurrentUpdateException("Payment", "pay"))).isTrue();
        assertThat(FailureClassifier.isRetryable(new IllegalStateException("db down"))).isTrue();
    }

    @Test
    void describeIsTruncated() {
        String description = FailureClassifier.describe(new IllegalStateException("x".repeat(5000)));

        assertThat(description).startsWith("IllegalStateException: ").hasSize(2000);
    }
}
