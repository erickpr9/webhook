# Implementation Plan — Payment Confirmation Webhook

Projeto de estudo pessoal. Código, nomes e banco em **inglês**; comentários e documentação dentro do código em **português**.

Pipeline: **origem (payment provider) → recebimento → validação → inbox idempotente → consumo assíncrono → atualização de domínio → notificação → observabilidade**.

## 0. Decisões fixadas

| Item | Escolha |
|---|---|
| Linguagem | Java 21 |
| Framework | Spring Boot 3.5.0 |
| Build | Gradle (Kotlin DSL), version catalog `gradle/libs.versions.toml`, wrapper 9.3.0 |
| Pacote base | `com.estudos.webhook` |
| Banco | PostgreSQL 16 via Docker (local). Sem outros bancos nem filas externas no MVP |
| Acesso a dados | `NamedParameterJdbcTemplate`, sem JPA/Hibernate |
| Migrations | Flyway 10.x em `src/main/resources/db/migration/` (`V<n>__<description>.sql`) |
| Arquitetura | Hexagonal: `api → application → domain ← infrastructure` |
| Nomes | Inglês em classes, métodos, tabelas e colunas |
| Documentação no código | Português (Javadoc, comentários) |
| Boilerplate | Lombok, MapStruct |
| API docs | springdoc-openapi |
| Logs | SLF4J + Logback em JSON (logstash-logback-encoder) |
| Métricas | Actuator + Micrometer Prometheus |
| Testes | JUnit 5, AssertJ, Mockito, Testcontainers (Postgres), JaCoCo |

---

## 1. Visão geral

```
 Provedor (simulador)                 Aplicação Spring Boot                          PostgreSQL (Docker)
 ─────────────────────   POST    ┌────────────────────────────────┐
 payment.confirmed  ───────────▶ │ PaymentWebhookController       │  (api)
   X-Signature (HMAC)            │  1. valida tamanho / headers   │
   X-Event-Id                    │  2. valida janela de tempo     │
   X-Timestamp                   │  3. valida assinatura          │
                                 │  4. RegisterWebhookEventUseCase│  (application)
                 202 Accepted ◀──│     INSERT ... ON CONFLICT     │──▶ webhook_event
                                 └────────────────────────────────┘
                                              │
                                 ┌────────────▼───────────────────┐
                                 │ ProcessPendingEventsJob        │  (infrastructure/messaging)
                                 │  @Scheduled + FOR UPDATE       │
                                 │   SKIP LOCKED                  │
                                 │  → ProcessWebhookEventUseCase  │  (application)
                                 │    → handler por tipo          │──▶ payment / orders
                                 │    → retry com backoff         │──▶ notification_outbox
                                 │    → DEAD após o limite        │
                                 └────────────────────────────────┘
```

**Princípio central:** o endpoint só valida, grava e responde `202`. A regra de negócio roda depois, de forma assíncrona, com retry. O provedor recebe resposta rápida e nenhum evento válido se perde.

**Por que fila em Postgres:** não exige infraestrutura extra, fica na mesma transação do domínio e funciona com várias instâncias graças ao `SKIP LOCKED`. A saída do processamento fica atrás de uma porta (`application/port`), então trocar por um broker depois é uma mudança localizada.

---

## 2. Origem: contrato do webhook

### 2.1 Requisição

| Item | Valor |
|---|---|
| Método / rota | `POST /v1/webhooks/payments` |
| Content-Type | `application/json` |
| Header `X-Event-Id` | ID único do evento no provedor (ex.: `evt_01HX...`) |
| Header `X-Timestamp` | Epoch em segundos de quando o evento foi emitido |
| Header `X-Signature` | `sha256=<hex>`: HMAC-SHA256 de `{timestamp}.{body}` com o segredo compartilhado |
| Tamanho máximo do body | 64 KB (acima disso, `413`) |

### 2.2 Payload (exemplo)

```json
{
  "id": "evt_01HXYZ...",
  "type": "payment.confirmed",
  "created_at": "2026-11-26T14:03:11Z",
  "data": {
    "payment_id": "pay_8f3a...",
    "order_id": "ord_1042",
    "amount": 15990,
    "currency": "BRL",
    "method": "PIX",
    "paid_at": "2026-11-26T14:03:09Z"
  }
}
```

