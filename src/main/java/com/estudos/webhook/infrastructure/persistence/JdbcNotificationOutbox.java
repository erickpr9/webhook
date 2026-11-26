package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.NotificationView;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.toDb;

/**
 * Outbox de notificações em PostgreSQL. Usa o mesmo padrão de reserva com lease da inbox.
 */
@Repository
public class JdbcNotificationOutbox implements NotificationOutbox {

    private static final String INSERT = """
            INSERT INTO notification_outbox (id, notification_type, aggregate_id, payload, status, attempts,
                                             next_attempt_at, created_at)
            VALUES (:id, :type, :aggregateId, CAST(:payload AS jsonb), 'PENDING', 0, :now, :now)
            """;

    private static final String CLAIM_DUE = """
            UPDATE notification_outbox
               SET status = 'PROCESSING',
                   attempts = attempts + 1,
                   locked_until = :leaseUntil
             WHERE id IN (
                   SELECT id
                     FROM notification_outbox
                    WHERE (status IN ('PENDING', 'RETRY_SCHEDULED') AND next_attempt_at <= :now)
                       OR (status = 'PROCESSING' AND locked_until < :now)
                    ORDER BY created_at
                    LIMIT :batchSize
                      FOR UPDATE SKIP LOCKED)
            RETURNING id, attempts, payload
            """;

    private static final String DEAD_LETTER_ABANDONED = """
            UPDATE notification_outbox
               SET status = 'DEAD',
                   locked_until = NULL,
                   last_error = 'Delivery lease expired after the maximum number of attempts'
             WHERE status = 'PROCESSING'
               AND locked_until < :now
               AND attempts >= :maxAttempts
            """;

    private static final String MARK_SENT = """
            UPDATE notification_outbox
               SET status = 'SENT', sent_at = :now, locked_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """;

    private static final String SCHEDULE_RETRY = """
            UPDATE notification_outbox
               SET status = 'RETRY_SCHEDULED', next_attempt_at = :nextAttemptAt, last_error = :error,
                   locked_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """;

    private static final String MARK_DEAD = """
            UPDATE notification_outbox
               SET status = 'DEAD', last_error = :error, locked_until = NULL
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """;

    private static final String FIND_RECENT = """
            SELECT id, notification_type, payload ->> 'orderId' AS order_id, aggregate_id, status, attempts,
                   created_at, next_attempt_at, sent_at, last_error
              FROM notification_outbox
             ORDER BY created_at DESC
             LIMIT :limit
            """;

    private static final RowMapper<NotificationView> VIEW_MAPPER = (rs, rowNum) -> new NotificationView(
            rs.getObject("id", UUID.class),
            rs.getString("notification_type"),
            rs.getString("order_id"),
            rs.getString("aggregate_id"),
            rs.getString("status"),
            rs.getInt("attempts"),
            JdbcTimestamps.fromDb(rs, "created_at"),
            JdbcTimestamps.fromDb(rs, "next_attempt_at"),
            JdbcTimestamps.fromDb(rs, "sent_at"),
            rs.getString("last_error"));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<OutboxNotification> rowMapper;

    public JdbcNotificationOutbox(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = (rs, rowNum) -> new OutboxNotification(
                rs.getObject("id", UUID.class),
                rs.getInt("attempts"),
                fromJson(rs.getString("payload")));
    }

    @Override
    public void enqueue(NotificationMessage message, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("type", message.type())
                .addValue("aggregateId", message.paymentId())
                .addValue("payload", toJson(message))
                .addValue("now", toDb(now));
        jdbc.update(INSERT, params);
    }

    @Override
    public List<OutboxNotification> claimDue(Instant now, Instant leaseUntil, int batchSize) {
        var params = new MapSqlParameterSource()
                .addValue("now", toDb(now))
                .addValue("leaseUntil", toDb(leaseUntil))
                .addValue("batchSize", batchSize);
        return jdbc.query(CLAIM_DUE, params, rowMapper);
    }

    @Override
    public int deadLetterAbandoned(Instant now, int maxAttempts) {
        var params = new MapSqlParameterSource()
                .addValue("now", toDb(now))
                .addValue("maxAttempts", maxAttempts);
        return jdbc.update(DEAD_LETTER_ABANDONED, params);
    }

    @Override
    public boolean markSent(UUID id, int attempt, Instant now) {
        return jdbc.update(MARK_SENT, fenced(id, attempt).addValue("now", toDb(now))) == 1;
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
    public List<NotificationView> findRecent(int limit) {
        return jdbc.query(FIND_RECENT, new MapSqlParameterSource("limit", limit), VIEW_MAPPER);
    }

    private static MapSqlParameterSource fenced(UUID id, int attempt) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("attempt", attempt);
    }

    private String toJson(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize notification", e);
        }
    }

    private NotificationMessage fromJson(String json) {
        try {
            return objectMapper.readValue(json, NotificationMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not deserialize notification", e);
        }
    }
}
