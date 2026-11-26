package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.HandlingOutcome;
import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.exception.FailureClassifier;
import com.estudos.webhook.application.handler.PaymentEventHandlerRegistry;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.estudos.webhook.application.port.TransactionRunner;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.model.PaymentEventType;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.domain.service.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Processa um webhook consumido da esteira.
 *
 * <p>A entrega é "pelo menos uma vez": a mesma mensagem pode chegar de novo (reentrega do broker,
 * reenvio do provedor, cópia vinda de um tópico de retry). O efeito, porém, acontece uma vez só:
 * <ol>
 *   <li>o evento é gravado no registro de processamento, se ainda não existir;</li>
 *   <li>a linha é bloqueada e, se o evento já foi tratado, nada mais acontece;</li>
 *   <li>a transição de domínio, o outbox e a conclusão do evento ficam na mesma transação.</li>
 * </ol>
 *
 * <p>Em caso de falha, a transação é desfeita e a falha é registrada em uma transação separada.
 * O resultado ({@link ConsumeOutcome}) diz ao adaptador se a mensagem vai para retentativa ou
 * para a dead-letter.
 */
public final class ProcessWebhookEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookEventUseCase.class);

    static final String MDC_EVENT_ID = "eventId";
    static final String MDC_EVENT_TYPE = "eventType";
    static final String MDC_ATTEMPT = "attempt";

    private final WebhookEventRepository repository;
    private final PaymentEventPayloadReader payloadReader;
    private final PaymentEventHandlerRegistry handlers;
    private final TransactionRunner transactions;
    private final RetryPolicy retryPolicy;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public ProcessWebhookEventUseCase(WebhookEventRepository repository, PaymentEventPayloadReader payloadReader,
                                      PaymentEventHandlerRegistry handlers, TransactionRunner transactions,
                                      RetryPolicy retryPolicy, WebhookMetrics metrics, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.handlers = Objects.requireNonNull(handlers);
        this.transactions = Objects.requireNonNull(transactions);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    public ConsumeOutcome execute(ReceivedWebhookEvent message) {
        long startedAt = System.nanoTime();
        MDC.put(MDC_EVENT_ID, message.providerEventId());
        MDC.put(MDC_EVENT_TYPE, message.eventType());
        try {
            Completion completion = transactions.inTransaction(() -> handle(message));
            if (completion.alreadyHandled()) {
                log.info("Mensagem repetida: o evento já tinha sido tratado. Nada foi processado de novo");
                return ConsumeOutcome.ALREADY_HANDLED;
            }
            metrics.eventCompleted(message.eventType(), completion.status());
            log.info("Evento concluído com status {} (tentativa {})", completion.status(), completion.attempt());
            return ConsumeOutcome.COMPLETED;
        } catch (RuntimeException failure) {
            return recordFailure(message, failure);
        } finally {
            metrics.processingTime(message.eventType(), Duration.ofNanos(System.nanoTime() - startedAt));
            MDC.remove(MDC_EVENT_ID);
            MDC.remove(MDC_EVENT_TYPE);
            MDC.remove(MDC_ATTEMPT);
        }
    }

    /**
     * Registra como DEAD um evento que chegou à dead-letter sem ter sido marcado (por exemplo,
     * porque o banco estava fora do ar quando a última tentativa falhou).
     */
    public void deadLettered(ReceivedWebhookEvent message, String reason) {
        transactions.inTransaction(() -> {
            WebhookEvent event = lockedRecord(message);
            if (!event.status().isFinal()) {
                repository.markDead(event.id(), event.attempts(), reason);
                metrics.eventDead(message.eventType());
            }
            return null;
        });
    }

    private Completion handle(ReceivedWebhookEvent message) {
        WebhookEvent event = lockedRecord(message);
        if (event.status().isFinal()) {
            return Completion.ALREADY_HANDLED;
        }
        int attempt = event.attempts() + 1;
        MDC.put(MDC_ATTEMPT, String.valueOf(attempt));

        Optional<PaymentEventType> type = PaymentEventType.fromCode(event.eventType());
        if (type.isEmpty()) {
            log.info("Evento ignorado: a plataforma não trata eventos do tipo '{}'", event.eventType());
            repository.complete(event.id(), attempt, WebhookEventStatus.IGNORED, clock.instant(),
                    "Unsupported event type");
            return new Completion(WebhookEventStatus.IGNORED, attempt);
        }
        PaymentEventData data = payloadReader.read(event.payload());
        HandlingOutcome outcome = handlers.handlerFor(type.get()).handle(data, event.occurredAt());
        repository.complete(event.id(), attempt, WebhookEventStatus.PROCESSED, clock.instant(), outcome.note());
        return new Completion(WebhookEventStatus.PROCESSED, attempt);
    }

    private ConsumeOutcome recordFailure(ReceivedWebhookEvent message, RuntimeException failure) {
        String error = FailureClassifier.describe(failure);
        boolean retryable = FailureClassifier.isRetryable(failure);
        try {
            return transactions.inTransaction(() -> {
                WebhookEvent event = lockedRecord(message);
                if (event.status().isFinal()) {
                    // Outra cópia da mensagem concluiu o evento enquanto esta falhava.
                    return ConsumeOutcome.ALREADY_HANDLED;
                }
                int attempt = event.attempts() + 1;
                if (!retryable || retryPolicy.isExhausted(attempt)) {
                    repository.markDead(event.id(), attempt, error);
                    metrics.eventDead(message.eventType());
                    if (retryable) {
                        // Erro inesperado: o stack trace ajuda a investigar.
                        log.error("Evento movido para DEAD após {} tentativa(s): as tentativas acabaram. {}",
                                attempt, error, failure);
                    } else {
                        log.error("Evento movido para DEAD na tentativa {}: erro permanente, tentar de novo não "
                                + "resolveria. {}", attempt, error);
                    }
                    return ConsumeOutcome.DEAD;
                }
                Duration delay = retryPolicy.delayAfter(attempt);
                Instant nextAttemptAt = clock.instant().plus(delay);
                repository.recordRetry(event.id(), attempt, nextAttemptAt, error);
                metrics.eventRetryScheduled(message.eventType());
                log.warn("Falha ao processar o evento (tentativa {} de {}). Nova tentativa em {}s: {}",
                        attempt, retryPolicy.maxAttempts(), delay.toSeconds(), error);
                return ConsumeOutcome.RETRY;
            });
        } catch (RuntimeException recordingFailure) {
            log.error("Falha ao processar o evento ({}) e também ao registrar a falha ({}). A mensagem segue "
                            + "para {}", error, FailureClassifier.describe(recordingFailure),
                    retryable ? "retentativa" : "a dead-letter");
            return retryable ? ConsumeOutcome.RETRY : ConsumeOutcome.DEAD;
        }
    }

    /** Garante que o evento existe no registro e bloqueia a linha até o fim da transação. */
    private WebhookEvent lockedRecord(ReceivedWebhookEvent message) {
        repository.insertIfAbsent(WebhookEvent.received(UUID.randomUUID(), message.provider(),
                message.providerEventId(), message.eventType(), message.payload(), message.occurredAt(),
                message.receivedAt()));
        return repository.lockForProcessing(message.provider(), message.providerEventId())
                .orElseThrow(() -> new IllegalStateException(
                        "Webhook event " + message.providerEventId() + " vanished after insert"));
    }

    private record Completion(WebhookEventStatus status, int attempt) {

        static final Completion ALREADY_HANDLED = new Completion(null, 0);

        boolean alreadyHandled() {
            return status == null;
        }
    }
}
