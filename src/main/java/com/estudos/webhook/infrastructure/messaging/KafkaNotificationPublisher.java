package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.port.NotificationPublisher;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Publica as notificações do outbox no tópico {@value KafkaTopics#PAYMENT_NOTIFICATIONS}.
 */
public class KafkaNotificationPublisher implements NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaNotificationPublisher.class);

    private final KafkaRecordSender sender;
    private final KafkaMessageMapper mapper;

    public KafkaNotificationPublisher(KafkaRecordSender sender, KafkaMessageMapper mapper) {
        this.sender = Objects.requireNonNull(sender);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void publish(OutboxNotification notification) {
        RecordMetadata metadata = sender.send(mapper.toRecord(notification));
        log.debug("Aviso {} (pedido {}) gravado no tópico {} (partição {}, offset {})", notification.id(),
                notification.message().orderId(), metadata.topic(), metadata.partition(), metadata.offset());
    }
}
