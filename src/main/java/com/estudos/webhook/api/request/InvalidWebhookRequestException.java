package com.estudos.webhook.api.request;

import java.util.List;

/**
 * Requisição de webhook mal formada (JSON inválido, campo obrigatório ausente, headers
 * inconsistentes). Resulta em {@code 400}.
 */
public class InvalidWebhookRequestException extends RuntimeException {

    private final List<String> details;

    public InvalidWebhookRequestException(String message) {
        this(message, List.of());
    }

    public InvalidWebhookRequestException(String message, List<String> details) {
        super(message);
        this.details = List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
