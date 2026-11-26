package com.estudos.webhook.domain.model;

/**
 * Ciclo de vida de um evento na inbox.
 *
 * <pre>
 * RECEIVED ──▶ PROCESSING ──▶ PROCESSED | IGNORED
 *                  │
 *                  ├──▶ RETRY_SCHEDULED ──▶ PROCESSING ...
 *                  └──▶ DEAD (tentativas esgotadas ou erro permanente)
 * </pre>
 */
public enum WebhookEventStatus {
    RECEIVED,
    PROCESSING,
    PROCESSED,
    RETRY_SCHEDULED,
    DEAD,
    IGNORED
}
