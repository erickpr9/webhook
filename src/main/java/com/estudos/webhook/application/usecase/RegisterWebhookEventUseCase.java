package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.dto.RegisterWebhookEventCommand;
import com.estudos.webhook.application.exception.EventPublishingException;
import com.estudos.webhook.application.port.WebhookEventPublisher;
import com.estudos.webhook.application.port.WebhookMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;

/**
 * Coloca um webhook autenticado na esteira, para processamento assíncrono.
 *
 * <p>Não executa regra de negócio nem consulta o banco: só publica e espera a confirmação de que
 * a mensagem está gravada. Assim o provedor recebe resposta rápida e nenhum evento aceito se perde.
 *
 * <p>Por não consultar o banco, a recepção não sabe se o evento é repetido: a deduplicação
 * acontece no consumo ({@link ProcessWebhookEventUseCase}).
 */
public final class RegisterWebhookEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterWebhookEventUseCase.class);

    private final WebhookEventPublisher publisher;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public RegisterWebhookEventUseCase(WebhookEventPublisher publisher, WebhookMetrics metrics, Clock clock) {
        this.publisher = Objects.requireNonNull(publisher);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @throws EventPublishingException se a esteira não confirmou a publicação a tempo
     */
    public void execute(RegisterWebhookEventCommand command) {
        ReceivedWebhookEvent event = new ReceivedWebhookEvent(command.provider(), command.providerEventId(),
                command.eventType(), command.occurredAt(), clock.instant(), command.partitionKey(), command.payload());
        try {
            publisher.publish(event);
        } catch (EventPublishingException e) {
            metrics.eventReceived(command.eventType(), "unavailable");
            log.error("Webhook {} NÃO aceito: a esteira não confirmou a publicação ({}). O provedor recebe 503 e "
                    + "reenvia depois", command.providerEventId(), e.getMessage());
            throw e;
        }
        metrics.eventReceived(command.eventType(), "accepted");
        log.info("Webhook recebido do provedor: evento {} ({}) publicado na esteira, será processado em instantes",
                command.providerEventId(), command.eventType());
    }
}
