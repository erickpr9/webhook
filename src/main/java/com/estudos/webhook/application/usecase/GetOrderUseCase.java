package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.OrderView;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.exception.OrderNotFoundException;
import com.estudos.webhook.domain.model.Order;

import java.util.Objects;

/**
 * Consulta um pedido e seus pagamentos.
 */
public final class GetOrderUseCase {

    private final OrderRepository orders;
    private final PaymentRepository payments;

    public GetOrderUseCase(OrderRepository orders, PaymentRepository payments) {
        this.orders = Objects.requireNonNull(orders);
        this.payments = Objects.requireNonNull(payments);
    }

    public OrderView execute(String orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderView.from(order, payments.findByOrderId(orderId));
    }
}
