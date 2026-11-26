package com.estudos.webhook.infrastructure.messaging;

/**
 * Sinaliza ao Spring Kafka que a mensagem deve seguir para o próximo tópico de retry.
 *
 * <p>Lançada pelos listeners quando o caso de uso responde {@code RETRY}. A falha original já foi
 * registrada no banco pelo caso de uso; esta exceção só move a mensagem na esteira.
 */
public class RetryLaterException extends RuntimeException {

    public RetryLaterException(String message) {
        super(message, null, false, false);
    }
}
