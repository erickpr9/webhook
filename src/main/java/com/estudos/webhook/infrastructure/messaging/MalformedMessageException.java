package com.estudos.webhook.infrastructure.messaging;

/**
 * Mensagem que não segue o formato da esteira (sem headers obrigatórios, JSON inválido).
 *
 * <p>Tentar de novo não resolve: vai direto para a dead-letter.
 */
public class MalformedMessageException extends RuntimeException {

    public MalformedMessageException(String message) {
        super(message);
    }
}
