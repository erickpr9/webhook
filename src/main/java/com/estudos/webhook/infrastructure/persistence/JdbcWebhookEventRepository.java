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
 * Registro de processamento dos webhooks em PostgreSQL.
 *
 * <p>A idempotência usa a restrição única {@code (provider, provider_event_id)} com
 * {@code ON CONFLICT DO NOTHING}, e o bloqueio da linha ({@code SELECT ... FOR UPDATE}) faz duas
 * cópias da mesma mensagem esperarem uma pela outra, dentro da transação do consumer.
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

    private static final String LOCK_FOR_PROCESSING = """
            SELECT *
              FROM webhook_events
             WHERE provider = :provider AND provider_event_id = :providerEventId
               FOR UPDATE
            """;

    private static final String COMPLETE = """
            UPDATE webhook_events
               SET status = :status, attempts = :attempts, processed_at = :now, processing_note = :note,
                   last_error = NULL
             WHERE id = :id
            """;

    private static final String RECORD_RETRY = """
            UPDATE webhook_events
               SET status = 'RETRY_SCHEDULED', attempts = :attempts, next_attempt_at = :nextAttemptAt,
                   last_error = :error
             WHERE id = :id
            """;

    private static final String MARK_DEAD = """
            UPDATE webhook_events
               SET status = 'DEAD', attempts = :attempts, last_error = :error
             WHERE id = :id
            """;

    private static final String REQUEUE = """
            UPDATE webhook_events
               SET status = 'RETRY_SCHEDULED', attempts = 0, next_attempt_at = :now
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
    public Optional<WebhookEvent> lockForProcessing(String provider, String providerEventId) {
        var params = new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("providerEventId", providerEventId);
        return jdbc.query(LOCK_FOR_PROCESSING, params, ROW_MAPPER).stream().findFirst();
    }

    @Override
    public void complete(UUID id, int attempts, WebhookEventStatus finalStatus, Instant now, String note) {
        if (finalStatus != WebhookEventStatus.PROCESSED && finalStatus != WebhookEventStatus.IGNORED) {
            throw new IllegalArgumentException("Final status must be PROCESSED or IGNORED, got " + finalStatus);
        }
        var params = withAttempts(id, attempts)
                .addValue("status", finalStatus.name())
                .addValue("now", toDb(now))
                .addValue("note", truncate(note, 255));
        jdbc.update(COMPLETE, params);
    }

    @Override
    public void recordRetry(UUID id, int attempts, Instant nextAttemptAt, String error) {
        var params = withAttempts(id, attempts)
                .addValue("nextAttemptAt", toDb(nextAttemptAt))
                .addValue("error", error);
        jdbc.update(RECORD_RETRY, params);
    }

    @Override
    public void markDead(UUID id, int attempts, String error) {
        jdbc.update(MARK_DEAD, withAttempts(id, attempts).addValue("error", error));
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

    private static MapSqlParameterSource withAttempts(UUID id, int attempts) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("attempts", attempts);
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
