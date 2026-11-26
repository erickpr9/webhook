-- Esteira sobre o Kafka: o transporte entre as etapas passa a ser o broker, e não mais o polling
-- destas tabelas. O lease (locked_until) e os índices de busca dos workers deixam de existir.

-- ── webhook_events: vira o registro de processamento do consumer ─────────────────────────────
-- O evento é gravado e processado na mesma transação, com a linha bloqueada: não há mais o
-- estado intermediário PROCESSING. Eventos antigos que ficaram presos nele voltam para a fila.
UPDATE webhook_events SET status = 'RETRY_SCHEDULED' WHERE status = 'PROCESSING';

ALTER TABLE webhook_events DROP CONSTRAINT ck_webhook_events_status;
ALTER TABLE webhook_events ADD CONSTRAINT ck_webhook_events_status CHECK (
    status IN ('RECEIVED', 'PROCESSED', 'RETRY_SCHEDULED', 'DEAD', 'IGNORED')
);

DROP INDEX ix_webhook_events_due;
DROP INDEX ix_webhook_events_lease;
ALTER TABLE webhook_events DROP COLUMN locked_until;

-- ── notification_outbox: relay publica, consumer entrega ─────────────────────────────────────
-- PENDING (gravada junto com a mudança no pedido) → PUBLISHED (relay publicou no Kafka)
-- → SENT | RETRY_SCHEDULED | DEAD (consumer tentou entregar à loja).
UPDATE notification_outbox SET status = 'PENDING' WHERE status = 'PROCESSING';

ALTER TABLE notification_outbox DROP CONSTRAINT ck_notification_outbox_status;
ALTER TABLE notification_outbox ADD CONSTRAINT ck_notification_outbox_status CHECK (
    status IN ('PENDING', 'PUBLISHED', 'RETRY_SCHEDULED', 'SENT', 'DEAD')
);

ALTER TABLE notification_outbox ADD COLUMN published_at TIMESTAMPTZ;

DROP INDEX ix_notification_outbox_due;
DROP INDEX ix_notification_outbox_lease;
ALTER TABLE notification_outbox DROP COLUMN locked_until;

-- Busca do relay: somente notificações ainda não publicadas, na ordem de criação.
CREATE INDEX ix_notification_outbox_pending
    ON notification_outbox (created_at)
    WHERE status = 'PENDING';

-- Retentativas antigas (do worker por polling) não têm mensagem no Kafka: o relay as publica de novo.
UPDATE notification_outbox SET status = 'PENDING' WHERE status = 'RETRY_SCHEDULED';