### 2.3 Tipos de evento

| Tipo | Efeito no domínio |
|---|---|
| `payment.confirmed` | Pagamento vira `CONFIRMED`; pedido vira `PAID` |
| `payment.refused` | Pagamento vira `REFUSED`; pedido segue `PENDING_PAYMENT` |
| `payment.refunded` | Pagamento vira `REFUNDED`; pedido vira `REFUNDED` |

Tipo desconhecido é **gravado e marcado como `IGNORED`**, sem falhar.

---

## 3. Recebimento e validação (`api`)

Ordem das validações, da mais barata para a mais cara:

1. **Tamanho do body**: `413` acima de 64 KB.
2. **Headers obrigatórios** (`X-Event-Id`, `X-Timestamp`, `X-Signature`): `400`.
3. **Janela de tempo (anti-replay)**: `|now − X-Timestamp| ≤ 300 s`. Fora disso, `401`.
4. **Assinatura HMAC**: `HMAC-SHA256(secret, timestamp + "." + rawBody)` comparado com `MessageDigest.isEqual`. Divergente, `401`.
5. **Parse e schema**: JSON válido, `id` e `type` obrigatórios (Bean Validation). Inválido, `400`.
6. **Registro na inbox** via use case (seção 4).
7. **Resposta**: `202 Accepted` com `{"status":"received","eventId":"..."}`.

**Regras de resposta:**
- Evento novo → `202`.
- Evento já recebido (mesmo `X-Event-Id`) → `200 OK` com `{"status":"duplicate"}`. Reenvio é comportamento normal do provedor.
- Erro de validação → **não** grava na inbox.

**Detalhes:**
- O body precisa chegar **cru** para a assinatura. Um filtro com `ContentCachingRequestWrapper` garante isso. O controller lê como `String` e só depois faz o parse.
- O segredo vem de variável de ambiente `WEBHOOK_PAYMENT_SECRET`, nunca do repositório.
- O controller é fino: só valida e delega. A verificação de assinatura fica em um componente próprio, testado isoladamente.

---

## 4. Persistência: inbox idempotente

### 4.1 Tabela `webhook_event` (migration `V1__create_webhook_event.sql`)

| Coluna | Tipo | Observação |
|---|---|---|
| `id` | `UUID` PK | Gerado pela aplicação |
| `provider` | `VARCHAR(50)` | Ex.: `payment-gateway` |
| `provider_event_id` | `VARCHAR(100)` | Valor de `X-Event-Id` |
| `event_type` | `VARCHAR(100)` | Ex.: `payment.confirmed` |
| `payload` | `JSONB` | Body cru, para auditoria e reprocessamento |
| `status` | `VARCHAR(20)` | `RECEIVED`, `PROCESSING`, `PROCESSED`, `RETRY_SCHEDULED`, `DEAD`, `IGNORED` |
| `attempts` | `INT` | Começa em 0 |
| `next_attempt_at` | `TIMESTAMPTZ` | Quando pode ser processado de novo |
| `last_error` | `TEXT` | Truncado em 2 KB |
| `received_at` | `TIMESTAMPTZ` | |
| `processed_at` | `TIMESTAMPTZ` | Nulo até processar |

**Constraints e índices:**
- `UNIQUE (provider, provider_event_id)`: idempotência garantida pelo banco.
- `INDEX (status, next_attempt_at)`: acelera a busca do worker.

### 4.2 Gravação idempotente (sem SELECT antes do INSERT)

```sql
INSERT INTO webhook_event (id, provider, provider_event_id, event_type, payload, status, attempts, next_attempt_at, received_at)
VALUES (:id, :provider, :providerEventId, :eventType, CAST(:payload AS jsonb), 'RECEIVED', 0, now(), now())
ON CONFLICT (provider, provider_event_id) DO NOTHING;
```

- 1 linha afetada → evento novo, responde `202`.
- 0 linhas afetadas → duplicado, responde `200 duplicate`.

