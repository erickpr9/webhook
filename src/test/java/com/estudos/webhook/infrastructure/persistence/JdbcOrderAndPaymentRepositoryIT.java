package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.exception.ConcurrentUpdateException;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.OrderStatus;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentEventType;
import com.estudos.webhook.domain.model.PaymentMethod;
import com.estudos.webhook.domain.model.PaymentStatus;
import com.estudos.webhook.support.IntegrationTest;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcOrderAndPaymentRepositoryIT extends IntegrationTest {

    private static final Instant NOW = TestData.NOW;

    @Autowired
    private JdbcOrderRepository orders;
    @Autowired
    private JdbcPaymentRepository payments;

    @BeforeEach
    void insertOrderAndPayment() {
        orders.insert(TestData.pendingOrder(), NOW);
        payments.insert(TestData.pendingPayment(), NOW);
    }

    @Test
    void roundTripsOrder() {
        Order order = orders.findById(TestData.ORDER_ID).orElseThrow();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.totalAmount()).isEqualTo(TestData.AMOUNT);
        assertThat(order.version()).isZero();
        assertThat(orders.findById("ord_missing")).isEmpty();
    }

    @Test
    void roundTripsPayment() {
        Payment payment = payments.findById(TestData.PAYMENT_ID).orElseThrow();

        assertThat(payment.orderId()).isEqualTo(TestData.ORDER_ID);
        assertThat(payment.amount()).isEqualTo(TestData.AMOUNT);
        assertThat(payment.method()).isEqualTo(PaymentMethod.PIX);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.lastEventAt()).isEmpty();
        assertThat(payments.findByOrderId(TestData.ORDER_ID)).hasSize(1);
        assertThat(payments.findById("pay_missing")).isEmpty();
    }

    @Test
    void updateIncrementsVersionAndPersistsState() {
        Payment payment = payments.findById(TestData.PAYMENT_ID).orElseThrow();
        payment.apply(PaymentEventType.PAYMENT_CONFIRMED, NOW.minusSeconds(1));
        payments.update(payment, NOW);

        Order order = orders.findById(TestData.ORDER_ID).orElseThrow();
        order.markPaid();
        orders.update(order, NOW);

        Payment storedPayment = payments.findById(TestData.PAYMENT_ID).orElseThrow();
        assertThat(storedPayment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(storedPayment.lastEventAt()).contains(NOW.minusSeconds(1));
        assertThat(storedPayment.version()).isEqualTo(1);
        assertThat(orders.findById(TestData.ORDER_ID).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void staleVersionIsRejected() {
        Payment first = payments.findById(TestData.PAYMENT_ID).orElseThrow();
        Payment second = payments.findById(TestData.PAYMENT_ID).orElseThrow();
        first.apply(PaymentEventType.PAYMENT_CONFIRMED, NOW);
        second.apply(PaymentEventType.PAYMENT_REFUSED, NOW);

        payments.update(first, NOW);

        assertThatThrownBy(() -> payments.update(second, NOW)).isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    void staleOrderVersionIsRejected() {
        Order first = orders.findById(TestData.ORDER_ID).orElseThrow();
        Order second = orders.findById(TestData.ORDER_ID).orElseThrow();
        first.markPaid();
        second.markPaid();

        orders.update(first, NOW);

        assertThatThrownBy(() -> orders.update(second, NOW)).isInstanceOf(ConcurrentUpdateException.class);
    }

    @Test
    void paymentRequiresExistingOrder() {
        Payment orphan = Payment.newPending("pay_orphan", "ord_missing", new Money(100, "BRL"), PaymentMethod.PIX);

        assertThatThrownBy(() -> payments.insert(orphan, NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
