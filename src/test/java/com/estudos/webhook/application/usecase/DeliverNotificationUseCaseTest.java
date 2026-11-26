package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.exception.PermanentDeliveryException;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.NotificationSender;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.service.RetryPolicy;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliverNotificationUseCaseTest {

    private static final NotificationMessage MESSAGE = new NotificationMessage("PAYMENT_CONFIRMED",
            TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", TestData.NOW);
    private static final OutboxNotification NOTIFICATION = new OutboxNotification(UUID.randomUUID(), MESSAGE);

    @Mock
    private NotificationOutbox outbox;
    @Mock
    private NotificationSender sender;
    @Mock
    private WebhookMetrics metrics;

    private DeliverNotificationUseCase useCase() {
        return new DeliverNotificationUseCase(outbox, sender, new RetryPolicy(3, List.of(Duration.ofSeconds(30))),
                metrics, Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    private void attempt(int number) {
        when(outbox.startDelivery(NOTIFICATION.id())).thenReturn(OptionalInt.of(number));
    }

    @Test
    void sendsAndMarksAsSent() {
        attempt(1);

        assertThat(useCase().execute(NOTIFICATION)).isEqualTo(ConsumeOutcome.COMPLETED);

        verify(sender).send(NOTIFICATION.id(), MESSAGE);
        verify(outbox).markSent(NOTIFICATION.id(), TestData.NOW);
        verify(metrics).notificationSent("PAYMENT_CONFIRMED");
    }

    @Test
    void notificationAlreadyDeliveredIsNotSentAgain() {
        when(outbox.startDelivery(NOTIFICATION.id())).thenReturn(OptionalInt.empty());

        assertThat(useCase().execute(NOTIFICATION)).isEqualTo(ConsumeOutcome.ALREADY_HANDLED);

        verifyNoInteractions(sender, metrics);
    }

    @Test
    void failedSendIsRetried() {
        attempt(1);
        doThrow(new IllegalStateException("store down")).when(sender).send(NOTIFICATION.id(), MESSAGE);

        assertThat(useCase().execute(NOTIFICATION)).isEqualTo(ConsumeOutcome.RETRY);

        verify(outbox).scheduleRetry(NOTIFICATION.id(), TestData.NOW.plusSeconds(30),
                "IllegalStateException: store down");
        verify(outbox, never()).markSent(any(), any());
        verify(metrics).notificationFailed("PAYMENT_CONFIRMED", false);
    }

    @Test
    void failedSendOnLastAttemptGoesToDead() {
        attempt(3);
        doThrow(new IllegalStateException("store down")).when(sender).send(NOTIFICATION.id(), MESSAGE);

        assertThat(useCase().execute(NOTIFICATION)).isEqualTo(ConsumeOutcome.DEAD);

        verify(outbox).markDead(NOTIFICATION.id(), "IllegalStateException: store down");
        verify(metrics).notificationFailed("PAYMENT_CONFIRMED", true);
    }

    @Test
    void permanentRejectionGoesToDeadOnFirstAttempt() {
        attempt(1);
        doThrow(new PermanentDeliveryException("Client endpoint rejected the notification with 410"))
                .when(sender).send(NOTIFICATION.id(), MESSAGE);

        assertThat(useCase().execute(NOTIFICATION)).isEqualTo(ConsumeOutcome.DEAD);

        verify(outbox).markDead(NOTIFICATION.id(),
                "PermanentDeliveryException: Client endpoint rejected the notification with 410");
        verify(outbox, never()).scheduleRetry(any(), any(), any());
    }

    @Test
    void deadLetteredNotificationIsMarkedDead() {
        useCase().deadLettered(NOTIFICATION, "exhausted");

        verify(outbox).markDead(NOTIFICATION.id(), "exhausted");
    }
}
