package com.estudos.webhook.infrastructure.config;

import com.estudos.webhook.application.dto.WorkerSettings;
import com.estudos.webhook.domain.service.RetryPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Configuração dos workers ({@code webhook.worker.*}). Validada na inicialização.
 *
 * @param events        worker da inbox de webhooks
 * @param notifications worker do outbox de notificações
 */
@Validated
@ConfigurationProperties(prefix = "webhook.worker")
public record WorkerProperties(@Valid @NotNull Job events, @Valid @NotNull Job notifications) {

    /**
     * @param enabled     liga ou desliga o job agendado
     * @param interval    intervalo entre ciclos
     * @param batchSize   itens reservados por ciclo
     * @param lease       tempo de reserva de cada item
     * @param maxAttempts tentativas antes de DEAD
     * @param backoff     intervalos entre tentativas
     */
    public record Job(
            boolean enabled,
            @NotNull Duration interval,
            @Min(1) int batchSize,
            @NotNull Duration lease,
            @Min(1) int maxAttempts,
            @NotEmpty List<Duration> backoff) {

        public WorkerSettings toSettings() {
            return new WorkerSettings(batchSize, lease, new RetryPolicy(maxAttempts, backoff));
        }
    }
}
