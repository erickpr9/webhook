package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.WorkerSettings;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.model.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Um ciclo do worker: reserva um lote de eventos prontos e processa um a um, cada um em sua
 * própria transação.
 *
 * <p>Pode rodar em várias instâncias ao mesmo tempo: a reserva usa {@code SKIP LOCKED}, então
 * cada evento é entregue a um único worker.
 */
public final class ProcessPendingWebhookEventsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessPendingWebhookEventsUseCase.class);

    private final WebhookEventRepository repository;
    private final ProcessWebhookEventUseCase processor;
    private final WorkerSettings settings;
    private final Clock clock;

    public ProcessPendingWebhookEventsUseCase(WebhookEventRepository repository, ProcessWebhookEventUseCase processor,
                                              WorkerSettings settings, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.processor = Objects.requireNonNull(processor);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @return quantidade de eventos reservados neste ciclo
     */
    public int execute() {
        Instant now = clock.instant();

        int abandoned = repository.deadLetterAbandoned(now, settings.retryPolicy().maxAttempts());
        if (abandoned > 0) {
            log.error("{} evento(s) movido(s) para DEAD: o processamento foi interrompido (worker caiu) em todas as "
                    + "tentativas", abandoned);
        }

        List<WebhookEvent> claimed = repository.claimDue(now, now.plus(settings.lease()), settings.batchSize());
        claimed.stream()
                .sorted(Comparator.comparing(WebhookEvent::receivedAt))
                .forEach(processor::execute);
        return claimed.size();
    }
}
