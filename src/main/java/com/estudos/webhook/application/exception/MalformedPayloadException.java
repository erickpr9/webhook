package com.estudos.webhook.application.exception;

/**
 * O payload do evento não tem o formato esperado. É permanente: reprocessar o mesmo
 * conteúdo sempre falharia.
 */
public class MalformedPayloadException extends RuntimeException {

    public MalformedPayloadException(String message) {
        super(message);
    }

    public MalformedPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
