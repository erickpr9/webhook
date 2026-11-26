package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.support.TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaMessageMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final KafkaMessageMapper mapper = new KafkaMessageMapper(objectMapper);

    private static <K, V> ConsumerRecord<K, V> consumed(ProducerRecord<K, V> produced) {
        ConsumerRecord<K, V> record = new ConsumerRecord<>(produced.topic(), 0, 0L, produced.key(), produced.value());
        produced.headers().forEach(header -> record.headers().add(header));
        return record;
    }

    @Test
    void webhookTravelsWithOriginalBodyOrderKeyAndMetadataHeaders() {
        ReceivedWebhookEvent event = TestData.message("payment.confirmed");

        ProducerRecord<String, String> record = mapper.toRecord(event);

        assertThat(record.topic()).isEqualTo(KafkaTopics.PAYMENT_WEBHOOKS);
        assertThat(record.key()).isEqualTo(TestData.ORDER_ID);
        assertThat(record.value()).isEqualTo(event.payload());
        assertThat(new String(record.headers().lastHeader(KafkaTopics.HEADER_EVENT_ID).value(),
                StandardCharsets.UTF_8)).isEqualTo("evt_msg");
        assertThat(mapper.toWebhookEvent(consumed(record))).isEqualTo(event);
    }

    @Test
    void webhookWithoutMetadataIsMalformed() {
        var record = new ConsumerRecord<>(KafkaTopics.PAYMENT_WEBHOOKS, 0, 0L, "ord_1", "{}");

        assertThatThrownBy(() -> mapper.toWebhookEvent(record))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining(KafkaTopics.HEADER_PROVIDER);
    }

    @Test
    void webhookWithInvalidInstantOrNoValueIsMalformed() {
        ProducerRecord<String, String> produced = mapper.toRecord(TestData.message("payment.confirmed"));
        ConsumerRecord<String, String> record = consumed(produced);
        record.headers().remove(KafkaTopics.HEADER_OCCURRED_AT);
        record.headers().add(KafkaTopics.HEADER_OCCURRED_AT, "yesterday".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mapper.toWebhookEvent(record)).isInstanceOf(MalformedMessageException.class);
        assertThatThrownBy(() -> mapper.toWebhookEvent(
                new ConsumerRecord<String, String>(KafkaTopics.PAYMENT_WEBHOOKS, 0, 0L, "k", null)))
                .isInstanceOf(MalformedMessageException.class);
    }

    @Test
    void webhookWithoutKeyFallsBackToTheEventId() {
        ConsumerRecord<String, String> withKey = consumed(mapper.toRecord(TestData.message("payment.confirmed")));
        var record = new ConsumerRecord<String, String>(withKey.topic(), 0, 0L, null, withKey.value());
        withKey.headers().forEach(header -> record.headers().add(header));

        assertThat(mapper.toWebhookEvent(record).partitionKey()).isEqualTo("evt_msg");
    }

    @Test
    void notificationTravelsAsJsonKeyedByOrderWithItsId() {
        var notification = new OutboxNotification(UUID.randomUUID(), new NotificationMessage("PAYMENT_CONFIRMED",
                TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", TestData.NOW));

        ProducerRecord<String, String> record = mapper.toRecord(notification);

        assertThat(record.topic()).isEqualTo(KafkaTopics.PAYMENT_NOTIFICATIONS);
        assertThat(record.key()).isEqualTo(TestData.ORDER_ID);
        assertThat(record.value()).contains("\"orderId\":\"" + TestData.ORDER_ID + "\"");
        assertThat(mapper.toNotification(consumed(record))).isEqualTo(notification);
    }

    @Test
    void notificationWithBadIdOrJsonIsMalformed() {
        var badId = new ConsumerRecord<>(KafkaTopics.PAYMENT_NOTIFICATIONS, 0, 0L, "k", "{}");
        badId.headers().add(KafkaTopics.HEADER_NOTIFICATION_ID, "not-a-uuid".getBytes(StandardCharsets.UTF_8));
        var badJson = new ConsumerRecord<>(KafkaTopics.PAYMENT_NOTIFICATIONS, 0, 0L, "k", "not json");
        badJson.headers().add(KafkaTopics.HEADER_NOTIFICATION_ID,
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mapper.toNotification(badId)).isInstanceOf(MalformedMessageException.class);
        assertThatThrownBy(() -> mapper.toNotification(badJson)).isInstanceOf(MalformedMessageException.class);
    }

    @Test
    void deadLetterReasonComesFromTheSpringKafkaHeader() {
        var record = new ConsumerRecord<>(KafkaTopics.PAYMENT_WEBHOOKS + "-dlt", 0, 0L, "k", "{}");
        assertThat(KafkaMessageMapper.deadLetterReason(record)).isEqualTo("Moved to the dead-letter topic");

        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "Event evt_1 is dead".getBytes(StandardCharsets.UTF_8));
        assertThat(KafkaMessageMapper.deadLetterReason(record)).isEqualTo("Event evt_1 is dead");
    }

    @Test
    void headersAreIndependentPerRecord() {
        assertThat(mapper.toRecord(TestData.message("a")).headers())
                .isNotSameAs(mapper.toRecord(TestData.message("b")).headers())
                .isInstanceOf(RecordHeaders.class);
    }
}
