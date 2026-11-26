package com.estudos.webhook.application.exception;

/**
 * Falha ao publicar uma mensagem na esteira (broker fora do ar, timeout de confirmação).
 *
 * <p>Na recepção de webhooks vira {@code 503}: o provedor reenvia depois, e nada se perde.
 */
public class EventPublishingException extends RuntimeException {

    public EventPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}
