package com.estudos.webhook.infrastructure.messaging;

/**
 * Sinaliza ao Spring Kafka que a mensagem deve ir direto para a dead-letter, sem passar pelos
 * tópicos de retry.
 *
 * <p>Lançada pelos listeners quando o caso de uso responde {@code DEAD} (erro permanente ou
 * tentativas esgotadas). Está na lista de exceções não retentáveis do {@code @RetryableTopic}.
 */
public class DeadLetterException extends RuntimeException {

    public DeadLetterException(String message) {
        super(message, null, false, false);
    }
}
