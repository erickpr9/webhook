package com.estudos.webhook.application.handler;

import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.PaymentEventType;

import java.time.Clock;

/**
 * {@code payment.refunded}: pagamento estornado (valor integral) e pedido estornado.
 */
public final class RefundPaymentHandler extends PaymentTransitionHandler {

    public RefundPaymentHandler(PaymentRepository payments, OrderRepository orders,
                                NotificationOutbox outbox, Clock clock) {
        super(payments, orders, outbox, clock);
    }

    @Override
    public PaymentEventType supportedType() {
        return PaymentEventType.PAYMENT_REFUNDED;
    }

    @Override
    protected boolean updateOrder(Order order) {
        order.markRefunded();
        return true;
    }
}
