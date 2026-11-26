package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.dto.WebhookEventView;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.estudos.webhook.application.port.WebhookEventPublisher;
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
 * Recoloca na esteira um evento DEAD, após correção manual da causa (ex.: pagamento criado,
 * dado corrigido). As tentativas são zeradas e o payload guardado é publicado de novo.
 */
public final class ReprocessWebhookEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReprocessWebhookEventUseCase.class);

    private final WebhookEventRepository repository;
    private final WebhookEventPublisher publisher;
    private final PaymentEventPayloadReader payloadReader;
    private final Clock clock;

    public ReprocessWebhookEventUseCase(WebhookEventRepository repository, WebhookEventPublisher publisher,
                                        PaymentEventPayloadReader payloadReader, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.publisher = Objects.requireNonNull(publisher);
        this.payloadReader = Objects.requireNonNull(payloadReader);
        this.clock = Objects.requireNonNull(clock);
    }

    public WebhookEventView execute(UUID id) {
        WebhookEvent event = repository.findById(id).orElseThrow(() -> new WebhookEventNotFoundException(id));
        if (event.status() != WebhookEventStatus.DEAD || !repository.requeue(id, clock.instant())) {
            // A segunda condição cobre a corrida em que outro operador reprocessou o mesmo evento.
            WebhookEventStatus current = repository.findById(id).map(WebhookEvent::status).orElse(event.status());
            throw new WebhookEventNotReprocessableException(id, current);
        }

        // Se a publicação falhar, o evento fica RETRY_SCHEDULED sem mensagem na esteira; o operador
        // vê o 503 e pode tentar de novo depois (o reprocessamento só exige o status DEAD na primeira vez).
        publisher.publish(new ReceivedWebhookEvent(event.provider(), event.providerEventId(), event.eventType(),
                event.occurredAt(), clock.instant(), partitionKey(event), event.payload()));

        log.info("Evento {} recolocado na esteira para reprocessamento, com as tentativas zeradas",
                event.providerEventId());
        return repository.findById(id).map(WebhookEventView::from).orElseThrow(() -> new WebhookEventNotFoundException(id));
    }

    /** Mesma chave da publicação original: o pedido, quando o payload é legível; senão, o evento. */
    private String partitionKey(WebhookEvent event) {
        try {
            String orderId = payloadReader.read(event.payload()).orderId();
            return orderId == null || orderId.isBlank() ? event.providerEventId() : orderId;
        } catch (RuntimeException unreadable) {
            return event.providerEventId();
        }
    }
}
