package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.CreateOrderCommand;
import com.estudos.webhook.application.dto.CreatedOrder;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.application.port.TransactionRunner;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Cria um pedido com seu pagamento pendente. É o estado esperado antes de o provedor enviar
 * os webhooks de pagamento.
 */
public final class CreateOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateOrderUseCase.class);

    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final TransactionRunner transactions;
    private final Clock clock;

    public CreateOrderUseCase(OrderRepository orders, PaymentRepository payments, TransactionRunner transactions,
                              Clock clock) {
        this.orders = Objects.requireNonNull(orders);
        this.payments = Objects.requireNonNull(payments);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    public CreatedOrder execute(CreateOrderCommand command) {
        Money total = new Money(command.amountInCents(), command.currency());
        PaymentMethod method = PaymentMethod.valueOf(command.paymentMethod());

        Order order = Order.create(newId("ord_"), total);
        Payment payment = Payment.newPending(newId("pay_"), order.id(), total, method);
        Instant now = clock.instant();

        transactions.inTransaction(() -> {
            orders.insert(order, now);
            payments.insert(payment, now);
        });
        log.info("Pedido {} criado, aguardando o pagamento {}", order.id(), payment.id());
        return new CreatedOrder(order.id(), payment.id());
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
