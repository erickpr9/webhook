package com.estudos.webhook.application.port;

import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência da inbox de webhooks.
 *
 * <p>As operações de conclusão recebem o número da tentativa ({@code attempt}) como "fencing
 * token": a atualização só acontece se o evento ainda estiver em processamento por aquela
 * tentativa. Assim, um worker cujo lease expirou não sobrescreve o trabalho de outro.
 */
public interface WebhookEventRepository {

    /**
     * Grava o evento se ainda não existir outro com o mesmo provedor e identificador.
     *
     * @return {@code true} se gravou; {@code false} se era duplicado
     */
    boolean insertIfAbsent(WebhookEvent event);

    /**
     * Reserva até {@code batchSize} eventos prontos para processamento, marcando-os como
     * {@link WebhookEventStatus#PROCESSING} e incrementando as tentativas. Eventos reservados por
     * outro worker são pulados. Também retoma eventos cujo lease expirou.
     */
    List<WebhookEvent> claimDue(Instant now, Instant leaseUntil, int batchSize);

    /**
     * Move para {@link WebhookEventStatus#DEAD} os eventos com lease expirado que já esgotaram as
     * tentativas (ex.: o processo morreu várias vezes no mesmo evento).
     *
     * @return quantidade de eventos movidos
     */
    int deadLetterAbandoned(Instant now, int maxAttempts);

    /** Conclui o processamento com {@code finalStatus} (PROCESSED ou IGNORED). */
    boolean complete(UUID id, int attempt, WebhookEventStatus finalStatus, Instant now, String note);

    /** Agenda nova tentativa. */
    boolean scheduleRetry(UUID id, int attempt, Instant nextAttemptAt, String error);

    /** Move para {@link WebhookEventStatus#DEAD}. */
    boolean markDead(UUID id, int attempt, String error);

    /** Recoloca um evento DEAD na fila, zerando as tentativas. */
    boolean requeue(UUID id, Instant now);

    Optional<WebhookEvent> findById(UUID id);

    /** Lista os eventos mais recentes, filtrando por status quando informado. */
    List<WebhookEvent> findRecent(Optional<WebhookEventStatus> status, int limit);
}
