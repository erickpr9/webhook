package com.estudos.webhook.application.dto;

/**
 * Resultado do tratamento de um evento de pagamento.
 *
 * @param applied {@code true} se o domínio foi alterado
 * @param note    observação gravada no evento
 */
public record HandlingOutcome(boolean applied, String note) {

    public static HandlingOutcome applied(String note) {
        return new HandlingOutcome(true, note);
    }

    public static HandlingOutcome ignored(String note) {
        return new HandlingOutcome(false, note);
    }
}
