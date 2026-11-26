package com.estudos.webhook.infrastructure.persistence;

import com.estudos.webhook.application.exception.ConcurrentUpdateException;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.OrderStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

import static com.estudos.webhook.infrastructure.persistence.JdbcTimestamps.toDb;

/**
 * Pedidos em PostgreSQL, com lock otimista pela coluna {@code version}.
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    private static final String FIND_BY_ID = """
            SELECT id, status, total_amount, currency, version FROM orders WHERE id = :id
            """;

    private static final String INSERT = """
            INSERT INTO orders (id, status, total_amount, currency, created_at, updated_at, version)
            VALUES (:id, :status, :totalAmount, :currency, :now, :now, 0)
            """;

    private static final String UPDATE = """
            UPDATE orders
               SET status = :status, updated_at = :now, version = version + 1
             WHERE id = :id AND version = :version
            """;

    private static final RowMapper<Order> ROW_MAPPER = (rs, rowNum) -> Order.restore(
            rs.getString("id"),
            new Money(rs.getLong("total_amount"), rs.getString("currency")),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Order> findById(String id) {
        return jdbc.query(FIND_BY_ID, new MapSqlParameterSource("id", id), ROW_MAPPER).stream().findFirst();
    }

    @Override
    public void insert(Order order, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", order.id())
                .addValue("status", order.status().name())
                .addValue("totalAmount", order.totalAmount().amountInCents())
                .addValue("currency", order.totalAmount().currency())
                .addValue("now", toDb(now));
        jdbc.update(INSERT, params);
    }

    @Override
    public void update(Order order, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("id", order.id())
                .addValue("status", order.status().name())
                .addValue("now", toDb(now))
                .addValue("version", order.version());
        if (jdbc.update(UPDATE, params) != 1) {
            throw new ConcurrentUpdateException("Order", order.id());
        }
    }
}
