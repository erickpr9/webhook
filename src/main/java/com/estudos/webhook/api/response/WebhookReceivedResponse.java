package com.estudos.webhook.api.response;

/**
 * Resposta ao provedor.
 *
 * @param status  {@code received} (evento novo) ou {@code duplicate} (já recebido)
 * @param eventId identificador do evento no provedor
 */
public record WebhookReceivedResponse(String status, String eventId) {

    public static WebhookReceivedResponse received(String eventId) {
        return new WebhookReceivedResponse("received", eventId);
    }

    public static WebhookReceivedResponse duplicate(String eventId) {
        return new WebhookReceivedResponse("duplicate", eventId);
    }
}
