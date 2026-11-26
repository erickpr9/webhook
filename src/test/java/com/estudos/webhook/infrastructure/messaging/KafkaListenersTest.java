package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.usecase.DeliverNotificationUseCase;
import com.estudos.webhook.application.usecase.ProcessWebhookEventUseCase;
import com.estudos.webhook.support.TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Os listeners traduzem o resultado do caso de uso em movimento na esteira: terminar sem erro
 * confirma o offset, {@link RetryLaterException} vai para o tópico de retry e
 * {@link DeadLetterException} vai direto para a dead-letter.
 */
class KafkaListenersTest {

    private final KafkaMessageMapper mapper = new KafkaMessageMapper(new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    private final ProcessWebhookEventUseCase processWebhookEvent = mock(ProcessWebhookEventUseCase.class);
    private final DeliverNotificationUseCase deliverNotification = mock(DeliverNotificationUseCase.class);

    private final PaymentWebhookListener webhookListener = new PaymentWebhookListener(processWebhookEvent, mapper);
    private final NotificationDeliveryListener notificationListener =
            new NotificationDeliveryListener(deliverNotification, mapper);

    private static final ReceivedWebhookEvent EVENT = TestData.message("payment.confirmed");
    private static final OutboxNotification NOTIFICATION = new OutboxNotification(UUID.randomUUID(),
            new NotificationMessage("PAYMENT_CONFIRMED", TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL",
                    "CONFIRMED", TestData.NOW));

    private static ConsumerRecord<String, String> consumed(ProducerRecord<String, String> produced, String topic) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 1, 42L, produced.key(), produced.value());
        produced.headers().forEach(header -> record.headers().add(header));
        return record;
    }

    private ConsumerRecord<String, String> webhookRecord() {
        return consumed(mapper.toRecord(EVENT), KafkaTopics.PAYMENT_WEBHOOKS);
    }

    private ConsumerRecord<String, String> notificationRecord() {
        return consumed(mapper.toRecord(NOTIFICATION), KafkaTopics.PAYMENT_NOTIFICATIONS);
    }

    @ParameterizedTest
    @EnumSource(value = ConsumeOutcome.class, names = {"COMPLETED", "ALREADY_HANDLED"})
    void handledWebhookIsAcknowledged(ConsumeOutcome outcome) {
        when(processWebhookEvent.execute(EVENT)).thenReturn(outcome);

        assertThatCode(() -> webhookListener.onMessage(webhookRecord())).doesNotThrowAnyException();
    }

    @Test
    void webhookToRetryMovesToTheRetryTopic() {
        when(processWebhookEvent.execute(EVENT)).thenReturn(ConsumeOutcome.RETRY);

        assertThatThrownBy(() -> webhookListener.onMessage(webhookRecord())).isInstanceOf(RetryLaterException.class);
    }

    @Test
    void deadWebhookGoesStraightToTheDeadLetter() {
        when(processWebhookEvent.execute(EVENT)).thenReturn(ConsumeOutcome.DEAD);

        assertThatThrownBy(() -> webhookListener.onMessage(webhookRecord())).isInstanceOf(DeadLetterException.class);
    }

    @Test
    void malformedWebhookNeverReachesTheUseCase() {
        var record = new ConsumerRecord<>(KafkaTopics.PAYMENT_WEBHOOKS, 0, 0L, "k", "{}");

        assertThatThrownBy(() -> webhookListener.onMessage(record)).isInstanceOf(MalformedMessageException.class);
        verifyNoInteractions(processWebhookEvent);
    }

    @Test
    void webhookInTheDeadLetterIsRecordedWithTheReason() {
        ConsumerRecord<String, String> record = consumed(mapper.toRecord(EVENT), KafkaTopics.PAYMENT_WEBHOOKS + "-dlt");
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "exhausted".getBytes(StandardCharsets.UTF_8));

        webhookListener.onDeadLetter(record);

        verify(processWebhookEvent).deadLettered(EVENT, "exhausted");
    }

    @Test
    void malformedWebhookInTheDeadLetterIsOnlyLogged() {
        assertThatCode(() -> webhookListener.onDeadLetter(
                new ConsumerRecord<>(KafkaTopics.PAYMENT_WEBHOOKS + "-dlt", 0, 0L, "k", "{}")))
                .doesNotThrowAnyException();
        verifyNoInteractions(processWebhookEvent);
    }

    @ParameterizedTest
    @EnumSource(value = ConsumeOutcome.class, names = {"COMPLETED", "ALREADY_HANDLED"})
    void deliveredNotificationIsAcknowledged(ConsumeOutcome outcome) {
        when(deliverNotification.execute(NOTIFICATION)).thenReturn(outcome);

        assertThatCode(() -> notificationListener.onMessage(notificationRecord())).doesNotThrowAnyException();
    }

    @Test
    void notificationOutcomesMoveTheMessage() {
        when(deliverNotification.execute(any())).thenReturn(ConsumeOutcome.RETRY, ConsumeOutcome.DEAD);

        assertThatThrownBy(() -> notificationListener.onMessage(notificationRecord()))
                .isInstanceOf(RetryLaterException.class);
        assertThatThrownBy(() -> notificationListener.onMessage(notificationRecord()))
                .isInstanceOf(DeadLetterException.class);
    }

    @Test
    void notificationInTheDeadLetterIsRecorded() {
        ConsumerRecord<String, String> record = consumed(mapper.toRecord(NOTIFICATION),
                KafkaTopics.PAYMENT_NOTIFICATIONS + "-dlt");

        notificationListener.onDeadLetter(record);

        verify(deliverNotification).deadLettered(NOTIFICATION, "Moved to the dead-letter topic");
    }

    @Test
    void malformedNotificationInTheDeadLetterIsOnlyLogged() {
        assertThatCode(() -> notificationListener.onDeadLetter(
                new ConsumerRecord<>(KafkaTopics.PAYMENT_NOTIFICATIONS + "-dlt", 0, 0L, "k", "{}")))
                .doesNotThrowAnyException();
        verifyNoInteractions(deliverNotification);
    }
}
