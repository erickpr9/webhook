package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.usecase.DispatchPendingNotificationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara periodicamente o envio das notificações do outbox. Desative com
 * {@code webhook.worker.notifications.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "webhook.worker.notifications", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class DispatchNotificationsJob {

    private static final Logger log = LoggerFactory.getLogger(DispatchNotificationsJob.class);

    private final DispatchPendingNotificationsUseCase useCase;

    public DispatchNotificationsJob(DispatchPendingNotificationsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${webhook.worker.notifications.interval}",
            initialDelayString = "${webhook.worker.notifications.interval}")
    public void run() {
        try {
            int claimed = useCase.execute();
            if (claimed > 0) {
                log.debug("Ciclo de envio reservou {} aviso(s)", claimed);
            }
        } catch (RuntimeException e) {
            log.error("Falha no ciclo de envio de avisos; o próximo ciclo tenta de novo", e);
        }
    }
}
