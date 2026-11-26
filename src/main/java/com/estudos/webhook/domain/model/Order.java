package com.estudos.webhook.domain.model;

import com.estudos.webhook.domain.exception.InvalidOrderTransitionException;

import java.util.Objects;

/**
 * Pedido de compra. Seu estado acompanha o ciclo de vida do pagamento.
 */
public final class Order {

    private final String id;
    private final Money totalAmount;
    private final long version;
    private OrderStatus status;

    private Order(String id, Money totalAmount, OrderStatus status, long version) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        this.id = id;
        this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.version = version;
    }

    /** Cria um pedido novo, aguardando pagamento. */
    public static Order create(String id, Money totalAmount) {
        return new Order(id, totalAmount, OrderStatus.PENDING_PAYMENT, 0);
    }

    /** Reconstrói um pedido a partir do estado persistido. */
    public static Order restore(String id, Money totalAmount, OrderStatus status, long version) {
        return new Order(id, totalAmount, status, version);
    }

    /**
     * Marca o pedido como pago.
     *
     * @throws InvalidOrderTransitionException se o pedido não estiver aguardando pagamento
     */
    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    /**
     * Marca o pedido como estornado.
     *
     * @throws InvalidOrderTransitionException se o pedido não estiver pago
     */
    public void markRefunded() {
        transitionTo(OrderStatus.REFUNDED);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidOrderTransitionException(id, status, target);
        }
        this.status = target;
    }

    public String id() {
        return id;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public OrderStatus status() {
        return status;
    }

    /** Versão para controle de concorrência otimista. */
    public long version() {
        return version;
    }
}
