package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.exception.EventPublishingException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Envio síncrono ao Kafka: publica e espera a confirmação do broker ({@code acks=all}).
 *
 * <p>Esperar a confirmação é o que permite dizer "aceito" ao provedor e "publicado" no outbox.
 * Qualquer falha (broker fora do ar, timeout, erro de autorização) vira
 * {@link EventPublishingException}.
 */
public final class KafkaRecordSender {

    private final KafkaTemplate<String, String> template;
    private final Duration timeout;

    public KafkaRecordSender(KafkaTemplate<String, String> template, Duration timeout) {
        this.template = Objects.requireNonNull(template);
        this.timeout = Objects.requireNonNull(timeout);
    }

    public RecordMetadata send(ProducerRecord<String, String> record) {
        try {
            return template.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS).getRecordMetadata();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishingException("Interrupted while publishing to " + record.topic(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EventPublishingException("Kafka rejected the message for " + record.topic() + ": "
                    + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new EventPublishingException("Kafka did not acknowledge the message for " + record.topic()
                    + " within " + timeout.toMillis() + " ms", e);
        } catch (RuntimeException e) {
            // O próprio send() falha de forma síncrona quando não consegue os metadados do tópico.
            throw new EventPublishingException("Could not publish to " + record.topic() + ": " + e.getMessage(), e);
        }
    }
}
