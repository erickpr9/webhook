package com.estudos.webhook.domain.model;

import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PaymentTest {

    private static final Instant T1 = Instant.parse("2026-11-26T10:00:00Z");
    private static final Instant T2 = T1.plusSeconds(60);

    @Test
    void newPaymentStartsPendingWithoutEvents() {
        Payment payment = TestData.pendingPayment();

        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.lastEventAt()).isEmpty();
        assertThat(payment.version()).isZero();
    }

    @Test
    void confirmationChangesStatusAndRecordsEventTime() {
        Payment payment = TestData.pendingPayment();

        TransitionResult result = payment.apply(PaymentEventType.PAYMENT_CONFIRMED, T1);

        assertThat(result).isEqualTo(TransitionResult.APPLIED);
        assertThat(result.applied()).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.lastEventAt()).contains(T1);
    }

    @Test
    void refundAfterConfirmationIsApplied() {
        Payment payment = TestData.payment(PaymentStatus.CONFIRMED, T1);

        assertThat(payment.apply(PaymentEventType.PAYMENT_REFUNDED, T2)).isEqualTo(TransitionResult.APPLIED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.lastEventAt()).contains(T2);
    }

    @Test
    void redeliveryOfSameEffectIsAlreadyApplied() {
        Payment payment = TestData.payment(PaymentStatus.CONFIRMED, T1);

        assertThat(payment.apply(PaymentEventType.PAYMENT_CONFIRMED, T2)).isEqualTo(TransitionResult.ALREADY_APPLIED);
        assertThat(payment.lastEventAt()).contains(T1);
    }

    @Test
    void refusalAfterConfirmationIsNotAllowed() {
        Payment payment = TestData.payment(PaymentStatus.CONFIRMED, T1);

        TransitionResult result = payment.apply(PaymentEventType.PAYMENT_REFUSED, T2);

        assertThat(result).isEqualTo(TransitionResult.NOT_ALLOWED);
        assertThat(result.applied()).isFalse();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void eventOlderThanLastAppliedIsStale() {
        Payment payment = TestData.payment(PaymentStatus.CONFIRMED, T2);

        assertThat(payment.apply(PaymentEventType.PAYMENT_REFUNDED, T1)).isEqualTo(TransitionResult.STALE_EVENT);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void eventAtSameInstantAsLastIsNotStale() {
        Payment payment = TestData.payment(PaymentStatus.CONFIRMED, T1);

        assertThat(payment.apply(PaymentEventType.PAYMENT_REFUNDED, T1)).isEqualTo(TransitionResult.APPLIED);
    }

    @Test
    void matchesComparesOrderAndAmount() {
        Payment payment = TestData.pendingPayment();

        assertThat(payment.matches(TestData.ORDER_ID, TestData.AMOUNT)).isTrue();
        assertThat(payment.matches("ord_other", TestData.AMOUNT)).isFalse();
        assertThat(payment.matches(TestData.ORDER_ID, new Money(1, "BRL"))).isFalse();
        assertThat(payment.matches(TestData.ORDER_ID, new Money(15990, "USD"))).isFalse();
    }

    @Test
    void applyRequiresArguments() {
        Payment payment = TestData.pendingPayment();

        assertThatNullPointerException().isThrownBy(() -> payment.apply(null, T1));
        assertThatNullPointerException().isThrownBy(() -> payment.apply(PaymentEventType.PAYMENT_CONFIRMED, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsBlankIdentifiers(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Payment.newPending(blank, "ord", TestData.AMOUNT, PaymentMethod.PIX));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Payment.newPending("pay", blank, TestData.AMOUNT, PaymentMethod.PIX));
    }

    @Test
    void restoreKeepsPersistedState() {
        Payment payment = Payment.restore("pay", "ord", TestData.AMOUNT, PaymentMethod.BOLETO,
                PaymentStatus.REFUSED, T1, 7);

        assertThat(payment.id()).isEqualTo("pay");
        assertThat(payment.orderId()).isEqualTo("ord");
        assertThat(payment.amount()).isEqualTo(TestData.AMOUNT);
        assertThat(payment.method()).isEqualTo(PaymentMethod.BOLETO);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUSED);
        assertThat(payment.version()).isEqualTo(7);
    }
}
