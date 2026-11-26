package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.WebhookEventView;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.exception.WebhookEventNotFoundException;
import com.estudos.webhook.domain.exception.WebhookEventNotReprocessableException;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Recoloca na fila um evento DEAD, após correção manual da causa (ex.: pagamento criado,
 * dado corrigido). As tentativas são zeradas.
 */
public final class ReprocessWebhookEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReprocessWebhookEventUseCase.class);

    private final WebhookEventRepository repository;
    private final Clock clock;

    public ReprocessWebhookEventUseCase(WebhookEventRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public WebhookEventView execute(UUID id) {
        WebhookEvent event = repository.findById(id).orElseThrow(() -> new WebhookEventNotFoundException(id));
        if (event.status() != WebhookEventStatus.DEAD || !repository.requeue(id, clock.instant())) {
            // A segunda condição cobre a corrida em que outro operador reprocessou o mesmo evento.
            WebhookEventStatus current = repository.findById(id).map(WebhookEvent::status).orElse(event.status());
            throw new WebhookEventNotReprocessableException(id, current);
        }
        log.info("Evento {} recolocado na fila para reprocessamento, com as tentativas zeradas",
                event.providerEventId());
        return repository.findById(id).map(WebhookEventView::from).orElseThrow(() -> new WebhookEventNotFoundException(id));
    }
}
