package com.estudos.webhook.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuração do relay do outbox ({@code webhook.relay.*}).
 *
 * @param enabled   liga ou desliga o job agendado
 * @param interval  intervalo entre ciclos
 * @param batchSize notificações publicadas por lote
 */
@Validated
@ConfigurationProperties(prefix = "webhook.relay")
public record RelayProperties(boolean enabled, @NotNull Duration interval, @Min(1) int batchSize) {
}