Dois POSTs simultâneos com o mesmo ID se resolvem no banco: um insere, o outro recebe 0 linhas.

---

## 5. Consumo: worker assíncrono

### 5.1 Seleção de eventos

Um job em `infrastructure/messaging`, com `@Scheduled(fixedDelayString = "${webhook.worker.interval-ms}")`, executa dentro de transação:

```sql
SELECT * FROM webhook_event
WHERE status IN ('RECEIVED', 'RETRY_SCHEDULED')
  AND next_attempt_at <= now()
ORDER BY received_at
LIMIT :batchSize
FOR UPDATE SKIP LOCKED;
```

- `FOR UPDATE SKIP LOCKED` permite várias instâncias sem processar o mesmo evento duas vezes.
- Os selecionados passam para `PROCESSING` na mesma transação.
- Se o scheduler precisar de lock entre instâncias, ShedLock (provider JDBC) resolve. Não é obrigatório, pois o `SKIP LOCKED` já cobre o consumo.

### 5.2 Processamento por tipo (Strategy, em `application`)

```java
/** Trata um tipo de evento de pagamento. Lança exceção em caso de falha. */
public interface PaymentEventHandler {
    boolean supports(String eventType);
    void handle(WebhookEvent event);
}
```

Um handler por tipo: `ConfirmPaymentHandler`, `RefusePaymentHandler`, `RefundPaymentHandler`. Tipo sem handler → `IGNORED`.

### 5.3 Transação e atomicidade

Cada evento é processado em **uma transação** (`@Transactional` no use case):

1. Carregar o pagamento pelo `payment_id`.
2. Aplicar a transição de estado (seção 6).
3. Atualizar o pedido.
4. Inserir em `notification_outbox`, se houver notificação.
5. Marcar o evento como `PROCESSED` e preencher `processed_at`.

Se qualquer passo falhar, tudo sofre rollback e o evento volta para retry. Nunca fica estado parcial.

### 5.4 Retry e dead-letter

| Tentativa | Próxima tentativa após |
|---|---|
| 1 | 10 s |
| 2 | 30 s |
| 3 | 2 min |
| 4 | 10 min |
| 5 | 1 h |
| 6+ | `DEAD` |

- Erro **transitório** (banco indisponível, timeout): `RETRY_SCHEDULED` com backoff.
- Erro **permanente** (payload inválido): `DEAD` direto.
- Backoff e `max-attempts` vêm de `application.yml` via `@ConfigurationProperties`.

### 5.5 Fora de ordem

- Pagamento ainda não existe → exceção de "aguardando criação" → retry. Após o limite, `DEAD`.
- Evento mais antigo que o estado atual (ex.: `refused` depois de `confirmed`) → marcar `PROCESSED` com nota "transição não aplicável". Compara-se `occurred_at` do evento com `last_event_at` do pagamento.

---

## 6. Domínio: pagamento e pedido

### 6.1 Máquina de estados do pagamento

```
PENDING ──confirmed──▶ CONFIRMED ──refunded──▶ REFUNDED
   │
   └────refused─────▶ REFUSED
```

Transição inválida não gera erro para o provedor. É registrada como ignorada, com log `WARN`.

As regras ficam em classes **sem framework** (`domain`), testadas com JUnit puro.

### 6.2 Tabelas do domínio

- **`orders`**: `id` (VARCHAR PK), `status` (`PENDING_PAYMENT`, `PAID`, `REFUNDED`), `total_amount` (BIGINT, centavos), `currency`, `updated_at`.
- **`payments`**: `id` (VARCHAR PK, ex.: `pay_...`), `order_id` FK, `status`, `amount` (BIGINT, centavos), `method`, `last_event_at`, `version` (BIGINT; lock otimista com `WHERE version = :version`).

Valores monetários em **centavos (`BIGINT`)**, nunca `double`.

---

## 7. Notificação (consumo final)

- Tabela `notification_outbox` (`id`, `type`, `payload`, `status`, `created_at`, `sent_at`).
- Um segundo job lê o outbox e "envia". No MVP, o envio é um `log.info`.
- Desacopla o envio externo da transação de domínio: se o e-mail falhar, o pagamento continua confirmado.

