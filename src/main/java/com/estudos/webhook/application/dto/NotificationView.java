package com.estudos.webhook.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Visão de leitura de uma notificação do outbox: mostra se a entrega ao cliente já aconteceu,
 * quantas tentativas foram feitas e o último erro.
 */
public record NotificationView(
        UUID id,
        String type,
        String orderId,
        String paymentId,
        String status,
        int attempts,
        Instant createdAt,
        Instant nextAttemptAt,
        Instant sentAt,
        String lastError) {
}
