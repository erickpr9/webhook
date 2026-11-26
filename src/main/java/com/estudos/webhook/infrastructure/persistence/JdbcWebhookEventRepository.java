package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.fromDb;
import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.toDb;

/**
 * Inbox de webhooks em PostgreSQL.
 *
 * <p>A reserva de eventos usa {@code FOR UPDATE SKIP LOCKED} em uma única instrução
 * {@code UPDATE ... RETURNING}: a reserva é atômica, não segura locks durante o processamento
 * e permite vários workers em paralelo. Um lease ({@code locked_until}) permite retomar eventos
 * de um worker que morreu no meio do processamento.
 */
@Repository
public class JdbcWebhookEventRepository implements WebhookEventRepository {

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO webhook_events (id, provider, provider_event_id, event_type, payload, status, attempts,
                                        occurred_at, received_at, next_attempt_at)
            VALUES (:id, :provider, :providerEventId, :eventType, CAST(:payload AS jsonb), :status, 0,
                    :occurredAt, :receivedAt, :nextAttemptAt)
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            """;

    private static final String CLAIM_DUE = """
            UPDATE webhook_events
               SET status = 'PROCESSING',
                   attempts = attempts + 1,
                   locked_until = :leaseUntil
             WHERE id IN (
                   SELECT id
                     FROM webhook_events
                    WHERE (status IN ('RECEIVED', 'RETRY_SCHEDULED') AND next_attempt_at <= :now)
                       OR (status = 'PROCESSING' AND locked_until < :now)
                    ORDER BY received_at
                    LIMIT :batchSize
                      FOR UPDATE SKIP LOCKED)
            RETURNING *
            """;

    private static final String DEAD_LETTER_ABANDONED = """
            UPDATE webhook_events
               SET status = 'DEAD',
                   locked_until = NULL,
                   last_error = 'Processing lease expired after the maximum number of attempts'
             WHERE status = 'PROCESSING'
               AND locked_until < :now
               AND attempts >= :maxAttempts
            """;

    private static final String COMPLETE = """
            UPDATE webhook_events
               SET status = :status, processed_at = :now, processing_note = :note, locked_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """;

    private static final String SCHEDULE_RETRY = """
            UPDATE webhook_events
               SET status = 'RETRY_SCHEDULED', next_attempt_at = :nextAttemptAt, last_error = :error,
                   locked_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """;

    private static final String MARK_DEAD = """
            UPDATE webhook_events
               SET status = 'DEAD', last_error = :error, locked_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """;

    private static final String REQUEUE = """
            UPDATE webhook_events
               SET status = 'RETRY_SCHEDULED', attempts = 0, next_attempt_at = :now, locked_until = NULL
             WHERE id = :id AND status = 'DEAD'
            """;

    private static final String FIND_BY_ID = "SELECT * FROM webhook_events WHERE id = :id";

    private static final String FIND_RECENT = "SELECT * FROM webhook_events ORDER BY received_at DESC LIMIT :limit";

    private static final String FIND_RECENT_BY_STATUS = """
            SELECT * FROM webhook_events WHERE status = :status ORDER BY received_at DESC LIMIT :limit
            """;

    private static final RowMapper<WebhookEvent> ROW_MAPPER = (rs, rowNum) -> new WebhookEvent(
            rs.getObject("id", UUID.class),
            rs.getString("provider"),
            rs.getString("provider_event_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            WebhookEventStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            fromDb(rs, "occurred_at"),
            fromDb(rs, "received_at"),
            fromDb(rs, "next_attempt_at"),
            fromDb(rs, "processed_at"),
            rs.getString("processing_note"),
            rs.getString("last_error"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWebhookEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(WebhookEvent event) {
        var params = new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("provider", event.provider())
                .addValue("providerEventId", event.providerEventId())
                .addValue("eventType", event.eventType())
                .addValue("payload", event.payload())
                .addValue("status", event.status().name())
                .addValue("occurredAt", toDb(event.occurredAt()))
                .addValue("receivedAt", toDb(event.receivedAt()))
                .addValue("nextAttemptAt", toDb(event.nextAttemptAt()));
        return jdbc.update(INSERT_IF_ABSENT, params) == 1;
    }

    @Override
    public List<WebhookEvent> claimDue(Instant now, Instant leaseUntil, int batchSize) {
        var params = new MapSqlParameterSource()
                .addValue("now", toDb(now))
                .addValue("leaseUntil", toDb(leaseUntil))
                .addValue("batchSize", batchSize);
        return jdbc.query(CLAIM_DUE, params, ROW_MAPPER);
    }

    @Override
    public int deadLetterAbandoned(Instant now, int maxAttempts) {
        var params = new MapSqlParameterSource()
                .addValue("now", toDb(now))
                .addValue("maxAttempts", maxAttempts);
        return jdbc.update(DEAD_LETTER_ABANDONED, params);
    }

    @Override
    public boolean complete(UUID id, int attempt, WebhookEventStatus finalStatus, Instant now, String note) {
        if (finalStatus != WebhookEventStatus.PROCESSED && finalStatus != WebhookEventStatus.IGNORED) {
            throw new IllegalArgumentException("Final status must be PROCESSED or IGNORED, got " + finalStatus);
        }
        var params = fenced(id, attempt)
                .addValue("status", finalStatus.name())
                .addValue("now", toDb(now))
                .addValue("note", truncate(note, 255));
        return jdbc.update(COMPLETE, params) == 1;
    }

    @Override
    public boolean scheduleRetry(UUID id, int attempt, Instant nextAttemptAt, String error) {
        var params = fenced(id, attempt)
                .addValue("nextAttemptAt", toDb(nextAttemptAt))
                .addValue("error", error);
        return jdbc.update(SCHEDULE_RETRY, params) == 1;
    }

    @Override
    public boolean markDead(UUID id, int attempt, String error) {
        return jdbc.update(MARK_DEAD, fenced(id, attempt).addValue("error", error)) == 1;
    }

    @Override
    public boolean requeue(UUID id, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", toDb(now));
        return jdbc.update(REQUEUE, params) == 1;
    }

    @Override
    public Optional<WebhookEvent> findById(UUID id) {
        return jdbc.query(FIND_BY_ID, new MapSqlParameterSource("id", id), ROW_MAPPER).stream().findFirst();
    }

    @Override
    public List<WebhookEvent> findRecent(Optional<WebhookEventStatus> status, int limit) {
        var params = new MapSqlParameterSource("limit", limit);
        return status
                .map(value -> jdbc.query(FIND_RECENT_BY_STATUS, params.addValue("status", value.name()), ROW_MAPPER))
                .orElseGet(() -> jdbc.query(FIND_RECENT, params, ROW_MAPPER));
    }

    private static MapSqlParameterSource fenced(UUID id, int attempt) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("attempt", attempt);
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