---

## 8. Banco: Postgres local com Docker

### 8.1 `docker-compose.yml` (raiz do projeto)

```yaml
services:
  postgres:
    image: postgres:16
    container_name: webhook-postgres
    environment:
      POSTGRES_DB: webhook
      POSTGRES_USER: webhook
      POSTGRES_PASSWORD: ${DB_PASSWORD:-webhook}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U webhook -d webhook"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  pgdata:
```

Credenciais só para desenvolvimento local, em `.env` (no `.gitignore`), com um `.env.example` versionado.

### 8.2 Comandos (PowerShell)

```powershell
cd C:\Projetos\Estudos\webhook
docker compose up -d
docker compose ps          # aguardar "healthy"
docker compose logs -f postgres
```

### 8.3 Configuração Spring (`application.yml`)

```yaml
spring:
  application:
    name: webhook-study
  datasource:
    url: jdbc:postgresql://localhost:5432/webhook
    username: webhook
    password: ${DB_PASSWORD:webhook}
  flyway:
    enabled: true
    locations: classpath:db/migration

webhook:
  payment:
    secret: ${WEBHOOK_PAYMENT_SECRET}      # definir no ambiente; sem default fora do local
    tolerance-seconds: 300
    max-body-bytes: 65536
  worker:
    interval-ms: 2000
    batch-size: 20
    max-attempts: 6
```

No Windows, as variáveis vão com `$env:` no PowerShell ou na configuração de execução da IDE.

### 8.4 Migrations

- `V1__create_webhook_event.sql`: inbox (seção 4).
- `V2__create_orders_payments.sql`: domínio (seção 6).
- `V3__create_notification_outbox.sql`: outbox (seção 7).
- `V4__seed_dev_orders.sql`: **somente dev**. Fica em `db/dev/` e entra na location só no profile `local`.

Nunca editar migration já aplicada.

---

## 9. Estrutura de pacotes

Base: `com.estudos.webhook`

```
src/main/java/com/estudos/webhook/
├── WebhookApplication.java
├── domain/                                  # sem dependências de framework
│   ├── model/
│   │   ├── Payment.java
│   │   ├── PaymentStatus.java
│   │   ├── Order.java
│   │   └── OrderStatus.java
│   ├── service/
│   │   └── PaymentStateMachine.java         # transições válidas
│   └── exception/
│       ├── PaymentNotFoundException.java    # extends ResourceNotFoundException
│       └── InvalidTransitionException.java  # extends BusinessException
├── application/                             # casos de uso
│   ├── usecase/
│   │   ├── RegisterWebhookEventUseCase.java
│   │   └── ProcessWebhookEventUseCase.java
│   ├── handler/
│   │   ├── PaymentEventHandler.java
│   │   ├── ConfirmPaymentHandler.java
│   │   ├── RefusePaymentHandler.java
│   │   └── RefundPaymentHandler.java
│   ├── dto/
│   │   ├── WebhookEventCommand.java
│   │   └── RegistrationResult.java
│   └── port/
│       ├── WebhookEventRepository.java      # porta de saída
│       ├── PaymentRepository.java
│       └── OrderRepository.java
├── infrastructure/
│   ├── persistence/
│   │   ├── JdbcWebhookEventRepository.java
│   │   ├── JdbcPaymentRepository.java
│   │   ├── JdbcOrderRepository.java
│   │   └── mapper/                          # RowMapper
│   ├── messaging/
│   │   ├── ProcessPendingEventsJob.java     # @Scheduled
│   │   └── SendNotificationJob.java
│   ├── security/
│   │   ├── WebhookSignatureVerifier.java
│   │   └── WebhookAuthenticationFilter.java
│   └── config/
│       ├── WebhookProperties.java           # @ConfigurationProperties
│       ├── SchedulingConfig.java
│       └── JdbcConfig.java
└── api/
    ├── controller/
    │   ├── PaymentWebhookController.java    # POST /v1/webhooks/payments
    │   └── DevSimulationController.java     # só profile local
    ├── request/
    │   └── PaymentWebhookRequest.java       # Bean Validation
    ├── response/
    │   ├── WebhookReceivedResponse.java
    │   └── ErrorResponse.java
    ├── filter/
    │   └── RequestCachingFilter.java
    └── handler/
        └── GlobalExceptionHandler.java      # @RestControllerAdvice
```

