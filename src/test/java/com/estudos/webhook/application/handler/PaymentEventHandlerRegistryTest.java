package com.estudos.webhook.application.handler;

import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.model.PaymentEventType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

class PaymentEventHandlerRegistryTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final NotificationOutbox outbox = mock(NotificationOutbox.class);
    private final Clock clock = Clock.systemUTC();

    private final ConfirmPaymentHandler confirm = new ConfirmPaymentHandler(payments, orders, outbox, clock);
    private final RefusePaymentHandler refuse = new RefusePaymentHandler(payments, orders, outbox, clock);
    private final RefundPaymentHandler refund = new RefundPaymentHandler(payments, orders, outbox, clock);

    @Test
    void resolvesHandlerByType() {
        var registry = new PaymentEventHandlerRegistry(List.of(confirm, refuse, refund));

        assertThat(registry.handlerFor(PaymentEventType.PAYMENT_CONFIRMED)).isSameAs(confirm);
        assertThat(registry.handlerFor(PaymentEventType.PAYMENT_REFUSED)).isSameAs(refuse);
        assertThat(registry.handlerFor(PaymentEventType.PAYMENT_REFUNDED)).isSameAs(refund);
    }

    @Test
    void failsFastWhenAHandlerIsMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new PaymentEventHandlerRegistry(List.of(confirm, refuse)))
                .withMessageContaining("PAYMENT_REFUNDED");
    }

    @Test
    void failsFastOnDuplicateHandlers() {
        var anotherConfirm = new ConfirmPaymentHandler(payments, orders, outbox, clock);

        assertThatIllegalStateException()
                .isThrownBy(() -> new PaymentEventHandlerRegistry(List.of(confirm, anotherConfirm, refuse, refund)))
                .withMessageContaining("Duplicate");
    }
}
