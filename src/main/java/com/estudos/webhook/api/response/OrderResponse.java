package com.estudos.webhook.api.response;

import com.estudos.webhook.application.dto.OrderView;

import java.time.Instant;
import java.util.List;

/**
 * Pedido e seus pagamentos.
 */
public record OrderResponse(String orderId, String status, long totalAmountInCents, String currency,
                            List<PaymentResponse> payments) {

    public static OrderResponse from(OrderView view) {
        return new OrderResponse(view.orderId(), view.status(), view.totalAmountInCents(), view.currency(),
                view.payments().stream().map(PaymentResponse::from).toList());
    }

    /**
     * Pagamento de um pedido.
     */
    public record PaymentResponse(String paymentId, String status, long amountInCents, String currency,
                                  String method, Instant lastEventAt) {

        static PaymentResponse from(OrderView.PaymentView view) {
            return new PaymentResponse(view.paymentId(), view.status(), view.amountInCents(), view.currency(),
                    view.method(), view.lastEventAt());
        }
    }
}