**Regra de dependência:** `api → application → domain ← infrastructure`. `domain` não importa Spring nem JDBC.

---

## 10. Simulador de envio

- `WebhookSigner` (utilitário), com a mesma lógica da verificação. Reutilizado nos testes.
- Endpoint de dev **só no profile `local`**: `POST /dev/simulate/payment?type=payment.confirmed&orderId=...`. Monta, assina e envia o evento.
- Alternativa: script PowerShell.

```powershell
$secret = $env:WEBHOOK_PAYMENT_SECRET
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$body = '{"id":"evt_test_001","type":"payment.confirmed","data":{"payment_id":"pay_001","order_id":"ord_1042","amount":15990}}'
$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
$sig = ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$ts.$body")) | ForEach-Object { $_.ToString("x2") }) -join ''
Invoke-RestMethod -Method Post -Uri http://localhost:8080/v1/webhooks/payments `
  -ContentType "application/json" -Body $body `
  -Headers @{ "X-Event-Id"="evt_test_001"; "X-Timestamp"="$ts"; "X-Signature"="sha256=$sig" }
```

---

## 11. Erros HTTP

- Todos os erros usam `ErrorResponse`.
- Exceções de domínio estendem `ResourceNotFoundException` (404) ou `BusinessException` (422). O handler captura as bases.
- Logs: `400` e `422` em WARN com método, URI e body. `500` em ERROR com stack trace. O `detail` de 500 é genérico.
- Assinatura inválida (`401`): WARN, sem body e sem a assinatura no log.

---

## 12. Testes

| Nível | Ferramenta | Cobertura |
|---|---|---|
| Domínio | JUnit 5 puro | `PaymentStateMachine` (todas as transições) |
| Use case | Mockito | Handlers, backoff, transição ignorada |
| Assinatura | JUnit puro | Válida, inválida, fora da janela de tempo, comparação em tempo constante |
| Controller | `@WebMvcTest` + `@MockitoBean` | 202, 200 duplicate, 400, 401, 413 |
| Repositórios JDBC | **Testcontainers (Postgres 16)** | `ON CONFLICT`, `SKIP LOCKED`, `JSONB`, migrations |
| Fluxo completo | `@SpringBootTest` + Testcontainers | POST → 202 → job → `PROCESSED` e pagamento atualizado |
| Concorrência | Threads + Testcontainers | Dois POSTs simultâneos com mesmo ID → um insere, outro `duplicate` |
| Retry | Handler que falha N vezes | Backoff respeitado, `DEAD` após o limite |
| Fora de ordem | Cenário | `refused` após `confirmed` é ignorado; evento sem pagamento espera e depois processa |

Testcontainers em vez de H2, porque `ON CONFLICT`, `SKIP LOCKED` e `JSONB` não têm equivalência confiável no H2.

Cobertura mínima de 80% via JaCoCo.

---

## 13. Observabilidade

- Logs JSON com `eventId`, `eventType`, `attempt`, `status` no MDC. Nunca logar o body completo nem a assinatura.
- Actuator: `/actuator/health` (inclui banco) e `/actuator/prometheus`. Sem `micrometer-registry-prometheus`, o endpoint responde 404.
- Métricas customizadas: `webhook.received`, `webhook.processed`, `webhook.failed`, `webhook.dead`, e tempo de processamento.
- Admin (só `local`): `GET /admin/webhook-events?status=DEAD` e `POST /admin/webhook-events/{id}/reprocess`.

---

## 14. Fases de execução

