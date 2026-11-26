package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.dto.WorkerSettings;
import com.estudos.webhook.application.exception.FailureClassifier;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.NotificationSender;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.service.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Um ciclo do envio de notificações: reserva um lote do outbox e envia cada uma.
 *
 * <p>A entrega é "pelo menos uma vez": se o processo morrer entre o envio e a marcação como
 * enviada, a notificação será reenviada. O destinatário deve tolerar duplicatas.
 */
public final class DispatchPendingNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DispatchPendingNotificationsUseCase.class);

    private final NotificationOutbox outbox;
    private final NotificationSender sender;
    private final WorkerSettings settings;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public DispatchPendingNotificationsUseCase(NotificationOutbox outbox, NotificationSender sender,
                                               WorkerSettings settings, WebhookMetrics metrics, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.sender = Objects.requireNonNull(sender);
        this.settings = Objects.requireNonNull(settings);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @return quantidade de notificações reservadas neste ciclo
     */
    public int execute() {
        Instant now = clock.instant();
        RetryPolicy retryPolicy = settings.retryPolicy();

        int abandoned = outbox.deadLetterAbandoned(now, retryPolicy.maxAttempts());
        if (abandoned > 0) {
            log.error("{} aviso(s) à loja movido(s) para DEAD: o envio foi interrompido (worker caiu) em todas as "
                    + "tentativas", abandoned);
        }

        List<OutboxNotification> claimed = outbox.claimDue(now, now.plus(settings.lease()), settings.batchSize());
        claimed.forEach(notification -> dispatch(notification, retryPolicy));
        return claimed.size();
    }

    private void dispatch(OutboxNotification notification, RetryPolicy retryPolicy) {
        String type = notification.message().type();
        try {
            sender.send(notification.id(), notification.message());
            outbox.markSent(notification.id(), notification.attempts(), clock.instant());
            metrics.notificationSent(type);
        } catch (RuntimeException failure) {
            String error = FailureClassifier.describe(failure);
            boolean retryable = FailureClassifier.isRetryable(failure);
            String orderId = notification.message().orderId();
            if (!retryable || retryPolicy.isExhausted(notification.attempts())) {
                outbox.markDead(notification.id(), notification.attempts(), error);
                metrics.notificationFailed(type, true);
                log.error("Aviso {} à loja (pedido {}) movido para DEAD após {} tentativa(s): {}. {}",
                        notification.id(), orderId, notification.attempts(),
                        retryable ? "as tentativas acabaram" : "a loja recusou de forma definitiva", error);
            } else {
                Duration delay = retryPolicy.delayAfter(notification.attempts());
                Instant nextAttemptAt = clock.instant().plus(delay);
                outbox.scheduleRetry(notification.id(), notification.attempts(), nextAttemptAt, error);
                metrics.notificationFailed(type, false);
                log.warn("Não foi possível avisar a loja sobre o pedido {} (tentativa {} de {}). O aviso não se perde: "
                                + "nova tentativa em {}s. Motivo: {}", orderId, notification.attempts(),
                        retryPolicy.maxAttempts(), delay.toSeconds(), error);
            }
        }
    }
}
