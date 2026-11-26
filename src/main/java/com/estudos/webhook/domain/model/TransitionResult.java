package com.estudos.webhook.domain.model;

/**
 * Resultado da tentativa de aplicar um evento a um pagamento.
 */
public enum TransitionResult {
    /** O estado mudou. */
    APPLIED,
    /** O pagamento já estava no estado de destino (reentrega do mesmo efeito). */
    ALREADY_APPLIED,
    /** O evento é mais antigo que o último evento aplicado (chegou fora de ordem). */
    STALE_EVENT,
    /** A transição não é permitida a partir do estado atual. */
    NOT_ALLOWED;

    public boolean applied() {
        return this == APPLIED;
    }
}
