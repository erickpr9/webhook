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
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.toDb;

/**
 * Outbox de notificações em PostgreSQL.
 *
 * <p>O relay reserva as linhas pendentes com {@code FOR UPDATE SKIP LOCKED}: várias instâncias
 * podem publicar em paralelo sem pegar a mesma notificação. A entrega abre cada tentativa com um
 * {@code UPDATE} condicional, que não tem efeito se a notificação já estiver em estado final.
 */
@Repository
public class JdbcNotificationOutbox implements NotificationOutbox {

    private static final String INSERT = """
            INSERT INTO notification_outbox (id, notification_type, aggregate_id, payload, status, attempts,
                                             next_attempt_at, created_at)
            VALUES (:id, :type, :aggregateId, CAST(:payload AS jsonb), 'PENDING', 0, :now, :now)
            """;

    private static final String LOCK_PENDING = """
            SELECT id, payload
              FROM notification_outbox
             WHERE status = 'PENDING'
             ORDER BY created_at
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE notification_outbox
               SET status = 'PUBLISHED', published_at = :now
             WHERE id IN (:ids) AND status = 'PENDING'
            """;

    // PENDING entra na lista porque a mensagem pode chegar ao consumer antes de o relay marcar
    // PUBLISHED: o UPDATE espera o lock do relay e, depois, encontra a linha já publicada.
    private static final String START_DELIVERY = """
            UPDATE notification_outbox
               SET attempts = attempts + 1
             WHERE id = :id AND status IN ('PENDING', 'PUBLISHED', 'RETRY_SCHEDULED')
            RETURNING attempts
            """;

    private static final String MARK_SENT = """
            UPDATE notification_outbox
               SET status = 'SENT', sent_at = :now, last_error = NULL
             WHERE id = :id
            """;

    private static final String SCHEDULE_RETRY = """
            UPDATE notification_outbox
               SET status = 'RETRY_SCHEDULED', next_attempt_at = :nextAttemptAt, last_error = :error
             WHERE id = :id AND status <> 'SENT'
            """;

    private static final String MARK_DEAD = """
            UPDATE notification_outbox
               SET status = 'DEAD', last_error = :error
             WHERE id = :id AND status NOT IN ('SENT', 'DEAD')
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
    public List<OutboxNotification> lockPending(int batchSize) {
        return jdbc.query(LOCK_PENDING, new MapSqlParameterSource("batchSize", batchSize), rowMapper);
    }

    @Override
    public void markPublished(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        var params = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("now", toDb(now));
        jdbc.update(MARK_PUBLISHED, params);
    }

    @Override
    public OptionalInt startDelivery(UUID id) {
        List<Integer> attempts = jdbc.queryForList(START_DELIVERY, new MapSqlParameterSource("id", id), Integer.class);
        return attempts.isEmpty() ? OptionalInt.empty() : OptionalInt.of(attempts.getFirst());
    }

    @Override
    public void markSent(UUID id, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", toDb(now));
        jdbc.update(MARK_SENT, params);
    }

    @Override
    public void scheduleRetry(UUID id, Instant nextAttemptAt, String error) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("nextAttemptAt", toDb(nextAttemptAt))
                .addValue("error", error);
        jdbc.update(SCHEDULE_RETRY, params);
    }

    @Override
    public void markDead(UUID id, String error) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("error", error);
        jdbc.update(MARK_DEAD, params);
    }

    @Override
    public List<NotificationView> findRecent(int limit) {
        return jdbc.query(FIND_RECENT, new MapSqlParameterSource("limit", limit), VIEW_MAPPER);
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
