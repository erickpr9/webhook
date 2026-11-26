package com.estudos.webhook.domain.exception;

import com.estudos.webhook.domain.model.WebhookEventStatus;

import java.util.UUID;

/**
 * Somente eventos em {@link WebhookEventStatus#DEAD} podem ser reprocessados manualmente.
 */
public class WebhookEventNotReprocessableException extends BusinessException {

    public WebhookEventNotReprocessableException(UUID id, WebhookEventStatus status) {
        super("Webhook event '" + id + "' is " + status + "; only DEAD events can be reprocessed");
    }
}