| Fase | Entrega | Critério de aceite |
|---|---|---|
| **0. Base** | Projeto Gradle, version catalog, `docker-compose`, Flyway, Actuator, `.env.example` | `docker compose up -d` e `.\gradlew.bat bootRun` sobem; `/actuator/health` = UP |
| **1. Recebimento** | Controller, validações de header/tempo/assinatura, `ON CONFLICT` | POST válido → 202; assinatura errada → 401; duplicado → 200 `duplicate`; testes verdes |
| **2. Domínio** | Tabelas `orders`/`payments`, máquina de estados, seed `dev` | Transições testadas; transição inválida não quebra |
| **3. Worker** | Job `@Scheduled`, `SKIP LOCKED`, handlers, retry, `DEAD` | Evento válido vira `PROCESSED` e altera o pagamento; falha agenda retry; limite leva a `DEAD` |
| **4. Notificação** | Outbox e job de envio | Ao confirmar, há registro no outbox e o envio é logado |
| **5. Simulador e ponta a ponta** | `/dev/simulate/payment`, script PowerShell, testes com Testcontainers | Fluxo completo validado nos testes e manualmente |
| **6. Observabilidade e admin** | Métricas, MDC, reprocessamento | Métricas em `/actuator/prometheus`; `DEAD` reprocessável |

Cada fase fecha com testes passando e um commit.

---

## 15. Decisões

**Fixadas:**
- Java 21, Spring Boot 3.5.0, Gradle com version catalog.
- Pacote `com.estudos.webhook`.
- Nomes em inglês; documentação no código em português.
- Postgres via Docker, sem outros bancos ou filas externas no MVP.
- Projeto de estudo pessoal, sem vínculo com outros projetos.

**Pendentes:** nenhuma.

---

## 17. Status: implementado

Todas as fases (0 a 6) foram implementadas e validadas: 224 testes, cobertura de linhas de 97,6% e fluxo manual pela collection do Postman em `postman/`. Veja o [README](../README.md) para rodar.

**Ajustes em relação ao plano, feitos durante a implementação:**

| Plano | Implementado | Motivo |
|---|---|---|
| Spring Boot 3.5.0 | 3.5.16 | Último patch da mesma linha, com correções de bugs e segurança |
| Porta 5432 no Docker | 5433 no host | Já existe um Postgres local ocupando a 5432 |
| Tabelas `webhook_event`, `orders`, `payments` | `webhook_events`, `orders`, `payments` | Nomes no plural de forma consistente |
| `SELECT ... FOR UPDATE SKIP LOCKED` + transação longa | `UPDATE ... RETURNING` com `SKIP LOCKED` e lease (`locked_until`) | A reserva é atômica, não segura locks durante o processamento e permite retomar eventos de um worker que morreu |
| Conclusão sem proteção | "Fencing token" (número da tentativa) | Um worker com lease expirado não sobrescreve outro |
| Lombok e MapStruct | Records do Java 21 | Os mapeamentos são triviais; menos dependências |
| `@Transactional` nos casos de uso | Porta `TransactionRunner` | A camada de aplicação fica sem nenhuma dependência do Spring |
| Filtro de autenticação | Verificação no controller com `byte[]` cru | A assinatura é feita sobre os bytes exatos, sem filtro de cache do corpo |
| Seed `V4__seed_dev_orders.sql` | `db/dev/R__seed_dev_orders.sql` (repetível) | Só entra no profile `local` e é idempotente |
| Endpoint `/dev/simulate/payment` | `POST /dev/orders` e `POST /dev/simulations/payment-events` | Cria o pedido e envia o evento pelo caminho HTTP real |
| — | Testes ArchUnit | Garantem a regra de dependência entre as camadas |
| — | Valor e pedido do evento conferidos com o pagamento | Divergência vai para DEAD em vez de confirmar um valor errado |

---

## 16. Checklist de segurança

- [ ] Segredo HMAC fora do repositório (env var, `.env` no `.gitignore`).
- [ ] Comparação de assinatura em tempo constante (`MessageDigest.isEqual`).
- [ ] Janela de tempo contra replay (300 s).
- [ ] Limite de tamanho do body (64 KB).
- [ ] Nenhum dado de cartão trafega ou é armazenado (só IDs e valores do provedor).
- [ ] Endpoints `/dev/**` e `/admin/**` desativados fora do profile `local`.
- [ ] Logs sem o body completo nem a assinatura.
