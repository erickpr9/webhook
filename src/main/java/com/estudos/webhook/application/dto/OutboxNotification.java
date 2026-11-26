package com.estudos.webhook.application.dto;

import java.util.UUID;

/**
 * Notificação reservada do outbox para envio.
 *
 * @param id       identificador no outbox
 * @param attempts tentativa corrente (já incrementada na reserva)
 * @param message  conteúdo a enviar
 */
public record OutboxNotification(UUID id, int attempts, NotificationMessage message) {
}
