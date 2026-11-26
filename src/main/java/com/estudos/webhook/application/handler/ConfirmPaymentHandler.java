package com.estudos.webhook.application.handler;

import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.PaymentEventType;

import java.time.Clock;

/**
 * {@code payment.confirmed}: pagamento confirmado e pedido pago.
 */
public final class ConfirmPaymentHandler extends PaymentTransitionHandler {

    public ConfirmPaymentHandler(PaymentRepository payments, OrderRepository orders,
                                 NotificationOutbox outbox, Clock clock) {
        super(payments, orders, outbox, clock);
    }

    @Override
    public PaymentEventType supportedType() {
        return PaymentEventType.PAYMENT_CONFIRMED;
    }

    @Override
    protected boolean updateOrder(Order order) {
        order.markPaid();
        return true;
    }
}
