-- Outbox de notificações: gravado na mesma transação da mudança de domínio e enviado depois,
-- por um job próprio. Se o envio falhar, o pagamento continua confirmado.
CREATE TABLE notification_outbox (
    id                UUID        PRIMARY KEY,
    notification_type VARCHAR(50) NOT NULL,
    aggregate_id      VARCHAR(64) NOT NULL,
    payload           JSONB       NOT NULL,
    status            VARCHAR(20) NOT NULL,
    attempts          INT         NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    locked_until      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL,
    sent_at           TIMESTAMPTZ,
    last_error        TEXT,

    CONSTRAINT ck_notification_outbox_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'SENT', 'DEAD')
    ),
    CONSTRAINT ck_notification_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_notification_outbox_due
    ON notification_outbox (next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

CREATE INDEX ix_notification_outbox_lease
    ON notification_outbox (locked_until)
    WHERE status = 'PROCESSING';
