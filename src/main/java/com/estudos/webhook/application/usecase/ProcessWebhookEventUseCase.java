package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.HandlingOutcome;
import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.exception.FailureClassifier;
import com.estudos.webhook.application.exception.LeaseLostException;
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

/**
 * Processa um evento já reservado da inbox.
 *
 * <p>As alterações de domínio, o outbox e a conclusão do evento ficam na mesma transação:
 * ou tudo é gravado, ou nada. Em caso de falha, a transação é desfeita e a falha é registrada
 * em uma transação separada (retentativa ou DEAD).
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

    /**
     * @param event evento em {@link WebhookEventStatus#PROCESSING}, reservado por este worker
     */
    public void execute(WebhookEvent event) {
        long startedAt = System.nanoTime();
        MDC.put(MDC_EVENT_ID, event.providerEventId());
        MDC.put(MDC_EVENT_TYPE, event.eventType());
        MDC.put(MDC_ATTEMPT, String.valueOf(event.attempts()));
        try {
            WebhookEventStatus finalStatus = transactions.inTransaction(() -> handle(event));
            metrics.eventCompleted(event.eventType(), finalStatus);
            log.info("Evento concluído com status {} (tentativa {})", finalStatus, event.attempts());
        } catch (LeaseLostException leaseLost) {
            log.warn("O tempo de reserva do evento expirou e outro worker assumiu. As alterações desta tentativa "
                    + "foram desfeitas");
        } catch (RuntimeException failure) {
            recordFailure(event, failure);
        } finally {
            metrics.processingTime(event.eventType(), Duration.ofNanos(System.nanoTime() - startedAt));
            MDC.remove(MDC_EVENT_ID);
            MDC.remove(MDC_EVENT_TYPE);
            MDC.remove(MDC_ATTEMPT);
        }
    }

    private WebhookEventStatus handle(WebhookEvent event) {
        Optional<PaymentEventType> type = PaymentEventType.fromCode(event.eventType());
        if (type.isEmpty()) {
            log.info("Evento ignorado: a plataforma não trata eventos do tipo '{}'", event.eventType());
            complete(event, WebhookEventStatus.IGNORED, "Unsupported event type");
            return WebhookEventStatus.IGNORED;
        }
        PaymentEventData data = payloadReader.read(event.payload());
        HandlingOutcome outcome = handlers.handlerFor(type.get()).handle(data, event.occurredAt());
        complete(event, WebhookEventStatus.PROCESSED, outcome.note());
        return WebhookEventStatus.PROCESSED;
    }

    private void complete(WebhookEvent event, WebhookEventStatus status, String note) {
        if (!repository.complete(event.id(), event.attempts(), status, clock.instant(), note)) {
            throw new LeaseLostException(event.id());
        }
    }

    private void recordFailure(WebhookEvent event, RuntimeException failure) {
        String error = FailureClassifier.describe(failure);
        boolean retryable = FailureClassifier.isRetryable(failure);
        boolean recorded;

        if (!retryable || retryPolicy.isExhausted(event.attempts())) {
            recorded = repository.markDead(event.id(), event.attempts(), error);
            metrics.eventDead(event.eventType());
            if (retryable) {
                // Erro inesperado: o stack trace ajuda a investigar.
                log.error("Evento movido para DEAD após {} tentativa(s): as tentativas acabaram. {}",
                        event.attempts(), error, failure);
            } else {
                log.error("Evento movido para DEAD na tentativa {}: erro permanente, tentar de novo não resolveria. {}",
                        event.attempts(), error);
            }
        } else {
            Duration delay = retryPolicy.delayAfter(event.attempts());
            Instant nextAttemptAt = clock.instant().plus(delay);
            recorded = repository.scheduleRetry(event.id(), event.attempts(), nextAttemptAt, error);
            metrics.eventRetryScheduled(event.eventType());
            log.warn("Falha ao processar o evento (tentativa {} de {}). Nova tentativa em {}s: {}",
                    event.attempts(), retryPolicy.maxAttempts(), delay.toSeconds(), error);
        }

        if (!recorded) {
            log.warn("Não foi possível registrar a falha: outro worker assumiu o evento");
        }
    }
}
