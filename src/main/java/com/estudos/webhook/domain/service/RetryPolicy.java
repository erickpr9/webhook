package com.estudos.webhook.domain.service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Política de retentativa com backoff configurável.
 *
 * <p>Exemplo com {@code maxAttempts = 6} e backoff {@code [10s, 30s, 2m, 10m, 1h]}: as cinco
 * primeiras falhas agendam nova tentativa com esses intervalos; a sexta esgota as tentativas.
 * Se houver mais tentativas que intervalos, o último intervalo é repetido.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final List<Duration> backoff;

    public RetryPolicy(int maxAttempts, List<Duration> backoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(backoff, "backoff must not be null");
        if (backoff.isEmpty()) {
            throw new IllegalArgumentException("backoff must have at least one interval");
        }
        if (backoff.stream().anyMatch(interval -> interval == null || interval.isNegative())) {
            throw new IllegalArgumentException("backoff intervals must not be null or negative");
        }
        this.maxAttempts = maxAttempts;
        this.backoff = List.copyOf(backoff);
    }

    /** Indica se, após {@code attempts} tentativas, não deve haver outra. */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /** Intervalo até a próxima tentativa, após a falha da tentativa número {@code attempts}. */
    public Duration delayAfter(int attempts) {
        int index = Math.min(Math.max(attempts, 1) - 1, backoff.size() - 1);
        return backoff.get(index);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
