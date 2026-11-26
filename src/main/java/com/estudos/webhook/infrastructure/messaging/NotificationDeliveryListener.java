package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.usecase.DeliverNotificationUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumer das notificações: lê {@value KafkaTopics#PAYMENT_NOTIFICATIONS} e entrega cada uma à
 * loja pelo {@link DeliverNotificationUseCase}.
 *
 * <p>Loja fora do ar ou lenta: a mensagem passa pelos tópicos de retry
 * ({@code payment-notifications-retry-<atraso>}). Loja recusando (4xx definitivo) ou tentativas
 * esgotadas: {@code payment-notifications-dlt}.
 */
@Component
public class NotificationDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryListener.class);

    private final DeliverNotificationUseCase deliverNotification;
    private final KafkaMessageMapper mapper;

    public NotificationDeliveryListener(DeliverNotificationUseCase deliverNotification, KafkaMessageMapper mapper) {
        this.deliverNotification = deliverNotification;
        this.mapper = mapper;
    }

    @RetryableTopic(
            attempts = "${webhook.kafka.notifications.retry.attempts}",
            backoff = @Backoff(
                    delayExpression = "${webhook.kafka.notifications.retry.delay-ms}",
                    multiplierExpression = "${webhook.kafka.notifications.retry.multiplier}",
                    maxDelayExpression = "${webhook.kafka.notifications.retry.max-delay-ms}"),
            numPartitions = "${webhook.kafka.partitions}",
            replicationFactor = "1",
            exclude = {DeadLetterException.class, MalformedMessageException.class},
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "${webhook.kafka.listeners-enabled}")
    @KafkaListener(
            id = "notification-dispatcher",
            topics = KafkaTopics.PAYMENT_NOTIFICATIONS,
            groupId = KafkaTopics.NOTIFICATION_DISPATCHER_GROUP,
            concurrency = "${webhook.kafka.partitions}",
            autoStartup = "${webhook.kafka.listeners-enabled}")
    public void onMessage(ConsumerRecord<String, String> record) {
        OutboxNotification notification = mapper.toNotification(record);
        log.info("Aviso {} (pedido {}) consumido de {} (partição {}, offset {})", notification.id(),
                notification.message().orderId(), record.topic(), record.partition(), record.offset());

        ConsumeOutcome outcome = deliverNotification.execute(notification);
        switch (outcome) {
            case COMPLETED, ALREADY_HANDLED -> {
                // Termina sem exceção: o offset é confirmado.
            }
            case RETRY -> throw new RetryLaterException("Notification " + notification.id() + " will be retried");
            case DEAD -> throw new DeadLetterException("Notification " + notification.id() + " is dead");
        }
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String reason = KafkaMessageMapper.deadLetterReason(record);
        try {
            OutboxNotification notification = mapper.toNotification(record);
            deliverNotification.deadLettered(notification, reason);
            log.error("Aviso {} (pedido {}) chegou à dead-letter {} (partição {}, offset {}). Motivo: {}",
                    notification.id(), notification.message().orderId(), record.topic(), record.partition(),
                    record.offset(), reason);
        } catch (MalformedMessageException malformed) {
            log.error("Mensagem fora do formato chegou à dead-letter {} (partição {}, offset {}): {}",
                    record.topic(), record.partition(), record.offset(), malformed.getMessage());
        }
    }
}
