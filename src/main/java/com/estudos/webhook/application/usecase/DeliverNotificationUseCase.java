package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.OutboxNotification;
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
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Entrega ao cliente uma notificação consumida da esteira.
 *
 * <p>A entrega é "pelo menos uma vez": se o processo morrer entre o envio e a marcação como
 * enviada, a mensagem é entregue de novo pelo broker e a notificação é reenviada. O destinatário
 * descarta a repetição pelo id da notificação, que é o mesmo em todas as tentativas.
 */
public final class DeliverNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliverNotificationUseCase.class);

    private final NotificationOutbox outbox;
    private final NotificationSender sender;
    private final RetryPolicy retryPolicy;
    private final WebhookMetrics metrics;
    private final Clock clock;

    public DeliverNotificationUseCase(NotificationOutbox outbox, NotificationSender sender, RetryPolicy retryPolicy,
                                      WebhookMetrics metrics, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.sender = Objects.requireNonNull(sender);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    public ConsumeOutcome execute(OutboxNotification notification) {
        OptionalInt started = outbox.startDelivery(notification.id());
        if (started.isEmpty()) {
            log.info("Aviso {} à loja (pedido {}) já tinha sido entregue ou descartado: mensagem repetida ignorada",
                    notification.id(), notification.message().orderId());
            return ConsumeOutcome.ALREADY_HANDLED;
        }
        int attempt = started.getAsInt();
        String type = notification.message().type();
        try {
            sender.send(notification.id(), notification.message());
        } catch (RuntimeException failure) {
            return recordFailure(notification, attempt, failure);
        }
        outbox.markSent(notification.id(), clock.instant());
        metrics.notificationSent(type);
        return ConsumeOutcome.COMPLETED;
    }

    /** Registra como DEAD uma notificação que chegou à dead-letter sem ter sido marcada. */
    public void deadLettered(OutboxNotification notification, String reason) {
        outbox.markDead(notification.id(), reason);
    }

    private ConsumeOutcome recordFailure(OutboxNotification notification, int attempt, RuntimeException failure) {
        String type = notification.message().type();
        String error = FailureClassifier.describe(failure);
        boolean retryable = FailureClassifier.isRetryable(failure);
        String orderId = notification.message().orderId();

        if (!retryable || retryPolicy.isExhausted(attempt)) {
            outbox.markDead(notification.id(), error);
            metrics.notificationFailed(type, true);
            log.error("Aviso {} à loja (pedido {}) movido para DEAD após {} tentativa(s): {}. {}",
                    notification.id(), orderId, attempt,
                    retryable ? "as tentativas acabaram" : "a loja recusou de forma definitiva", error);
            return ConsumeOutcome.DEAD;
        }
        Duration delay = retryPolicy.delayAfter(attempt);
        Instant nextAttemptAt = clock.instant().plus(delay);
        outbox.scheduleRetry(notification.id(), nextAttemptAt, error);
        metrics.notificationFailed(type, false);
        log.warn("Não foi possível avisar a loja sobre o pedido {} (tentativa {} de {}). O aviso não se perde: "
                        + "nova tentativa em {}s. Motivo: {}", orderId, attempt, retryPolicy.maxAttempts(),
                delay.toSeconds(), error);
        return ConsumeOutcome.RETRY;
    }
}
