-- Domínio mínimo para o fluxo: pedidos e seus pagamentos.
-- Valores monetários em centavos (BIGINT). A coluna version implementa lock otimista.
CREATE TABLE orders (
    id           VARCHAR(64) PRIMARY KEY,
    status       VARCHAR(20) NOT NULL,
    total_amount BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'REFUNDED')),
    CONSTRAINT ck_orders_total_amount CHECK (total_amount > 0)
);

CREATE TABLE payments (
    id            VARCHAR(64) PRIMARY KEY,
    order_id      VARCHAR(64) NOT NULL REFERENCES orders (id),
    status        VARCHAR(20) NOT NULL,
    amount        BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    method        VARCHAR(20) NOT NULL,
    -- Instante do último evento aplicado; usado para descartar eventos fora de ordem.
    last_event_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REFUSED', 'REFUNDED')),
    CONSTRAINT ck_payments_method CHECK (method IN ('PIX', 'CREDIT_CARD', 'DEBIT_CARD', 'BOLETO')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
);

CREATE INDEX ix_payments_order_id ON payments (order_id);
