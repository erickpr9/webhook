package com.estudos.webhook.application.port;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.NotificationView;
import com.estudos.webhook.application.dto.OutboxNotification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Outbox de notificações.
 *
 * <pre>
 * PENDING ──relay──▶ PUBLISHED ──entrega──▶ SENT
 *                         │
 *                         ├──▶ RETRY_SCHEDULED ──▶ (nova tentativa) ...
 *                         └──▶ DEAD
 * </pre>
 */
public interface NotificationOutbox {

    /** Grava a notificação na transação corrente, junto com a mudança que a originou. */
    void enqueue(NotificationMessage message, Instant now);

    /**
     * Reserva até {@code batchSize} notificações ainda não publicadas, bloqueando as linhas até o
     * fim da transação corrente. Linhas já reservadas por outra instância são puladas.
     */
    List<OutboxNotification> lockPending(int batchSize);

    void markPublished(Collection<UUID> ids, Instant now);

    /**
     * Abre uma tentativa de entrega.
     *
     * @return número da tentativa, ou vazio se a notificação já foi entregue, já está DEAD ou não existe
     */
    OptionalInt startDelivery(UUID id);

    void markSent(UUID id, Instant now);

    void scheduleRetry(UUID id, Instant nextAttemptAt, String error);

    /** Move para DEAD se ainda não estiver em um estado final. */
    void markDead(UUID id, String error);

    List<NotificationView> findRecent(int limit);
}
