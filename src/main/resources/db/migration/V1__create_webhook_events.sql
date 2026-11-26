-- Inbox de webhooks recebidos. Cada linha é um evento do provedor, gravado antes de qualquer
-- processamento. A constraint única garante a idempotência mesmo com requisições concorrentes.
CREATE TABLE webhook_events (
    id                UUID         PRIMARY KEY,
    provider          VARCHAR(50)  NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    payload           JSONB        NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    attempts          INT          NOT NULL DEFAULT 0,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    -- Fim do "lease" de processamento: se o worker morrer, outro retoma o evento após esse instante.
    locked_until      TIMESTAMPTZ,
    processed_at      TIMESTAMPTZ,
    processing_note   VARCHAR(255),
    last_error        TEXT,

    CONSTRAINT uk_webhook_events_provider_event UNIQUE (provider, provider_event_id),
    CONSTRAINT ck_webhook_events_status CHECK (
        status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'RETRY_SCHEDULED', 'DEAD', 'IGNORED')
    ),
    CONSTRAINT ck_webhook_events_attempts CHECK (attempts >= 0)
);

-- Busca do worker: somente eventos aguardando processamento.
CREATE INDEX ix_webhook_events_due
    ON webhook_events (next_attempt_at)
    WHERE status IN ('RECEIVED', 'RETRY_SCHEDULED');

-- Recuperação de eventos cujo lease expirou.
CREATE INDEX ix_webhook_events_lease
    ON webhook_events (locked_until)
    WHERE status = 'PROCESSING';

-- Consultas administrativas por status.
CREATE INDEX ix_webhook_events_status_received
    ON webhook_events (status, received_at DESC);
