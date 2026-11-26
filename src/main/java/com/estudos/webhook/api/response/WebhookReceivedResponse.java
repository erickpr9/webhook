package com.estudos.webhook.api.response;

/**
 * Resposta ao provedor.
 *
 * @param status  sempre {@code received}: o evento está gravado na esteira. Se for repetido, o
 *                consumo descarta a cópia.
 * @param eventId identificador do evento no provedor
 */
public record WebhookReceivedResponse(String status, String eventId) {

    public static WebhookReceivedResponse received(String eventId) {
        return new WebhookReceivedResponse("received", eventId);
    }
}
