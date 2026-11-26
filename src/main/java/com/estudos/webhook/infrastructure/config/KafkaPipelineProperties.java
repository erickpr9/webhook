package com.estudos.webhook.infrastructure.config;

import com.estudos.webhook.domain.service.RetryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuração da esteira sobre o Kafka ({@code webhook.kafka.*}). Validada na inicialização.
 *
 * <p>Os mesmos valores de retentativa alimentam os tópicos de retry ({@code @RetryableTopic}, via
 * placeholders) e a {@link RetryPolicy} usada pelos casos de uso, para que o registro no banco e o
 * broker concordem sobre quando a próxima tentativa acontece e quando desistir.
 *
 * @param partitions       partições de cada tópico (e de cada tópico de retry)
 * @param publishTimeout   espera máxima pela confirmação do broker ao publicar
 * @param listenersEnabled liga ou desliga os consumers
 * @param events           consumo dos webhooks
 * @param notifications    entrega das notificações à loja
 */
@Validated
@ConfigurationProperties(prefix = "webhook.kafka")
public record KafkaPipelineProperties(
        @Min(1) int partitions,
        @NotNull Duration publishTimeout,
        boolean listenersEnabled,
        @Valid @NotNull Stage events,
        @Valid @NotNull Stage notifications) {

    /**
     * @param retry retentativa pelos tópicos de retry
     */
    public record Stage(@Valid @NotNull Retry retry) {
    }

    /**
     * @param attempts   tentativas no total (a primeira + as retentativas)
     * @param delayMs    atraso antes da primeira retentativa, em milissegundos
     * @param multiplier fator de crescimento do atraso a cada retentativa
     * @param maxDelayMs atraso máximo, em milissegundos
     */
    public record Retry(@Min(1) int attempts, @Min(1) long delayMs, @DecimalMin("1.0") double multiplier,
                        @Min(1) long maxDelayMs) {

        public RetryPolicy toPolicy() {
            return RetryPolicy.exponential(attempts, Duration.ofMillis(delayMs), multiplier,
                    Duration.ofMillis(maxDelayMs));
        }
    }
}
