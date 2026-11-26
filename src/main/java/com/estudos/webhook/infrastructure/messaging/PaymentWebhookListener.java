package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.usecase.ProcessWebhookEventUseCase;
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
 * Consumer dos webhooks: lê {@value KafkaTopics#PAYMENT_WEBHOOKS} e entrega cada mensagem ao
 * {@link ProcessWebhookEventUseCase}.
 *
 * <p>Retentativa sem bloqueio: uma falha transitória move a mensagem para um tópico de retry
 * ({@code payment-webhooks-retry-<atraso>}), e a partição principal segue andando. Erro permanente
 * ou tentativas esgotadas vão direto para {@code payment-webhooks-dlt}.
 *
 * <p>O offset só é confirmado depois que este método termina ({@code ack-mode: record}). Se a
 * aplicação cair no meio, o Kafka entrega a mensagem de novo e o caso de uso evita o efeito em dobro.
 */
@Component
public class PaymentWebhookListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookListener.class);

    private final ProcessWebhookEventUseCase processWebhookEvent;
    private final KafkaMessageMapper mapper;

    public PaymentWebhookListener(ProcessWebhookEventUseCase processWebhookEvent, KafkaMessageMapper mapper) {
        this.processWebhookEvent = processWebhookEvent;
        this.mapper = mapper;
    }

    @RetryableTopic(
            attempts = "${webhook.kafka.events.retry.attempts}",
            backoff = @Backoff(
                    delayExpression = "${webhook.kafka.events.retry.delay-ms}",
                    multiplierExpression = "${webhook.kafka.events.retry.multiplier}",
                    maxDelayExpression = "${webhook.kafka.events.retry.max-delay-ms}"),
            numPartitions = "${webhook.kafka.partitions}",
            replicationFactor = "1",
            exclude = {DeadLetterException.class, MalformedMessageException.class},
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            autoStartDltHandler = "${webhook.kafka.listeners-enabled}")
    @KafkaListener(
            id = "payment-webhook-processor",
            topics = KafkaTopics.PAYMENT_WEBHOOKS,
            groupId = KafkaTopics.WEBHOOK_PROCESSOR_GROUP,
            concurrency = "${webhook.kafka.partitions}",
            autoStartup = "${webhook.kafka.listeners-enabled}")
    public void onMessage(ConsumerRecord<String, String> record) {
        ReceivedWebhookEvent event = mapper.toWebhookEvent(record);
        log.info("Evento {} consumido de {} (partição {}, offset {}, chave {})", event.providerEventId(),
                record.topic(), record.partition(), record.offset(), record.key());

        ConsumeOutcome outcome = processWebhookEvent.execute(event);
        switch (outcome) {
            case COMPLETED, ALREADY_HANDLED -> {
                // Termina sem exceção: o offset é confirmado.
            }
            case RETRY -> throw new RetryLaterException("Event " + event.providerEventId() + " will be retried");
            case DEAD -> throw new DeadLetterException("Event " + event.providerEventId() + " is dead");
        }
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String reason = KafkaMessageMapper.deadLetterReason(record);
        try {
            ReceivedWebhookEvent event = mapper.toWebhookEvent(record);
            processWebhookEvent.deadLettered(event, reason);
            log.error("Evento {} chegou à dead-letter {} (partição {}, offset {}). Motivo: {}. Use o reprocessamento "
                    + "do Admin depois de corrigir a causa", event.providerEventId(), record.topic(),
                    record.partition(), record.offset(), reason);
        } catch (MalformedMessageException malformed) {
            log.error("Mensagem fora do formato chegou à dead-letter {} (partição {}, offset {}): {}. Nada foi gravado",
                    record.topic(), record.partition(), record.offset(), malformed.getMessage());
        }
    }
}
