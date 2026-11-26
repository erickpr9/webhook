package com.estudos.webhook.api.security;

/**
 * Webhook não autenticado: assinatura ausente, inválida ou fora da janela de tempo.
 * Resulta em {@code 401}.
 */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
