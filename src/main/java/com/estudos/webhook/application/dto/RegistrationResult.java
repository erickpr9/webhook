package com.estudos.webhook.application.dto;

/**
 * Resultado do registro de um webhook na inbox.
 *
 * @param providerEventId identificador do evento no provedor
 * @param duplicate       {@code true} se o evento já tinha sido recebido antes
 */
public record RegistrationResult(String providerEventId, boolean duplicate) {
}
