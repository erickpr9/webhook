package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

import static com.estudos.webhook.infrastructure.messaging.KafkaTopics.HEADER_EVENT_ID;
import static com.estudos.webhook.infrastructure.messaging.KafkaTopics.HEADER_EVENT_TYPE;
import static com.estudos.webhook.infrastructure.messaging.KafkaTopics.HEADER_NOTIFICATION_ID;
import static com.estudos.webhook.infrastructure.messaging.KafkaTopics.HEADER_OCCURRED_AT;
import static com.estudos.webhook.infrastructure.messaging.KafkaTopics.HEADER_PROVIDER;
import static com.estudos.webhook.infrastructure.messaging.KafkaTopics.HEADER_RECEIVED_AT;

/**
 * Converte as mensagens da aplicação em registros do Kafka, e vice-versa.
 *
 * <p>Webhooks: o valor é o corpo original, sem nenhuma transformação, e os metadados vão em
 * headers. Notificações: o valor é a notificação em JSON e o id vai em header. Nos dois casos a
 * chave é o pedido, para manter a ordem por pedido dentro de uma partição.
 */
@Component
public class KafkaMessageMapper {

    private final ObjectMapper objectMapper;

    public KafkaMessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public ProducerRecord<String, String> toRecord(ReceivedWebhookEvent event) {
        Headers headers = new RecordHeaders()
                .add(HEADER_PROVIDER, bytes(event.provider()))
                .add(HEADER_EVENT_ID, bytes(event.providerEventId()))
                .add(HEADER_EVENT_TYPE, bytes(event.eventType()))
                .add(HEADER_OCCURRED_AT, bytes(event.occurredAt().toString()))
                .add(HEADER_RECEIVED_AT, bytes(event.receivedAt().toString()));
        return new ProducerRecord<>(KafkaTopics.PAYMENT_WEBHOOKS, null, event.partitionKey(), event.payload(),
                headers);
    }

    /**
     * @throws MalformedMessageException se faltar header obrigatório ou algum valor for inválido
     */
    public ReceivedWebhookEvent toWebhookEvent(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            throw new MalformedMessageException("Webhook message has no value");
        }
        return new ReceivedWebhookEvent(
                header(record, HEADER_PROVIDER),
                header(record, HEADER_EVENT_ID),
                header(record, HEADER_EVENT_TYPE),
                instantHeader(record, HEADER_OCCURRED_AT),
                instantHeader(record, HEADER_RECEIVED_AT),
                record.key() != null ? record.key() : header(record, HEADER_EVENT_ID),
                record.value());
    }

    public ProducerRecord<String, String> toRecord(OutboxNotification notification) {
        Headers headers = new RecordHeaders().add(HEADER_NOTIFICATION_ID, bytes(notification.id().toString()));
        return new ProducerRecord<>(KafkaTopics.PAYMENT_NOTIFICATIONS, null, notification.message().orderId(),
                toJson(notification.message()), headers);
    }

    /**
     * @throws MalformedMessageException se faltar o id ou o JSON for inválido
     */
    public OutboxNotification toNotification(ConsumerRecord<String, String> record) {
        UUID id;
        try {
            id = UUID.fromString(header(record, HEADER_NOTIFICATION_ID));
        } catch (IllegalArgumentException e) {
            throw new MalformedMessageException("Header " + HEADER_NOTIFICATION_ID + " is not a UUID");
        }
        try {
            return new OutboxNotification(id, objectMapper.readValue(record.value(), NotificationMessage.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new MalformedMessageException("Notification message is not valid JSON");
        }
    }

    /**
     * Motivo registrado pelo Spring Kafka ao mover a mensagem para a dead-letter (header
     * {@value KafkaHeaders#DLT_EXCEPTION_MESSAGE}).
     */
    public static String deadLetterReason(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (header == null || header.value() == null || header.value().length == 0) {
            return "Moved to the dead-letter topic";
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String toJson(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize notification", e);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length == 0) {
            throw new MalformedMessageException("Missing header " + name);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Instant instantHeader(ConsumerRecord<String, String> record, String name) {
        try {
            return Instant.parse(header(record, name));
        } catch (DateTimeParseException e) {
            throw new MalformedMessageException("Header " + name + " is not an ISO-8601 instant");
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
