package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.CreateOrderCommand;
import com.estudos.webhook.application.dto.CreatedOrder;
import com.estudos.webhook.application.dto.OrderView;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.exception.OrderNotFoundException;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.OrderStatus;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentMethod;
import com.estudos.webhook.domain.model.PaymentStatus;
import com.estudos.webhook.support.DirectTransactionRunner;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderUseCasesTest {

    @Mock
    private OrderRepository orders;
    @Mock
    private PaymentRepository payments;

    @Test
    void createsOrderWithPendingPaymentInOneTransaction() {
        var transactions = new DirectTransactionRunner();
        var useCase = new CreateOrderUseCase(orders, payments, transactions, Clock.fixed(TestData.NOW, ZoneOffset.UTC));

        CreatedOrder created = useCase.execute(new CreateOrderCommand(4990, "BRL", "PIX"));

        assertThat(created.orderId()).startsWith("ord_");
        assertThat(created.paymentId()).startsWith("pay_");
        assertThat(transactions.executions()).isEqualTo(1);

        ArgumentCaptor<Order> order = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(orders).insert(order.capture(), org.mockito.ArgumentMatchers.eq(TestData.NOW));
        verify(payments).insert(payment.capture(), org.mockito.ArgumentMatchers.eq(TestData.NOW));
        assertThat(order.getValue().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(payment.getValue().orderId()).isEqualTo(created.orderId());
        assertThat(payment.getValue().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getValue().method()).isEqualTo(PaymentMethod.PIX);
    }

    @Test
    void rejectsInvalidInputBeforeTouchingTheDatabase() {
        var useCase = new CreateOrderUseCase(orders, payments, new DirectTransactionRunner(), Clock.systemUTC());

        assertThatIllegalArgumentException().isThrownBy(() -> useCase.execute(new CreateOrderCommand(0, "BRL", "PIX")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> useCase.execute(new CreateOrderCommand(100, "BRL", "CASH")));
        verifyNoInteractions(orders, payments);
    }

    @Test
    void getsOrderWithPayments() {
        when(orders.findById(TestData.ORDER_ID)).thenReturn(Optional.of(TestData.order(OrderStatus.PAID)));
        when(payments.findByOrderId(TestData.ORDER_ID))
                .thenReturn(List.of(TestData.payment(PaymentStatus.CONFIRMED, TestData.NOW)));

        OrderView view = new GetOrderUseCase(orders, payments).execute(TestData.ORDER_ID);

        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.totalAmountInCents()).isEqualTo(15990);
        assertThat(view.payments()).singleElement().satisfies(payment -> {
            assertThat(payment.status()).isEqualTo("CONFIRMED");
            assertThat(payment.lastEventAt()).isEqualTo(TestData.NOW);
        });
    }

    @Test
    void unknownOrderIsNotFound() {
        when(orders.findById("ord_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new GetOrderUseCase(orders, payments).execute("ord_x"))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
