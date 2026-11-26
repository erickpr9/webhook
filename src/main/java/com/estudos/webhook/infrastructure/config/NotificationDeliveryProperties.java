package com.estudos.webhook.infrastructure.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Entrega das notificações ao cliente ({@code webhook.notification.*}).
 *
 * @param endpointUrl    URL da API do cliente; vazia, as notificações só vão para o log
 * @param secret         segredo HMAC compartilhado com o cliente (mínimo de 32 caracteres)
 * @param connectTimeout tempo máximo para abrir a conexão
 * @param readTimeout    tempo máximo esperando a resposta do cliente
 */
@Validated
@ConfigurationProperties(prefix = "webhook.notification")
public record NotificationDeliveryProperties(
        String endpointUrl,
        String secret,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    static final int MIN_SECRET_LENGTH = 32;

    public boolean deliveryEnabled() {
        return StringUtils.hasText(endpointUrl);
    }

    /** Não expõe o segredo em logs ou no Actuator. */
    @Override
    public String toString() {
        return "NotificationDeliveryProperties[endpointUrl=" + endpointUrl + ", secret=***, connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
