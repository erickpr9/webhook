package com.estudos.webhook.application.dto;

import com.estudos.webhook.domain.service.RetryPolicy;

import java.time.Duration;
import java.util.Objects;

/**
 * Parâmetros de um worker de processamento em lote.
 *
 * @param batchSize   quantidade máxima de itens reservados por ciclo
 * @param lease       tempo de reserva de cada item; após isso, outro worker pode retomá-lo
 * @param retryPolicy política de retentativa
 */
public record WorkerSettings(int batchSize, Duration lease, RetryPolicy retryPolicy) {

    public WorkerSettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        Objects.requireNonNull(lease, "lease must not be null");
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
    }
}
