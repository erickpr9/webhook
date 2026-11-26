package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.exception.EventPublishingException;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.NotificationPublisher;
import com.estudos.webhook.support.DirectTransactionRunner;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelayPendingNotificationsUseCaseTest {

    private static final NotificationMessage MESSAGE = new NotificationMessage("PAYMENT_CONFIRMED",
            TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", TestData.NOW);

    @Mock
    private NotificationOutbox outbox;
    @Mock
    private NotificationPublisher publisher;

    private final DirectTransactionRunner transactions = new DirectTransactionRunner();

    private RelayPendingNotificationsUseCase useCase() {
        return new RelayPendingNotificationsUseCase(outbox, publisher, transactions, 10,
                Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesPendingNotificationsAndMarksThemInTheSameTransaction() {
        var first = new OutboxNotification(UUID.randomUUID(), MESSAGE);
        var second = new OutboxNotification(UUID.randomUUID(), MESSAGE);
        when(outbox.lockPending(10)).thenReturn(List.of(first, second));

        assertThat(useCase().execute()).isEqualTo(2);

        InOrder order = inOrder(publisher, outbox);
        order.verify(publisher).publish(first);
        order.verify(publisher).publish(second);
        order.verify(outbox).markPublished(List.of(first.id(), second.id()), TestData.NOW);
        assertThat(transactions.executions()).isEqualTo(1);
    }

    @Test
    void nothingPendingPublishesNothing() {
        when(outbox.lockPending(10)).thenReturn(List.of());

        assertThat(useCase().execute()).isZero();

        verifyNoInteractions(publisher);
    }

    @Test
    void failedPublicationLeavesTheBatchPending() {
        var notification = new OutboxNotification(UUID.randomUUID(), MESSAGE);
        when(outbox.lockPending(10)).thenReturn(List.of(notification));
        doThrow(new EventPublishingException("broker down", null)).when(publisher).publish(notification);

        assertThatThrownBy(() -> useCase().execute()).isInstanceOf(EventPublishingException.class);

        verify(outbox, never()).markPublished(any(), any());
    }

    @Test
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new RelayPendingNotificationsUseCase(outbox, publisher, transactions, 0,
                Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    }
}
