package com.estudos.webhook.domain.model;

/**
 * Ciclo de vida de um evento no registro de processamento.
 *
 * <pre>
 * RECEIVED ──▶ PROCESSED | IGNORED
 *    │
 *    ├──▶ RETRY_SCHEDULED ──▶ PROCESSED | IGNORED | RETRY_SCHEDULED ...
 *    └──▶ DEAD (tentativas esgotadas ou erro permanente) ──reprocessamento──▶ RETRY_SCHEDULED
 * </pre>
 *
 * <p>O evento é gravado como {@code RECEIVED} e processado na mesma transação, com a linha
 * bloqueada. Por isso {@code RECEIVED} só fica visível se o processamento falhar antes de registrar
 * a falha (ex.: queda da aplicação), e nesse caso a mensagem é entregue de novo pelo broker.
 */
public enum WebhookEventStatus {
    RECEIVED,
    PROCESSED,
    RETRY_SCHEDULED,
    DEAD,
    IGNORED;

    /** Estados em que uma nova entrega da mesma mensagem não deve ter efeito. */
    public boolean isFinal() {
        return this == PROCESSED || this == IGNORED || this == DEAD;
    }
}
