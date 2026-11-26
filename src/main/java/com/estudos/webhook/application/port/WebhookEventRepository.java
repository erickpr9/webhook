package com.estudos.webhook.application.port;

import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Registro de processamento dos webhooks consumidos da esteira.
 *
 * <p>É a chave da idempotência do consumer: a mensagem pode chegar mais de uma vez (reentrega do
 * broker, reenvio do provedor), mas o registro de cada evento é único por
 * {@code (provider, providerEventId)} e guarda se ele já foi tratado.
 */
public interface WebhookEventRepository {

    /**
     * Grava o evento se ainda não existir. Em corrida, apenas uma inserção vence; as outras
     * esperam a primeira transação terminar.
     *
     * @return {@code true} se o evento foi inserido agora
     */
    boolean insertIfAbsent(WebhookEvent event);

    /**
     * Lê o evento e bloqueia a linha até o fim da transação corrente. Duas cópias da mesma
     * mensagem processadas ao mesmo tempo ficam em fila: a segunda vê o resultado da primeira.
     */
    Optional<WebhookEvent> lockForProcessing(String provider, String providerEventId);

    /** Conclui o processamento com {@code PROCESSED} ou {@code IGNORED}. */
    void complete(UUID id, int attempts, WebhookEventStatus finalStatus, Instant now, String note);

    /** Registra uma tentativa que falhou e quando a próxima deve acontecer. */
    void recordRetry(UUID id, int attempts, Instant nextAttemptAt, String error);

    /** Move para DEAD (erro permanente ou tentativas esgotadas). */
    void markDead(UUID id, int attempts, String error);

    /**
     * Recoloca um evento DEAD para reprocessamento, com as tentativas zeradas.
     *
     * @return {@code false} se o evento não estava DEAD
     */
    boolean requeue(UUID id, Instant now);

    Optional<WebhookEvent> findById(UUID id);

    List<WebhookEvent> findRecent(Optional<WebhookEventStatus> status, int limit);
}
