package com.estudos.webhook.domain.model;

import com.estudos.webhook.domain.exception.InvalidOrderTransitionException;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void newOrderAwaitsPayment() {
        Order order = TestData.pendingOrder();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.totalAmount()).isEqualTo(TestData.AMOUNT);
        assertThat(order.version()).isZero();
    }

    @Test
    void paidThenRefunded() {
        Order order = TestData.pendingOrder();

        order.markPaid();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);

        order.markRefunded();
        assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void cannotRefundUnpaidOrder() {
        Order order = TestData.pendingOrder();

        assertThatThrownBy(order::markRefunded)
                .isInstanceOf(InvalidOrderTransitionException.class)
                .hasMessageContaining("PENDING_PAYMENT to REFUNDED");
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void cannotPayTwice() {
        Order order = TestData.order(OrderStatus.PAID);

        assertThatThrownBy(order::markPaid).isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void rejectsBlankId() {
        assertThatIllegalArgumentException().isThrownBy(() -> Order.create(" ", TestData.AMOUNT));
    }
}
