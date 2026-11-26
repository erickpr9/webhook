package com.estudos.webhook.application.port;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.NotificationView;
import com.estudos.webhook.application.dto.OutboxNotification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Porta do outbox de notificações. O {@link #enqueue} deve participar da transação corrente,
 * para que a notificação só exista se a mudança de domínio for confirmada.
 *
 * <p>As operações de conclusão usam o número da tentativa como "fencing token", como na inbox.
 */
public interface NotificationOutbox {

    void enqueue(NotificationMessage message, Instant now);

    /** Reserva notificações prontas para envio, incluindo as de lease expirado. */
    List<OutboxNotification> claimDue(Instant now, Instant leaseUntil, int batchSize);

    /** Move para DEAD as notificações com lease expirado e tentativas esgotadas. */
    int deadLetterAbandoned(Instant now, int maxAttempts);

    boolean markSent(UUID id, int attempt, Instant now);

    boolean scheduleRetry(UUID id, int attempt, Instant nextAttemptAt, String error);

    boolean markDead(UUID id, int attempt, String error);

    /** Notificações mais recentes, para acompanhamento das entregas. */
    List<NotificationView> findRecent(int limit);
}
