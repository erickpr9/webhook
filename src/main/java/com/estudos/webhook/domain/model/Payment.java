package com.estudos.webhook.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Pagamento de um pedido. Concentra as regras de transição de estado a partir dos eventos
 * enviados pelo provedor.
 */
public final class Payment {

    private final String id;
    private final String orderId;
    private final Money amount;
    private final PaymentMethod method;
    private final long version;
    private PaymentStatus status;
    private Instant lastEventAt;

    private Payment(String id, String orderId, Money amount, PaymentMethod method,
                    PaymentStatus status, Instant lastEventAt, long version) {
        this.id = requireText(id, "id");
        this.orderId = requireText(orderId, "orderId");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.lastEventAt = lastEventAt;
        this.version = version;
    }

    /** Cria um pagamento novo, aguardando a confirmação do provedor. */
    public static Payment newPending(String id, String orderId, Money amount, PaymentMethod method) {
        return new Payment(id, orderId, amount, method, PaymentStatus.PENDING, null, 0);
    }

    /** Reconstrói um pagamento a partir do estado persistido. */
    public static Payment restore(String id, String orderId, Money amount, PaymentMethod method,
                                  PaymentStatus status, Instant lastEventAt, long version) {
        return new Payment(id, orderId, amount, method, status, lastEventAt, version);
    }

    /**
     * Aplica um evento do provedor.
     *
     * <p>Regras, nesta ordem:
     * <ol>
     *   <li>Evento mais antigo que o último aplicado é descartado ({@link TransitionResult#STALE_EVENT}).</li>
     *   <li>Se o pagamento já está no estado de destino, nada muda ({@link TransitionResult#ALREADY_APPLIED}).</li>
     *   <li>Transição proibida não altera o estado ({@link TransitionResult#NOT_ALLOWED}).</li>
     * </ol>
     *
     * @param eventType  tipo do evento recebido
     * @param occurredAt instante em que o evento ocorreu no provedor
     * @return o resultado da tentativa; o estado só muda quando for {@link TransitionResult#APPLIED}
     */
    public TransitionResult apply(PaymentEventType eventType, Instant occurredAt) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        if (lastEventAt != null && occurredAt.isBefore(lastEventAt)) {
            return TransitionResult.STALE_EVENT;
        }
        PaymentStatus target = eventType.targetStatus();
        if (status == target) {
            return TransitionResult.ALREADY_APPLIED;
        }
        if (!status.canTransitionTo(target)) {
            return TransitionResult.NOT_ALLOWED;
        }
        this.status = target;
        this.lastEventAt = occurredAt;
        return TransitionResult.APPLIED;
    }

    /** Indica se o pedido e o valor informados pelo provedor batem com este pagamento. */
    public boolean matches(String otherOrderId, Money otherAmount) {
        return orderId.equals(otherOrderId) && amount.equals(otherAmount);
    }

    public String id() {
        return id;
    }

    public String orderId() {
        return orderId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentMethod method() {
        return method;
    }

    public PaymentStatus status() {
        return status;
    }

    public Optional<Instant> lastEventAt() {
        return Optional.ofNullable(lastEventAt);
    }

    /** Versão para controle de concorrência otimista. */
    public long version() {
        return version;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
