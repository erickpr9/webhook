package com.estudos.webhook.application.dto;

import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.Payment;

import java.time.Instant;
import java.util.List;

/**
 * Visão de leitura de um pedido e seus pagamentos.
 */
public record OrderView(String orderId, String status, long totalAmountInCents, String currency,
                        List<PaymentView> payments) {

    public static OrderView from(Order order, List<Payment> payments) {
        return new OrderView(order.id(), order.status().name(), order.totalAmount().amountInCents(),
                order.totalAmount().currency(), payments.stream().map(PaymentView::from).toList());
    }

    /**
     * Visão de leitura de um pagamento.
     */
    public record PaymentView(String paymentId, String status, long amountInCents, String currency,
                              String method, Instant lastEventAt) {

        static PaymentView from(Payment payment) {
            return new PaymentView(payment.id(), payment.status().name(), payment.amount().amountInCents(),
                    payment.amount().currency(), payment.method().name(), payment.lastEventAt().orElse(null));
        }
    }
}
