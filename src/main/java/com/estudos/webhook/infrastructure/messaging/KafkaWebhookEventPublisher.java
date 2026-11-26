package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.port.WebhookEventPublisher;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Publica os webhooks recebidos no tópico {@value KafkaTopics#PAYMENT_WEBHOOKS}.
 */
public class KafkaWebhookEventPublisher implements WebhookEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaWebhookEventPublisher.class);

    private final KafkaRecordSender sender;
    private final KafkaMessageMapper mapper;

    public KafkaWebhookEventPublisher(KafkaRecordSender sender, KafkaMessageMapper mapper) {
        this.sender = Objects.requireNonNull(sender);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void publish(ReceivedWebhookEvent event) {
        RecordMetadata metadata = sender.send(mapper.toRecord(event));
        log.debug("Evento {} gravado no tópico {} (chave {}, partição {}, offset {})", event.providerEventId(),
                metadata.topic(), event.partitionKey(), metadata.partition(), metadata.offset());
    }
}
