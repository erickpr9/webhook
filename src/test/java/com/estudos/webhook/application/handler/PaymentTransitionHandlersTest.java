package com.estudos.webhook.application.handler;

import com.estudos.webhook.application.dto.HandlingOutcome;
import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.exception.InvalidOrderTransitionException;
import com.estudos.webhook.domain.exception.OrderNotFoundException;
import com.estudos.webhook.domain.exception.PaymentDataMismatchException;
import com.estudos.webhook.domain.exception.PaymentNotFoundException;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.OrderStatus;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentEventType;
import com.estudos.webhook.domain.model.PaymentStatus;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentTransitionHandlersTest {

    private static final Instant OCCURRED_AT = TestData.NOW.minusSeconds(30);

    @Mock
    private PaymentRepository payments;
    @Mock
    private OrderRepository orders;
    @Mock
    private NotificationOutbox outbox;

    private final Clock clock = Clock.fixed(TestData.NOW, ZoneOffset.UTC);

    @Nested
    class Confirm {

        private ConfirmPaymentHandler handler;

        @BeforeEach
        void setUp() {
            handler = new ConfirmPaymentHandler(payments, orders, outbox, clock);
        }

        @Test
        void confirmsPaymentPaysOrderAndEnqueuesNotification() {
            Payment payment = TestData.pendingPayment();
            Order order = TestData.pendingOrder();
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(orders.findById(TestData.ORDER_ID)).thenReturn(Optional.of(order));

            HandlingOutcome outcome = handler.handle(TestData.eventData(), OCCURRED_AT);

            assertThat(outcome.applied()).isTrue();
            assertThat(outcome.note()).isEqualTo("Payment CONFIRMED, order PAID");
            assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            verify(payments).update(payment, TestData.NOW);
            verify(orders).update(order, TestData.NOW);

            ArgumentCaptor<NotificationMessage> message = ArgumentCaptor.forClass(NotificationMessage.class);
            verify(outbox).enqueue(message.capture(), any());
            assertThat(message.getValue()).isEqualTo(new NotificationMessage("PAYMENT_CONFIRMED",
                    TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", OCCURRED_AT));
        }

        @Test
        void supportsConfirmedEvents() {
            assertThat(handler.supportedType()).isEqualTo(PaymentEventType.PAYMENT_CONFIRMED);
        }

        @Test
        void missingPaymentIsReportedForRetry() {
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.handle(TestData.eventData(), OCCURRED_AT))
                    .isInstanceOf(PaymentNotFoundException.class);
            verifyNoInteractions(orders, outbox);
        }

        @Test
        void alreadyConfirmedPaymentIsIgnoredWithoutSideEffects() {
            when(payments.findById(TestData.PAYMENT_ID))
                    .thenReturn(Optional.of(TestData.payment(PaymentStatus.CONFIRMED, OCCURRED_AT.minusSeconds(60))));

            HandlingOutcome outcome = handler.handle(TestData.eventData(), OCCURRED_AT);

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.note()).contains("ALREADY_APPLIED");
            verify(payments, never()).update(any(), any());
            verifyNoInteractions(orders, outbox);
        }

        @Test
        void staleEventIsIgnored() {
            when(payments.findById(TestData.PAYMENT_ID))
                    .thenReturn(Optional.of(TestData.payment(PaymentStatus.REFUSED, OCCURRED_AT.plusSeconds(60))));

            HandlingOutcome outcome = handler.handle(TestData.eventData(), OCCURRED_AT);

            assertThat(outcome.note()).contains("STALE_EVENT");
            verifyNoInteractions(orders, outbox);
        }

        @Test
        void amountMismatchIsRejected() {
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(TestData.pendingPayment()));
            PaymentEventData wrongAmount = new PaymentEventData(TestData.PAYMENT_ID, TestData.ORDER_ID, 1, "BRL");

            assertThatThrownBy(() -> handler.handle(wrongAmount, OCCURRED_AT))
                    .isInstanceOf(PaymentDataMismatchException.class);
            verify(payments, never()).update(any(), any());
        }

        @Test
        void orderMismatchIsRejected() {
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(TestData.pendingPayment()));
            PaymentEventData otherOrder = new PaymentEventData(TestData.PAYMENT_ID, "ord_x", 15990, "BRL");

            assertThatThrownBy(() -> handler.handle(otherOrder, OCCURRED_AT))
                    .isInstanceOf(PaymentDataMismatchException.class);
        }

        @Test
        void invalidCurrencyIsTreatedAsMismatch() {
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(TestData.pendingPayment()));
            PaymentEventData invalid = new PaymentEventData(TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "brl");

            assertThatThrownBy(() -> handler.handle(invalid, OCCURRED_AT))
                    .isInstanceOf(PaymentDataMismatchException.class);
        }

        @Test
        void missingOrderFailsTheTransaction() {
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(TestData.pendingPayment()));
            when(orders.findById(TestData.ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> handler.handle(TestData.eventData(), OCCURRED_AT))
                    .isInstanceOf(OrderNotFoundException.class);
            verifyNoInteractions(outbox);
        }

        @Test
        void orderInUnexpectedStatusFailsTheTransaction() {
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(TestData.pendingPayment()));
            when(orders.findById(TestData.ORDER_ID)).thenReturn(Optional.of(TestData.order(OrderStatus.REFUNDED)));

            assertThatThrownBy(() -> handler.handle(TestData.eventData(), OCCURRED_AT))
                    .isInstanceOf(InvalidOrderTransitionException.class);
            verifyNoInteractions(outbox);
        }
    }

    @Nested
    class Refuse {

        @Test
        void refusesPaymentAndKeepsOrderPending() {
            var handler = new RefusePaymentHandler(payments, orders, outbox, clock);
            Payment payment = TestData.pendingPayment();
            Order order = TestData.pendingOrder();
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(orders.findById(TestData.ORDER_ID)).thenReturn(Optional.of(order));

            HandlingOutcome outcome = handler.handle(TestData.eventData(), OCCURRED_AT);

            assertThat(handler.supportedType()).isEqualTo(PaymentEventType.PAYMENT_REFUSED);
            assertThat(outcome.applied()).isTrue();
            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUSED);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            verify(orders, never()).update(any(), any());
            verify(outbox).enqueue(any(), any());
        }
    }

    @Nested
    class Refund {

        @Test
        void refundsConfirmedPaymentAndOrder() {
            var handler = new RefundPaymentHandler(payments, orders, outbox, clock);
            Payment payment = TestData.payment(PaymentStatus.CONFIRMED, OCCURRED_AT.minusSeconds(60));
            Order order = TestData.order(OrderStatus.PAID);
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(orders.findById(TestData.ORDER_ID)).thenReturn(Optional.of(order));

            HandlingOutcome outcome = handler.handle(TestData.eventData(), OCCURRED_AT);

            assertThat(handler.supportedType()).isEqualTo(PaymentEventType.PAYMENT_REFUNDED);
            assertThat(outcome.applied()).isTrue();
            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
            verify(orders).update(order, TestData.NOW);
        }

        @Test
        void refundOfPendingPaymentIsNotApplicable() {
            var handler = new RefundPaymentHandler(payments, orders, outbox, clock);
            when(payments.findById(TestData.PAYMENT_ID)).thenReturn(Optional.of(TestData.pendingPayment()));

            HandlingOutcome outcome = handler.handle(TestData.eventData(), OCCURRED_AT);

            assertThat(outcome.applied()).isFalse();
            assertThat(outcome.note()).contains("NOT_ALLOWED");
        }
    }
}
