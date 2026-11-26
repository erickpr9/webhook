package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.dto.WorkerSettings;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispatchPendingNotificationsUseCaseTest {

    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final NotificationMessage MESSAGE = new NotificationMessage("PAYMENT_CONFIRMED",
            TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", TestData.NOW);

    @Mock
    private NotificationOutbox outbox;
    @Mock
    private NotificationSender sender;
    @Mock
    private WebhookMetrics metrics;

    private DispatchPendingNotificationsUseCase useCase() {
        var settings = new WorkerSettings(50, LEASE, new RetryPolicy(3, List.of(Duration.ofSeconds(30))));
        return new DispatchPendingNotificationsUseCase(outbox, sender, settings, metrics,
                Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    private void claim(OutboxNotification notification) {
        when(outbox.claimDue(TestData.NOW, TestData.NOW.plus(LEASE), 50)).thenReturn(List.of(notification));
    }

    @Test
    void sendsAndMarksAsSent() {
        var notification = new OutboxNotification(UUID.randomUUID(), 1, MESSAGE);
        claim(notification);

        assertThat(useCase().execute()).isEqualTo(1);

        verify(sender).send(notification.id(), MESSAGE);
        verify(outbox).markSent(notification.id(), 1, TestData.NOW);
        verify(metrics).notificationSent("PAYMENT_CONFIRMED");
        verify(outbox).deadLetterAbandoned(TestData.NOW, 3);
    }

    @Test
    void failedSendIsRetried() {
        var notification = new OutboxNotification(UUID.randomUUID(), 1, MESSAGE);
        claim(notification);
        doThrow(new IllegalStateException("smtp down")).when(sender).send(notification.id(), MESSAGE);

        useCase().execute();

        verify(outbox).scheduleRetry(notification.id(), 1, TestData.NOW.plusSeconds(30),
                "IllegalStateException: smtp down");
        verify(outbox, never()).markSent(any(), anyInt(), any());
        verify(metrics).notificationFailed("PAYMENT_CONFIRMED", false);
    }

    @Test
    void failedSendAfterLastAttemptGoesToDead() {
        var notification = new OutboxNotification(UUID.randomUUID(), 3, MESSAGE);
        claim(notification);
        when(outbox.deadLetterAbandoned(TestData.NOW, 3)).thenReturn(2);
        doThrow(new IllegalStateException("smtp down")).when(sender).send(notification.id(), MESSAGE);

        useCase().execute();

        verify(outbox).markDead(notification.id(), 3, "IllegalStateException: smtp down");
        verify(metrics).notificationFailed("PAYMENT_CONFIRMED", true);
    }

    @Test
    void permanentRejectionGoesToDeadOnFirstAttempt() {
        var notification = new OutboxNotification(UUID.randomUUID(), 1, MESSAGE);
        claim(notification);
        doThrow(new PermanentDeliveryException("Client endpoint rejected the notification with 401"))
                .when(sender).send(notification.id(), MESSAGE);

        useCase().execute();

        verify(outbox).markDead(notification.id(), 1,
                "PermanentDeliveryException: Client endpoint rejected the notification with 401");
        verify(outbox, never()).scheduleRetry(any(), anyInt(), any(), any());
        verify(metrics).notificationFailed("PAYMENT_CONFIRMED", true);
    }
}
