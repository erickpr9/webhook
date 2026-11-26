package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.usecase.RelayPendingNotificationsUseCase;
import com.estudos.webhook.infrastructure.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara periodicamente o relay do outbox. Desative com {@code webhook.relay.enabled=false}.
 *
 * <p>Cada ciclo esvazia o que estiver pendente, em lotes, até um limite de lotes por ciclo para não
 * monopolizar a thread de agendamento.
 */
@Component
@ConditionalOnProperty(prefix = "webhook.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRelayJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRelayJob.class);

    static final int MAX_BATCHES_PER_CYCLE = 20;

    private final RelayPendingNotificationsUseCase useCase;
    private final int batchSize;

    public NotificationRelayJob(RelayPendingNotificationsUseCase useCase,
                                RelayProperties properties) {
        this.useCase = useCase;
        this.batchSize = properties.batchSize();
    }

    @Scheduled(fixedDelayString = "${webhook.relay.interval}", initialDelayString = "${webhook.relay.interval}")
    public void run() {
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_CYCLE; batch++) {
                if (useCase.execute() < batchSize) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            // Broker ou banco fora: as notificações continuam pendentes e o próximo ciclo tenta de novo.
            log.error("Falha no relay do outbox; as notificações continuam pendentes para o próximo ciclo: {}",
                    e.getMessage());
        }
    }
}
