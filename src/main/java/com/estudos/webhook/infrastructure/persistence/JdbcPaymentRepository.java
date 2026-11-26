package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.exception.ConcurrentUpdateException;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentMethod;
import com.estudos.webhook.domain.model.PaymentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.fromDb;
import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.toDb;

/**
 * Pagamentos em PostgreSQL, com lock otimista pela coluna {@code version}.
 */
@Repository
public class JdbcPaymentRepository implements PaymentRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, order_id, status, amount, currency, method, last_event_at, version FROM payments
            """;

    private static final String INSERT = """
            INSERT INTO payments (id, order_id, status, amount, currency, method, last_event_at,
                                  created_at, updated_at, version)
            VALUES (:id, :orderId, :status, :amount, :currency, :method, :lastEventAt, :now, :now, 0)
            """;

    private static final String UPDATE = """
            UPDATE payments
               SET status = :status, last_event_at = :lastEventAt, updated_at = :now, version = version + 1
             WHERE id = :id AND version = :version
            """;

    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> Payment.restore(
            rs.getString("id"),
            rs.getString("order_id"),
            new Money(rs.getLong("amount"), rs.getString("currency")),
            PaymentMethod.valueOf(rs.getString("method")),
            PaymentStatus.valueOf(rs.getString("status")),
            fromDb(rs, "last_event_at"),
            rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPaymentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Payment> findById(String id) {
        return jdbc.query(SELECT_COLUMNS + " WHERE id = :id", new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public List<Payment> findByOrderId(String orderId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE order_id = :orderId ORDER BY created_at",
                new MapSqlParameterSource("orderId", orderId), ROW_MAPPER);
    }

    @Override
    public void insert(Payment payment, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", payment.id())
                .addValue("orderId", payment.orderId())
                .addValue("status", payment.status().name())
                .addValue("amount", payment.amount().amountInCents())
                .addValue("currency", payment.amount().currency())
                .addValue("method", payment.method().name())
                .addValue("lastEventAt", toDb(payment.lastEventAt().orElse(null)))
                .addValue("now", toDb(now));
        jdbc.update(INSERT, params);
    }

    @Override
    public void update(Payment payment, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", payment.id())
                .addValue("status", payment.status().name())
                .addValue("lastEventAt", toDb(payment.lastEventAt().orElse(null)))
                .addValue("now", toDb(now))
                .addValue("version", payment.version());
        if (jdbc.update(UPDATE, params) != 1) {
            throw new ConcurrentUpdateException("Payment", payment.id());
        }
    }
}
