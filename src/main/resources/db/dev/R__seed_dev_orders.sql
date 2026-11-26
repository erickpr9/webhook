-- Dados de exemplo para desenvolvimento local (carregado apenas no profile "local").
-- Migration repetível e idempotente: pode rodar várias vezes sem duplicar dados.
INSERT INTO orders (id, status, total_amount, currency, created_at, updated_at, version)
VALUES ('ord_1042', 'PENDING_PAYMENT', 15990, 'BRL', now(), now(), 0),
       ('ord_1043', 'PENDING_PAYMENT', 4990, 'BRL', now(), now(), 0),
       ('ord_1044', 'PENDING_PAYMENT', 129900, 'BRL', now(), now(), 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments (id, order_id, status, amount, currency, method, last_event_at, created_at, updated_at, version)
VALUES ('pay_001', 'ord_1042', 'PENDING', 15990, 'BRL', 'PIX', NULL, now(), now(), 0),
       ('pay_002', 'ord_1043', 'PENDING', 4990, 'BRL', 'CREDIT_CARD', NULL, now(), now(), 0),
       ('pay_003', 'ord_1044', 'PENDING', 129900, 'BRL', 'BOLETO', NULL, now(), now(), 0)
ON CONFLICT (id) DO NOTHING;
