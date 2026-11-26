package com.estudos.webhook.infrastructure.config;

import com.estudos.webhook.application.port.NotificationSender;
import com.estudos.webhook.infrastructure.notification.HttpNotificationSender;
import com.estudos.webhook.infrastructure.notification.LoggingNotificationSender;
import com.estudos.webhook.shared.security.HmacSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;

/**
 * Escolhe como as notificações saem: webhook HTTP para o cliente, quando há URL configurada,
 * ou apenas log.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationSenderConfig.class);

    @Bean
    NotificationSender notificationSender(NotificationDeliveryProperties properties,
                                          RestClient.Builder restClientBuilder,
                                          ObjectMapper objectMapper, Clock clock) {
        if (!properties.deliveryEnabled()) {
            log.info("Nenhuma URL de loja configurada (webhook.notification.endpoint-url): os avisos só vão para o log");
            return new LoggingNotificationSender();
        }
        String secret = properties.secret();
        if (secret == null || secret.length() < NotificationDeliveryProperties.MIN_SECRET_LENGTH) {
            throw new IllegalStateException("webhook.notification.secret must have at least "
                    + NotificationDeliveryProperties.MIN_SECRET_LENGTH + " characters when endpoint-url is set");
        }

        // Cliente HTTP simples, com timeouts explícitos: um cliente lento não pode prender o worker.
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        URI endpoint = URI.create(properties.endpointUrl());
        log.info("Os avisos de pagamento serão enviados à loja como webhooks assinados em {}", endpoint);
        return new HttpNotificationSender(restClientBuilder.requestFactory(requestFactory), endpoint,
                new HmacSigner(secret), objectMapper, clock);
    }
}
