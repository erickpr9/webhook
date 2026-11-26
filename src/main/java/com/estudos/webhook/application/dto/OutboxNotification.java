package com.estudos.webhook.application.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Notificação do outbox, pronta para ser publicada ou entregue.
 *
 * @param id      identificador no outbox; é o mesmo em todas as tentativas de entrega
 * @param message conteúdo a enviar
 */
public record OutboxNotification(UUID id, NotificationMessage message) {

    public OutboxNotification {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
