package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.NotificationPublisher;
import com.estudos.webhook.application.port.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Relay do outbox: um ciclo publica na esteira as notificações gravadas junto com a mudança no
 * pedido.
 *
 * <p>Por que não publicar direto no handler? Porque banco e broker não participam da mesma
 * transação. Se o handler publicasse e a transação falhasse depois, a loja receberia um aviso de
 * algo que não aconteceu; se gravasse e a publicação falhasse, o aviso se perderia. Com o outbox,
 * o aviso nasce na mesma transação da mudança e o relay garante que ele chega ao broker.
 *
 * <p>As linhas ficam bloqueadas durante a publicação ({@code SKIP LOCKED}: várias instâncias podem
 * rodar o relay). Se a publicação falhar, a transação é desfeita e as notificações continuam
 * pendentes para o próximo ciclo. Se a aplicação cair depois de publicar e antes de marcar, elas
 * são publicadas de novo, e a entrega descarta a repetição.
 */
public final class RelayPendingNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RelayPendingNotificationsUseCase.class);

    private final NotificationOutbox outbox;
    private final NotificationPublisher publisher;
    private final TransactionRunner transactions;
    private final int batchSize;
    private final Clock clock;

    public RelayPendingNotificationsUseCase(NotificationOutbox outbox, NotificationPublisher publisher,
                                            TransactionRunner transactions, int batchSize, Clock clock) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.outbox = Objects.requireNonNull(outbox);
        this.publisher = Objects.requireNonNull(publisher);
        this.transactions = Objects.requireNonNull(transactions);
        this.batchSize = batchSize;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @return quantidade de notificações publicadas neste ciclo
     */
    public int execute() {
        int published = transactions.inTransaction(() -> {
            List<OutboxNotification> pending = outbox.lockPending(batchSize);
            pending.forEach(publisher::publish);
            outbox.markPublished(pending.stream().map(OutboxNotification::id).toList(), clock.instant());
            return pending.size();
        });
        if (published > 0) {
            log.info("{} aviso(s) do outbox publicado(s) na esteira de entrega à loja", published);
        }
        return published;
    }
}
