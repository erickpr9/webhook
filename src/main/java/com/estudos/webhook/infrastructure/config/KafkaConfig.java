package com.estudos.webhook.infrastructure.config;

import com.estudos.webhook.application.port.NotificationPublisher;
import com.estudos.webhook.application.port.WebhookEventPublisher;
import com.estudos.webhook.infrastructure.messaging.KafkaMessageMapper;
import com.estudos.webhook.infrastructure.messaging.KafkaNotificationPublisher;
import com.estudos.webhook.infrastructure.messaging.KafkaRecordSender;
import com.estudos.webhook.infrastructure.messaging.KafkaTopics;
import com.estudos.webhook.infrastructure.messaging.KafkaWebhookEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Tópicos e publicadores da esteira.
 *
 * <p>Os tópicos principais são criados na inicialização (pelo {@code KafkaAdmin} do Spring Boot),
 * com o número de partições configurado. Os tópicos de retry e de dead-letter são criados pelo
 * {@code @RetryableTopic} dos listeners.
 *
 * <p>Réplica 1 porque o ambiente local tem um broker só. Em produção seriam 3 réplicas com
 * {@code min.insync.replicas=2}: com {@code acks=all}, a mensagem confirmada sobrevive à perda de um broker.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    NewTopic paymentWebhooksTopic(KafkaPipelineProperties properties) {
        return TopicBuilder.name(KafkaTopics.PAYMENT_WEBHOOKS)
                .partitions(properties.partitions())
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentNotificationsTopic(KafkaPipelineProperties properties) {
        return TopicBuilder.name(KafkaTopics.PAYMENT_NOTIFICATIONS)
                .partitions(properties.partitions())
                .replicas(1)
                .build();
    }

    @Bean
    KafkaRecordSender kafkaRecordSender(KafkaTemplate<String, String> template, KafkaPipelineProperties properties) {
        return new KafkaRecordSender(template, properties.publishTimeout());
    }

    @Bean
    WebhookEventPublisher webhookEventPublisher(KafkaRecordSender sender, KafkaMessageMapper mapper) {
        return new KafkaWebhookEventPublisher(sender, mapper);
    }

    @Bean
    NotificationPublisher notificationPublisher(KafkaRecordSender sender, KafkaMessageMapper mapper) {
        return new KafkaNotificationPublisher(sender, mapper);
    }
}
