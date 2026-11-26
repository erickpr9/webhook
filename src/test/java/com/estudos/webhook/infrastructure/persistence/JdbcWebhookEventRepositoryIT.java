package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.support.IntegrationTest;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class JdbcWebhookEventRepositoryIT extends IntegrationTest {

    private static final Instant NOW = TestData.NOW;

    @Autowired
    private JdbcWebhookEventRepository repository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private WebhookEvent newEvent(String providerEventId, Instant receivedAt) {
        return WebhookEvent.received(UUID.randomUUID(), "payment-gateway", providerEventId, "payment.confirmed",
                TestData.payload(providerEventId, "payment.confirmed", receivedAt), receivedAt.minusSeconds(1),
                receivedAt);
    }

    @Test
    void insertIsIdempotentPerProviderEventId() {
        assertThat(repository.insertIfAbsent(newEvent("evt_1", NOW))).isTrue();
        assertThat(repository.insertIfAbsent(newEvent("evt_1", NOW))).isFalse();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM webhook_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void storesPayloadAsJsonbAndReadsItBack() {
        WebhookEvent event = newEvent("evt_1", NOW);
        repository.insertIfAbsent(event);

        WebhookEvent stored = repository.findById(event.id()).orElseThrow();

        assertThat(stored.status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(stored.occurredAt()).isEqualTo(event.occurredAt());
        assertThat(stored.receivedAt()).isEqualTo(NOW);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload ->> 'type' FROM webhook_events WHERE id = ?", String.class, event.id()))
                .isEqualTo("payment.confirmed");
    }

    @Test
    void concurrentInsertsOfTheSameEventKeepOnlyOne() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return repository.insertIfAbsent(newEvent("evt_race", NOW));
                }));
            }
            start.countDown();

            long inserted = 0;
            for (Future<Boolean> result : results) {
                inserted += result.get() ? 1 : 0;
            }
            assertThat(inserted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockForProcessingFindsTheEventByItsProviderId() {
        WebhookEvent event = newEvent("evt_1", NOW);
        repository.insertIfAbsent(event);

        Optional<WebhookEvent> locked = transactionTemplate.execute(
                status -> repository.lockForProcessing("payment-gateway", "evt_1"));

        assertThat(locked).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(event.id());
            assertThat(found.status()).isEqualTo(WebhookEventStatus.RECEIVED);
        });
        assertThat(repository.lockForProcessing("payment-gateway", "evt_unknown")).isEmpty();
        assertThat(repository.lockForProcessing("other-provider", "evt_1")).isEmpty();
    }

    @Test
    void secondCopyWaitsForTheFirstAndSeesItsResult() throws Exception {
        WebhookEvent event = newEvent("evt_copy", NOW);
        repository.insertIfAbsent(event);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                repository.lockForProcessing("payment-gateway", "evt_copy").orElseThrow();
                locked.countDown();
                await(release);
                repository.complete(event.id(), 1, WebhookEventStatus.PROCESSED, NOW, "first copy");
            }));
            locked.await();
            Future<WebhookEventStatus> second = pool.submit(() -> transactionTemplate.execute(
                    status -> repository.lockForProcessing("payment-gateway", "evt_copy").orElseThrow().status()));

            Thread.sleep(300);
            assertThat(second.isDone()).as("blocked by the first transaction").isFalse();
            release.countDown();
            first.get();

            assertThat(second.get()).isEqualTo(WebhookEventStatus.PROCESSED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completeRecordsFinalStatusAttemptsAndNote() {
        WebhookEvent event = newEvent("evt_1", NOW);
        repository.insertIfAbsent(event);
        repository.recordRetry(event.id(), 1, NOW.plusSeconds(10), "boom");

        repository.complete(event.id(), 2, WebhookEventStatus.IGNORED, NOW, "x".repeat(300));

        WebhookEvent stored = repository.findById(event.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.IGNORED);
        assertThat(stored.attempts()).isEqualTo(2);
        assertThat(stored.processedAt()).isEqualTo(NOW);
        assertThat(stored.processingNote()).hasSize(255);
        assertThat(stored.lastError()).as("cleared on success").isNull();
    }

    @Test
    void completeRejectsNonFinalStatus() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> repository.complete(UUID.randomUUID(), 1, WebhookEventStatus.DEAD, NOW, null));
    }

    @Test
    void retryAndDeadRecordAttemptsAndError() {
        WebhookEvent event = newEvent("evt_1", NOW);
        repository.insertIfAbsent(event);

        repository.recordRetry(event.id(), 1, NOW.plusSeconds(30), "transient");
        WebhookEvent retrying = repository.findById(event.id()).orElseThrow();
        assertThat(retrying.status()).isEqualTo(WebhookEventStatus.RETRY_SCHEDULED);
        assertThat(retrying.attempts()).isEqualTo(1);
        assertThat(retrying.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(retrying.lastError()).isEqualTo("transient");

        repository.markDead(event.id(), 2, "gave up");
        WebhookEvent dead = repository.findById(event.id()).orElseThrow();
        assertThat(dead.status()).isEqualTo(WebhookEventStatus.DEAD);
        assertThat(dead.attempts()).isEqualTo(2);
        assertThat(dead.lastError()).isEqualTo("gave up");
    }

    @Test
    void requeueOnlyAffectsDeadEventsAndResetsAttempts() {
        WebhookEvent event = newEvent("evt_1", NOW);
        repository.insertIfAbsent(event);
        assertThat(repository.requeue(event.id(), NOW)).as("not dead yet").isFalse();

        repository.markDead(event.id(), 3, "boom");
        assertThat(repository.requeue(event.id(), NOW.plusSeconds(5))).isTrue();

        WebhookEvent stored = repository.findById(event.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.RETRY_SCHEDULED);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void findRecentOrdersByArrivalAndFiltersByStatus() {
        WebhookEvent recent = newEvent("evt_new", NOW);
        WebhookEvent old = newEvent("evt_old", NOW.minusSeconds(60));
        repository.insertIfAbsent(recent);
        repository.insertIfAbsent(old);
        repository.markDead(old.id(), 1, "boom");

        assertThat(repository.findRecent(Optional.empty(), 10))
                .extracting(WebhookEvent::providerEventId)
                .containsExactly("evt_new", "evt_old");
        assertThat(repository.findRecent(Optional.of(WebhookEventStatus.DEAD), 10))
                .extracting(WebhookEvent::id)
                .containsExactly(old.id());
        assertThat(repository.findRecent(Optional.empty(), 1)).hasSize(1);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
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
