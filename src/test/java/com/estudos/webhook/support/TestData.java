package com.estudos.webhook.support;

import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.OrderStatus;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentMethod;
import com.estudos.webhook.domain.model.PaymentStatus;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Dados de teste reutilizáveis.
 */
public final class TestData {

    public static final Instant NOW = Instant.parse("2026-11-26T14:00:00Z");
    public static final String ORDER_ID = "ord_1042";
    public static final String PAYMENT_ID = "pay_001";
    public static final Money AMOUNT = new Money(15990, "BRL");

    private TestData() {
    }

    public static Order pendingOrder() {
        return Order.create(ORDER_ID, AMOUNT);
    }

    public static Order order(OrderStatus status) {
        return Order.restore(ORDER_ID, AMOUNT, status, 0);
    }

    public static Payment pendingPayment() {
        return Payment.newPending(PAYMENT_ID, ORDER_ID, AMOUNT, PaymentMethod.PIX);
    }

    public static Payment payment(PaymentStatus status, Instant lastEventAt) {
        return Payment.restore(PAYMENT_ID, ORDER_ID, AMOUNT, PaymentMethod.PIX, status, lastEventAt, 0);
    }

    public static PaymentEventData eventData() {
        return new PaymentEventData(PAYMENT_ID, ORDER_ID, AMOUNT.amountInCents(), AMOUNT.currency());
    }

    public static String payload(String eventId, String type, Instant createdAt) {
        return payload(eventId, type, createdAt, PAYMENT_ID, ORDER_ID, AMOUNT.amountInCents());
    }

    public static String payload(String eventId, String type, Instant createdAt, String paymentId, String orderId,
                                 long amount) {
        return """
                {"id":"%s","type":"%s","created_at":"%s","data":{"payment_id":"%s","order_id":"%s","amount":%d,"currency":"BRL","method":"PIX"}}"""
                .formatted(eventId, type, createdAt, paymentId, orderId, amount);
    }

    /** Evento reservado para processamento (status PROCESSING, tentativa {@code attempts}). */
    public static WebhookEvent processingEvent(String type, int attempts) {
        return new WebhookEvent(UUID.randomUUID(), "payment-gateway", "evt_" + UUID.randomUUID(), type,
                payload("evt_x", type, NOW), WebhookEventStatus.PROCESSING, attempts, NOW, NOW, NOW,
                null, null, null);
    }
}
