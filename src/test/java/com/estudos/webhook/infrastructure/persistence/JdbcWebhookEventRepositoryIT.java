package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.support.PostgresIntegrationTest;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class JdbcWebhookEventRepositoryIT extends PostgresIntegrationTest {

    private static final Instant NOW = TestData.NOW;
    private static final Duration LEASE = Duration.ofMinutes(2);

    @Autowired
    private JdbcWebhookEventRepository repository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private WebhookEvent newEvent(String providerEventId, Instant receivedAt) {
        return WebhookEvent.received(UUID.randomUUID(), "payment-gateway", providerEventId, "payment.confirmed",
                TestData.payload(providerEventId, "payment.confirmed", receivedAt), receivedAt.minusSeconds(1),
                receivedAt);
    }

    private WebhookEvent claimSingle(WebhookEvent event) {
        repository.insertIfAbsent(event);
        return repository.claimDue(NOW, NOW.plus(LEASE), 10).getFirst();
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
    void claimMarksEventsAsProcessingAndIncrementsAttempts() {
        WebhookEvent claimed = claimSingle(newEvent("evt_1", NOW));

        assertThat(claimed.status()).isEqualTo(WebhookEventStatus.PROCESSING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(repository.claimDue(NOW, NOW.plus(LEASE), 10)).as("already claimed").isEmpty();
    }

    @Test
    void claimSkipsEventsScheduledForLater() {
        WebhookEvent event = claimSingle(newEvent("evt_1", NOW));
        repository.scheduleRetry(event.id(), 1, NOW.plusSeconds(30), "boom");

        assertThat(repository.claimDue(NOW, NOW.plus(LEASE), 10)).isEmpty();
        assertThat(repository.claimDue(NOW.plusSeconds(30), NOW.plus(LEASE), 10)).hasSize(1);
    }

    @Test
    void claimRespectsBatchSize() {
        IntStream.range(0, 5).forEach(i -> repository.insertIfAbsent(newEvent("evt_" + i, NOW.minusSeconds(i))));

        assertThat(repository.claimDue(NOW, NOW.plus(LEASE), 3)).hasSize(3);
        assertThat(repository.claimDue(NOW, NOW.plus(LEASE), 3)).hasSize(2);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameEvent() throws Exception {
        int events = 40;
        IntStream.range(0, events).forEach(i -> repository.insertIfAbsent(newEvent("evt_" + i, NOW)));

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        Set<UUID> claimed = Collections.synchronizedSet(new HashSet<>());
        List<UUID> all = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<WebhookEvent> batch;
                    do {
                        batch = repository.claimDue(NOW, NOW.plus(LEASE), 3);
                        batch.forEach(event -> {
                            claimed.add(event.id());
                            all.add(event.id());
                        });
                    } while (!batch.isEmpty());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(all).hasSize(events);
        assertThat(claimed).hasSize(events);
    }

    @Test
    void rowLockedByAnotherTransactionIsSkipped() {
        WebhookEvent event = newEvent("evt_locked", NOW);
        repository.insertIfAbsent(event);
        repository.insertIfAbsent(newEvent("evt_free", NOW));

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM webhook_events WHERE id = ? FOR UPDATE", UUID.class, event.id());
            // Outra conexão (fora desta transação) não pode esperar pelo lock: precisa pular a linha.
            List<WebhookEvent> claimedElsewhere = runInNewThread(() -> repository.claimDue(NOW, NOW.plus(LEASE), 10));
            assertThat(claimedElsewhere).extracting(WebhookEvent::providerEventId).containsExactly("evt_free");
        });
    }

    @Test
    void expiredLeaseIsReclaimedByAnotherWorker() {
        WebhookEvent first = claimSingle(newEvent("evt_1", NOW));
        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);

        List<WebhookEvent> reclaimed = repository.claimDue(afterLease, afterLease.plus(LEASE), 10);

        assertThat(reclaimed).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo(first.id());
            assertThat(event.attempts()).isEqualTo(2);
        });
        assertThat(repository.complete(first.id(), 1, WebhookEventStatus.PROCESSED, afterLease, "late"))
                .as("the original worker lost the lease").isFalse();
        assertThat(repository.complete(first.id(), 2, WebhookEventStatus.PROCESSED, afterLease, "ok")).isTrue();
    }

    @Test
    void abandonedEventsBeyondMaxAttemptsGoToDead() {
        WebhookEvent claimed = claimSingle(newEvent("evt_1", NOW));
        Instant afterLease = NOW.plus(LEASE).plusSeconds(1);

        assertThat(repository.deadLetterAbandoned(afterLease, 2)).isZero();
        assertThat(repository.deadLetterAbandoned(afterLease, 1)).isEqualTo(1);

        WebhookEvent dead = repository.findById(claimed.id()).orElseThrow();
        assertThat(dead.status()).isEqualTo(WebhookEventStatus.DEAD);
        assertThat(dead.lastError()).contains("lease expired");
    }

    @Test
    void completeRecordsFinalStatusAndNote() {
        WebhookEvent claimed = claimSingle(newEvent("evt_1", NOW));

        assertThat(repository.complete(claimed.id(), 1, WebhookEventStatus.IGNORED, NOW, "x".repeat(300))).isTrue();

        WebhookEvent stored = repository.findById(claimed.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.IGNORED);
        assertThat(stored.processedAt()).isEqualTo(NOW);
        assertThat(stored.processingNote()).hasSize(255);
    }

    @Test
    void completeRejectsNonFinalStatus() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> repository.complete(UUID.randomUUID(), 1, WebhookEventStatus.DEAD, NOW, null));
    }

    @Test
    void failureTransitionsAreFencedByAttempt() {
        WebhookEvent claimed = claimSingle(newEvent("evt_1", NOW));

        assertThat(repository.scheduleRetry(claimed.id(), 99, NOW, "stale worker")).isFalse();
        assertThat(repository.markDead(claimed.id(), 99, "stale worker")).isFalse();
        assertThat(repository.markDead(claimed.id(), 1, "boom")).isTrue();

        WebhookEvent stored = repository.findById(claimed.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.DEAD);
        assertThat(stored.lastError()).isEqualTo("boom");
    }

    @Test
    void requeueOnlyAffectsDeadEventsAndResetsAttempts() {
        WebhookEvent claimed = claimSingle(newEvent("evt_1", NOW));
        assertThat(repository.requeue(claimed.id(), NOW)).as("not dead yet").isFalse();

        repository.markDead(claimed.id(), 1, "boom");
        assertThat(repository.requeue(claimed.id(), NOW.plusSeconds(5))).isTrue();

        WebhookEvent stored = repository.findById(claimed.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.RETRY_SCHEDULED);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.nextAttemptAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void findRecentOrdersByArrivalAndFiltersByStatus() {
        repository.insertIfAbsent(newEvent("evt_new", NOW));
        repository.insertIfAbsent(newEvent("evt_old", NOW.minusSeconds(60)));
        // Reserva só o mais antigo (a fila respeita a ordem de chegada).
        WebhookEvent claimed = repository.claimDue(NOW, NOW.plus(LEASE), 1).getFirst();

        assertThat(claimed.providerEventId()).isEqualTo("evt_old");
        assertThat(repository.findRecent(Optional.empty(), 10))
                .extracting(WebhookEvent::providerEventId)
                .containsExactly("evt_new", "evt_old");
        assertThat(repository.findRecent(Optional.of(WebhookEventStatus.PROCESSING), 10))
                .extracting(WebhookEvent::id)
                .containsExactly(claimed.id());
        assertThat(repository.findRecent(Optional.empty(), 1)).hasSize(1);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    private static <T> T runInNewThread(java.util.concurrent.Callable<T> task) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(task).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            executor.shutdownNow();
        }
    }
}
