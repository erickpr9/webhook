package com.estudos.webhook.application.handler;

import com.estudos.webhook.application.dto.HandlingOutcome;
import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.exception.OrderNotFoundException;
import com.estudos.webhook.domain.exception.PaymentDataMismatchException;
import com.estudos.webhook.domain.exception.PaymentNotFoundException;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.OrderStatus;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentStatus;
import com.estudos.webhook.domain.model.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Fluxo comum dos eventos que mudam o estado do pagamento (Template Method):
 * <ol>
 *   <li>carrega o pagamento (se ainda não existir, falha de forma transitória para nova tentativa);</li>
 *   <li>confere pedido e valor com o que o provedor enviou;</li>
 *   <li>aplica a transição; se não for aplicável, encerra sem alterar nada;</li>
 *   <li>atualiza o pedido conforme o tipo de evento;</li>
 *   <li>grava a notificação no outbox, na mesma transação.</li>
 * </ol>
 */
public abstract class PaymentTransitionHandler implements PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransitionHandler.class);

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final NotificationOutbox outbox;
    private final Clock clock;

    protected PaymentTransitionHandler(PaymentRepository payments, OrderRepository orders,
                                       NotificationOutbox outbox, Clock clock) {
        this.payments = Objects.requireNonNull(payments);
        this.orders = Objects.requireNonNull(orders);
        this.outbox = Objects.requireNonNull(outbox);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public final HandlingOutcome handle(PaymentEventData data, Instant occurredAt) {
        Payment payment = payments.findById(data.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(data.paymentId()));
        ensureEventMatches(payment, data);

        PaymentStatus previous = payment.status();
        TransitionResult result = payment.apply(supportedType(), occurredAt);
        if (!result.applied()) {
            log.info("Evento sem efeito para o pagamento {}: {}", payment.id(), reason(result, previous));
            return HandlingOutcome.ignored("Transition not applicable: " + result);
        }

        Instant now = clock.instant();
        payments.update(payment, now);

        Order order = orders.findById(payment.orderId())
                .orElseThrow(() -> new OrderNotFoundException(payment.orderId()));
        if (updateOrder(order)) {
            orders.update(order, now);
        }

        outbox.enqueue(notificationFor(payment, occurredAt), now);
        log.info("Pagamento {} {}: pedido {} {}. Aviso para a loja colocado na fila de envio", payment.id(),
                describe(payment.status()), order.id(), describe(order.status()));
        return HandlingOutcome.applied("Payment " + payment.status() + ", order " + order.status());
    }

    private static String describe(PaymentStatus status) {
        return switch (status) {
            case PENDING -> "pendente";
            case CONFIRMED -> "CONFIRMADO";
            case REFUSED -> "RECUSADO";
            case REFUNDED -> "ESTORNADO";
        };
    }

    private static String describe(OrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> "continua aguardando pagamento";
            case PAID -> "PAGO";
            case REFUNDED -> "ESTORNADO";
        };
    }

    private static String reason(TransitionResult result, PaymentStatus current) {
        return switch (result) {
            case STALE_EVENT -> "o evento é mais antigo que o último já aplicado (chegou fora de ordem)";
            case ALREADY_APPLIED -> "o pagamento já estava " + describe(current);
            case NOT_ALLOWED -> "um pagamento " + describe(current) + " não aceita esse evento";
            case APPLIED -> "";
        };
    }

    /**
     * Atualiza o pedido de acordo com o evento.
     *
     * @return {@code true} se o pedido mudou e precisa ser gravado
     */
    protected abstract boolean updateOrder(Order order);

    private static void ensureEventMatches(Payment payment, PaymentEventData data) {
        Money eventAmount;
        try {
            eventAmount = new Money(data.amountInCents(), data.currency());
        } catch (IllegalArgumentException invalidAmount) {
            throw new PaymentDataMismatchException(payment.id());
        }
        if (!payment.matches(data.orderId(), eventAmount)) {
            throw new PaymentDataMismatchException(payment.id());
        }
    }

    private NotificationMessage notificationFor(Payment payment, Instant occurredAt) {
        return new NotificationMessage(supportedType().name(), payment.id(), payment.orderId(),
                payment.amount().amountInCents(), payment.amount().currency(), payment.status().name(), occurredAt);
    }
}
