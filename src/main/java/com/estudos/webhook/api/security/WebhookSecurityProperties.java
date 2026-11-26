package com.estudos.webhook.api.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuração do webhook de pagamentos ({@code webhook.payment.*}). Validada na inicialização:
 * a aplicação não sobe sem um segredo de pelo menos 32 caracteres.
 *
 * @param provider    nome do provedor gravado na inbox
 * @param secret      segredo HMAC compartilhado com o provedor
 * @param tolerance   diferença máxima aceita entre o {@code X-Timestamp} e o relógio local
 * @param maxBodySize tamanho máximo do corpo
 */
@Validated
@ConfigurationProperties(prefix = "webhook.payment")
public record WebhookSecurityProperties(
        @NotBlank String provider,
        @NotBlank @Size(min = 32) String secret,
        @NotNull Duration tolerance,
        @NotNull DataSize maxBodySize) {

    /** Não expõe o segredo em logs ou no Actuator. */
    @Override
    public String toString() {
        return "WebhookSecurityProperties[provider=" + provider + ", secret=***, tolerance=" + tolerance
                + ", maxBodySize=" + maxBodySize + "]";
    }
}
