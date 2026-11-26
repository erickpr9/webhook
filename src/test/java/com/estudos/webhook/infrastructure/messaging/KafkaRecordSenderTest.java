package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.exception.EventPublishingException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaRecordSenderTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final KafkaRecordSender sender = new KafkaRecordSender(template, Duration.ofMillis(200));
    private final ProducerRecord<String, String> record = new ProducerRecord<>("topic", "key", "value");

    @Test
    void returnsTheBrokerAcknowledgement() {
        var metadata = new RecordMetadata(new TopicPartition("topic", 2), 41, 0, 0L, 3, 5);
        when(template.send(record)).thenReturn(CompletableFuture.completedFuture(new SendResult<>(record, metadata)));

        assertThat(sender.send(record).partition()).isEqualTo(2);
    }

    @Test
    void rejectedMessageBecomesPublishingFailure() {
        when(template.send(record)).thenReturn(CompletableFuture.failedFuture(new KafkaException("not authorized")));

        assertThatThrownBy(() -> sender.send(record))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    void missingAcknowledgementTimesOut() {
        when(template.send(record)).thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> sender.send(record))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining("within 200 ms");
    }

    @Test
    void synchronousFailureBecomesPublishingFailure() {
        when(template.send(record)).thenThrow(new KafkaException("metadata not available"));

        assertThatThrownBy(() -> sender.send(record))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining("metadata not available");
    }

    @Test
    void interruptionIsPreserved() {
        when(template.send(record)).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> sender.send(record)).isInstanceOf(EventPublishingException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
