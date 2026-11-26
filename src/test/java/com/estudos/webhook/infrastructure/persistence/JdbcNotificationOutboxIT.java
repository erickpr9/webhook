package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.support.PostgresIntegrationTest;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcNotificationOutboxIT extends PostgresIntegrationTest {

    private static final Instant NOW = TestData.NOW;
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final NotificationMessage MESSAGE = new NotificationMessage("PAYMENT_CONFIRMED",
            TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", NOW.minusSeconds(3));

    @Autowired
    private JdbcNotificationOutbox outbox;

    private String statusOf(OutboxNotification notification) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class,
                notification.id());
    }

    @Test
    void enqueuedNotificationIsClaimedWithItsMessage() {
        outbox.enqueue(MESSAGE, NOW);

        var claimed = outbox.claimDue(NOW, NOW.plus(LEASE), 10);

        assertThat(claimed).singleElement().satisfies(notification -> {
            assertThat(notification.attempts()).isEqualTo(1);
            assertThat(notification.message()).isEqualTo(MESSAGE);
        });
        assertThat(outbox.claimDue(NOW, NOW.plus(LEASE), 10)).isEmpty();
    }

    @Test
    void markSentIsFencedByAttempt() {
        outbox.enqueue(MESSAGE, NOW);
        OutboxNotification notification = outbox.claimDue(NOW, NOW.plus(LEASE), 10).getFirst();

        assertThat(outbox.markSent(notification.id(), 2, NOW)).isFalse();
        assertThat(outbox.markSent(notification.id(), 1, NOW)).isTrue();
        assertThat(statusOf(notification)).isEqualTo("SENT");
    }

    @Test
    void retryAndDeadTransitions() {
        outbox.enqueue(MESSAGE, NOW);
        OutboxNotification first = outbox.claimDue(NOW, NOW.plus(LEASE), 10).getFirst();

        assertThat(outbox.scheduleRetry(first.id(), 1, NOW.plusSeconds(30), "down")).isTrue();
        assertThat(statusOf(first)).isEqualTo("RETRY_SCHEDULED");
        assertThat(outbox.claimDue(NOW, NOW.plus(LEASE), 10)).isEmpty();

        OutboxNotification second = outbox.claimDue(NOW.plusSeconds(30), NOW.plus(LEASE), 10).getFirst();
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(outbox.markDead(second.id(), 2, "still down")).isTrue();
        assertThat(statusOf(second)).isEqualTo("DEAD");
    }

    @Test
    void findRecentShowsDeliveryStatus() {
        outbox.enqueue(MESSAGE, NOW.minusSeconds(10));
        OutboxNotification claimed = outbox.claimDue(NOW, NOW.plus(LEASE), 10).getFirst();
        outbox.scheduleRetry(claimed.id(), 1, NOW.plusSeconds(30), "NotificationDeliveryException: 503");
        outbox.enqueue(MESSAGE, NOW);

        var recent = outbox.findRecent(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).status()).isEqualTo("PENDING");
        assertThat(recent.get(1)).satisfies(view -> {
            assertThat(view.id()).isEqualTo(claimed.id());
            assertThat(view.status()).isEqualTo("RETRY_SCHEDULED");
            assertThat(view.attempts()).isEqualTo(1);
            assertThat(view.orderId()).isEqualTo(TestData.ORDER_ID);
            assertThat(view.paymentId()).isEqualTo(TestData.PAYMENT_ID);
            assertThat(view.type()).isEqualTo("PAYMENT_CONFIRMED");
            assertThat(view.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(view.lastError()).contains("503");
        });
        assertThat(outbox.findRecent(1)).hasSize(1);
    }

    @Test
    void abandonedNotificationsGoToDead() {
        outbox.enqueue(MESSAGE, NOW);
        OutboxNotification claimed = outbox.claimDue(NOW, NOW.plus(LEASE), 10).getFirst();

        assertThat(outbox.deadLetterAbandoned(NOW.plus(LEASE).plusSeconds(1), 1)).isEqualTo(1);
        assertThat(statusOf(claimed)).isEqualTo("DEAD");
    }
}
