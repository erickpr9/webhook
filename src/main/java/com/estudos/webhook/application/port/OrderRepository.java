package com.estudos.webhook.application.port;

import com.estudos.webhook.application.exception.ConcurrentUpdateException;
import com.estudos.webhook.domain.model.Order;

import java.time.Instant;
import java.util.Optional;

/**
 * Porta de persistência de pedidos.
 */
public interface OrderRepository {

    Optional<Order> findById(String id);

    void insert(Order order, Instant now);

    /**
     * Atualiza o pedido usando a versão lida como controle otimista.
     *
     * @throws ConcurrentUpdateException se outro processo alterou o pedido nesse meio-tempo
     */
    void update(Order order, Instant now);
}
