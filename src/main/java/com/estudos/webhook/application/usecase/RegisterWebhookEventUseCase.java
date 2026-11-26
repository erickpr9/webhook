package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.RegisterWebhookEventCommand;
import com.estudos.webhook.application.dto.RegistrationResult;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.model.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Grava um webhook autenticado na inbox, de forma idempotente.
 *
 * <p>Não executa regra de negócio: o processamento acontece depois, de forma assíncrona.
 * Assim o provedor recebe resposta rápida e nenhum evento válido se perde.
 */
public final class RegisterWebhookEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterWebhookEventUseCase.class);

    private final WebhookEventRepository repository;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public RegisterWebhookEventUseCase(WebhookEventRepository repository, WebhookMetrics metrics, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    public RegistrationResult execute(RegisterWebhookEventCommand command) {
        WebhookEvent event = WebhookEvent.received(UUID.randomUUID(), command.provider(), command.providerEventId(),
                command.eventType(), command.payload(), command.occurredAt(), clock.instant());

        boolean inserted = repository.insertIfAbsent(event);
        metrics.eventReceived(command.eventType(), inserted ? "accepted" : "duplicate");

        if (inserted) {
            log.info("Webhook recebido do provedor: evento {} ({}) gravado na inbox, será processado em instantes",
                    command.providerEventId(), command.eventType());
        } else {
            log.info("Webhook repetido: o provedor reenviou o evento {}, que já estava na inbox. Nada será "
                    + "processado de novo", command.providerEventId());
        }
        return new RegistrationResult(command.providerEventId(), !inserted);
    }
}
