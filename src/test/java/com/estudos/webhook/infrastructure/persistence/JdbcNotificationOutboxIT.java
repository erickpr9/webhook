package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.support.IntegrationTest;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcNotificationOutboxIT extends IntegrationTest {

    private static final Instant NOW = TestData.NOW;
    private static final NotificationMessage MESSAGE = new NotificationMessage("PAYMENT_CONFIRMED",
            TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", NOW.minusSeconds(3));

    @Autowired
    private JdbcNotificationOutbox outbox;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private String statusOf(UUID id) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String.class, id);
    }

    private UUID enqueueAndPublish() {
        outbox.enqueue(MESSAGE, NOW);
        OutboxNotification pending = outbox.lockPending(10).getFirst();
        outbox.markPublished(List.of(pending.id()), NOW);
        return pending.id();
    }

    @Test
    void pendingNotificationIsLockedWithItsMessageAndThenPublished() {
        outbox.enqueue(MESSAGE, NOW);

        List<OutboxNotification> pending = outbox.lockPending(10);

        assertThat(pending).singleElement().satisfies(n -> assertThat(n.message()).isEqualTo(MESSAGE));
        outbox.markPublished(List.of(pending.getFirst().id()), NOW);
        assertThat(statusOf(pending.getFirst().id())).isEqualTo("PUBLISHED");
        assertThat(outbox.lockPending(10)).as("published ones are not relayed again").isEmpty();
    }

    @Test
    void lockPendingRespectsBatchSizeAndCreationOrder() {
        outbox.enqueue(MESSAGE, NOW);
        outbox.enqueue(MESSAGE, NOW.minusSeconds(60));
        outbox.enqueue(MESSAGE, NOW.minusSeconds(30));

        List<OutboxNotification> batch = outbox.lockPending(2);

        assertThat(batch).hasSize(2);
        List<Instant> createdAt = batch.stream()
                .map(n -> jdbcTemplate.queryForObject("SELECT created_at FROM notification_outbox WHERE id = ?",
                        java.sql.Timestamp.class, n.id()).toInstant())
                .toList();
        assertThat(createdAt).containsExactly(NOW.minusSeconds(60), NOW.minusSeconds(30));
    }

    @Test
    void concurrentRelaysSkipRowsLockedByEachOther() throws Exception {
        outbox.enqueue(MESSAGE, NOW);
        outbox.enqueue(MESSAGE, NOW.plusSeconds(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<OutboxNotification>> first = pool.submit(() -> transactionTemplate.execute(status -> {
                List<OutboxNotification> batch = outbox.lockPending(1);
                locked.countDown();
                await(release);
                return batch;
            }));
            locked.await();
            List<OutboxNotification> second = pool.submit(() -> transactionTemplate.execute(
                    status -> outbox.lockPending(10))).get();
            release.countDown();

            assertThat(second).hasSize(1);
            assertThat(second.getFirst().id()).isNotEqualTo(first.get().getFirst().id());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deliveryAttemptsAreCountedUntilTheNotificationIsSent() {
        UUID id = enqueueAndPublish();

        assertThat(outbox.startDelivery(id)).hasValue(1);
        outbox.scheduleRetry(id, NOW.plusSeconds(30), "NotificationDeliveryException: 503");
        assertThat(statusOf(id)).isEqualTo("RETRY_SCHEDULED");
        assertThat(outbox.startDelivery(id)).hasValue(2);
        outbox.markSent(id, NOW);

        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(outbox.startDelivery(id)).as("redelivery of a sent notification").isEmpty();
        assertThat(outbox.startDelivery(UUID.randomUUID())).as("unknown notification").isEmpty();
    }

    @Test
    void deliveryCanStartBeforeTheRelayMarksItPublished() {
        outbox.enqueue(MESSAGE, NOW);
        UUID id = outbox.lockPending(10).getFirst().id();

        assertThat(outbox.startDelivery(id)).hasValue(1);
    }

    @Test
    void deadIsFinalAndNeverOverwritesASentNotification() {
        UUID dead = enqueueAndPublish();
        outbox.markDead(dead, "store rejected");
        assertThat(statusOf(dead)).isEqualTo("DEAD");
        assertThat(outbox.startDelivery(dead)).isEmpty();

        UUID sent = enqueueAndPublish();
        outbox.markSent(sent, NOW);
        outbox.markDead(sent, "late dead-letter");
        outbox.scheduleRetry(sent, NOW, "late retry");
        assertThat(statusOf(sent)).isEqualTo("SENT");
    }

    @Test
    void findRecentShowsDeliveryStatus() {
        outbox.enqueue(MESSAGE, NOW.minusSeconds(10));
        OutboxNotification first = outbox.lockPending(10).getFirst();
        outbox.markPublished(List.of(first.id()), NOW);
        outbox.startDelivery(first.id());
        outbox.scheduleRetry(first.id(), NOW.plusSeconds(30), "NotificationDeliveryException: 503");
        outbox.enqueue(MESSAGE, NOW);

        var recent = outbox.findRecent(10);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).status()).isEqualTo("PENDING");
        assertThat(recent.get(1)).satisfies(view -> {
            assertThat(view.id()).isEqualTo(first.id());
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
    void markPublishedWithNoIdsDoesNothing() {
        outbox.enqueue(MESSAGE, NOW);

        outbox.markPublished(List.of(), NOW);

        assertThat(outbox.lockPending(10)).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
