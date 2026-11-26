package com.estudos.webhook.domain.exception;

import java.util.UUID;

/**
 * Evento da inbox não encontrado.
 */
public class WebhookEventNotFoundException extends ResourceNotFoundException {

    public WebhookEventNotFoundException(UUID id) {
        super("Webhook event '" + id + "' not found");
    }
}
