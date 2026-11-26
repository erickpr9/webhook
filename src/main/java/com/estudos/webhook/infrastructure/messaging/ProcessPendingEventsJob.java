package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.usecase.ProcessPendingWebhookEventsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara periodicamente o processamento da inbox. Desative com
 * {@code webhook.worker.events.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "webhook.worker.events", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProcessPendingEventsJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessPendingEventsJob.class);

    private final ProcessPendingWebhookEventsUseCase useCase;

    public ProcessPendingEventsJob(ProcessPendingWebhookEventsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${webhook.worker.events.interval}",
            initialDelayString = "${webhook.worker.events.interval}")
    public void run() {
        try {
            int claimed = useCase.execute();
            if (claimed > 0) {
                log.debug("Ciclo do worker reservou {} evento(s)", claimed);
            }
        } catch (RuntimeException e) {
            // Uma falha no ciclo (ex.: banco fora) não pode derrubar o agendamento.
            log.error("Falha no ciclo do worker de eventos; o próximo ciclo tenta de novo", e);
        }
    }
}
