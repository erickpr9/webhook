package com.estudos.webhook.application.exception;

/**
 * O destinatário recusou a notificação de forma definitiva (ex.: {@code 400}, {@code 401},
 * {@code 404}). Reenviar o mesmo conteúdo não mudaria o resultado, então a notificação vai
 * direto para DEAD.
 */
public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }
}
