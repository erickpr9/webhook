# Payment Webhook Study

Estudo de uma esteira completa de webhook de confirmação de pagamento, da origem (provedor) até o consumo (domínio e notificação ao cliente), com Java 21 e Spring Boot 3.5.

```
Provedor ──POST assinado──▶ API ──▶ inbox (Postgres) ──▶ worker ──▶ pagamento / pedido
                             │                              │
                       202 Accepted                   outbox ──POST assinado──▶ Loja (cliente-webhook)
```

Este sistema faz os dois papéis do webhook: **recebe** o aviso do provedor e **envia** o próprio aviso à loja (webhook de saída). A loja é o projeto [`cliente-webhook`](../cliente-webhook), na pasta ao lado.

## O que a esteira garante

| Garantia | Como |
|---|---|
| Só aceita webhooks autênticos | HMAC-SHA256 de `{timestamp}.{body}`, comparação em tempo constante |
| Proteção contra replay | Janela de 5 minutos sobre o `X-Timestamp` (também assinado) |
| Resposta rápida ao provedor | A API só grava na inbox e responde `202`; o processamento é assíncrono |
| Nenhum evento processado duas vezes | `UNIQUE (provider, provider_event_id)` + `INSERT ... ON CONFLICT DO NOTHING` |
| Vários workers em paralelo | Reserva com `FOR UPDATE SKIP LOCKED` em um único `UPDATE ... RETURNING` |
| Recuperação se um worker morrer | Lease (`locked_until`): o evento é retomado por outro worker |
| Sem efeito duplicado após lease perdido | "Fencing token" (número da tentativa) em toda conclusão |
| Atomicidade | Domínio + outbox + conclusão do evento na mesma transação |
| Falhas transitórias | Retentativa com backoff (10s, 30s, 2m, 10m, 1h) e DEAD após 6 tentativas |
| Falhas permanentes | Payload malformado e regra de negócio violada vão direto para DEAD |
| Eventos fora de ordem | Descarte de eventos mais antigos que o último aplicado |
| Concorrência no domínio | Lock otimista (`version`) em pedidos e pagamentos |
| Notificação desacoplada | Outbox: se o envio falhar, o pagamento continua confirmado |
| Aviso à loja autêntico | Webhook de saída assinado com HMAC-SHA256 (mesmo contrato da entrada) |
| Loja fora do ar não perde aviso | `5xx`, `429`, `408` e timeout geram retentativa com backoff; `4xx` é recusa definitiva (DEAD) |
| Loja descarta reentregas | O `X-Event-Id` do aviso é o id da notificação, igual em todas as tentativas |
| Loja lenta não trava o worker | Timeouts de conexão (2s) e de resposta (5s) |

## Arquitetura

Hexagonal, com a regra de dependência verificada por testes ArchUnit:

```
api ──▶ application ──▶ domain ◀── infrastructure
```

| Camada | Conteúdo | Depende de |
|---|---|---|
| `domain` | `Payment`, `Order`, `Money`, máquina de estados, `RetryPolicy` | só do JDK |
| `application` | Casos de uso, handlers (Strategy), portas | `domain`, SLF4J |
| `infrastructure` | JDBC (`NamedParameterJdbcTemplate`), jobs, Micrometer, wiring | `application`, `domain`, Spring |
| `api` | Controllers, verificação HMAC, filtro de tamanho, erros | `application` |
| `dev` | Simulador do provedor (somente profile `local`) | `api`, `application` |

A camada de aplicação não usa anotações do Spring: os casos de uso são montados em `infrastructure/config/UseCaseConfig`.

## Como rodar

Pré-requisitos: Java 21 e Docker.

1. Suba o Postgres. Ele usa a porta **5433** para não conflitar com um Postgres local na 5432.

```powershell
docker compose up -d
```

2. Rode a aplicação no profile `local`. Ele aplica os dados de exemplo e liga os endpoints de admin, de simulação e o Swagger.

```powershell
$env:SPRING_PROFILES_ACTIVE="local"; .\gradlew.bat bootRun
```

3. No Postman, importe os dois arquivos da pasta [`postman/`](postman) (ou use a collection já criada no seu workspace) e selecione o environment **Webhook Study - Local**.

4. Envie **1. Webhooks (provedor) › Pagamento confirmado** e confira em **2. Pedidos › Consultar pedido**: o pedido `ord_1042` fica `PAID`.

### Windows: "Unable to establish loopback connection"

No Windows, o Java cria um socket local (AF_UNIX) na pasta temporária do usuário para o NIO, usado pelo Tomcat e pelo `HttpClient`. Quando o perfil tem espaço no nome (ex.: `C:\Users\Nome Sobrenome`, acessado como `C:\Users\NOMESO~1`), a conexão falha e a aplicação não sobe.

Pelo Gradle (`bootRun` e testes) isso já está resolvido: o `build.gradle.kts` aponta `jdk.net.unixdomain.tmpdir` para `build/tmp/uds`.

Rodando pela IDE (ex.: o botão Run do IntelliJ na classe `WebhookApplication`), adicione nas VM options:

```
-Djdk.net.unixdomain.tmpdir=C:\Projetos\Estudos\webhook\build\tmp
```

Para resolver para todos os projetos Java da máquina, defina a variável de ambiente do usuário `JAVA_TOOL_OPTIONS` com esse mesmo argumento, apontando para uma pasta fora do perfil (ex.: `C:\Temp\uds`, que precisa existir).

### Windows: acentos aparecendo como `├º` no log

A aplicação escreve o log em UTF-8, mas o terminal do Windows usa por padrão a página de código 850. Assim, "expedição" aparece como `expedi├º├úo`. O log está certo: é o terminal que lê os bytes com a tabela errada.

Para o terminal atual, rode antes do `bootRun`:

```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

Para valer em todo terminal novo, adicione essa mesma linha ao seu perfil do PowerShell. O comando abaixo abre o arquivo do perfil, criando-o se não existir:

```powershell
if (-not (Test-Path $PROFILE)) { New-Item -ItemType File -Force $PROFILE | Out-Null }; notepad $PROFILE
```

### Configuração

Para customizar, copie `.env.example` para `.env`. Fora do profile `local`, `DB_PASSWORD` e `WEBHOOK_PAYMENT_SECRET` (mínimo de 32 caracteres) são obrigatórios: a aplicação não sobe sem eles.

A entrega à loja usa `WEBHOOK_NOTIFICATION_URL` e `WEBHOOK_NOTIFICATION_SECRET`. No profile `local` elas já apontam para a loja em `http://localhost:8081`. Sem URL, as notificações só vão para o log. Se a loja não estiver rodando, as notificações ficam em retentativa e são entregues quando ela subir.

## Roteiro de testes no Postman

A collection fica na pasta [`postman/`](postman). Na pasta **1. Webhooks (provedor)**, um pre-request script gera o `eventId`, o `createdAt` e a assinatura HMAC a cada envio, então você só clica em **Send**.

Os webhooks usam as variáveis da collection `orderId`, `paymentId` e `amount`. O request **4. Simulação › Criar pedido** cria um pedido novo e atualiza essas variáveis. Por isso o roteiro sempre começa por ele: cada pedido só pode ser confirmado uma vez.

### Onde acompanhar

O processamento é assíncrono. O webhook responde na hora, mas o worker processa o evento em até 2 segundos e a notificação sai em até 5. Espere alguns segundos antes de conferir.

| Onde | O que mostra |
|---|---|
| **2. Pedidos › Consultar pedido** | Estado do pedido e do pagamento |
| **3. Admin › Listar eventos recentes** | Status, tentativas, nota e último erro de cada evento |
| Console da aplicação (terminal do `bootRun`) | Processamento, retentativas e `Notification ... sent` |
| Console do Postman (*View › Show Postman Console*) | Variáveis atualizadas pelos scripts (`orderId`, `webhookEventId`) |
| **5. Observabilidade › Métricas** | Contadores `webhook_*` |

### 0. Preparação

1. Suba o Postgres e a aplicação no profile `local` (veja [Como rodar](#como-rodar)).
2. No Postman, selecione o environment **Webhook Study - Local**.
3. Envie **5. Observabilidade › Health**: deve responder `UP`.

### 1. Caminho feliz: pagamento confirmado

| # | Request | Esperado |
|---|---|---|
| 1 | **4. Simulação › Criar pedido** | `201`; o console do Postman mostra o novo `orderId` |
| 2 | **2. Pedidos › Consultar pedido** | pedido `PENDING_PAYMENT`, pagamento `PENDING` |
| 3 | **1. Webhooks › Pagamento confirmado** | `202` `received` |
| 4 | **2. Pedidos › Consultar pedido** (aguarde ~3s) | pedido `PAID`, pagamento `CONFIRMED` |
| 5 | **3. Admin › Listar eventos recentes** | evento `PROCESSED`, nota `Payment CONFIRMED, order PAID` |

No console da aplicação aparece `Notification PAYMENT_CONFIRMED sent`.

### 2. Idempotência: o provedor reenvia o mesmo evento

Continue com o mesmo pedido.

| # | Request | Esperado |
|---|---|---|
| 1 | **1. Webhooks › Reentrega (mesmo evento)** | `202` `received` |
| 2 | **1. Webhooks › Reentrega (mesmo evento)** de novo | `200` `duplicate` |
| 3 | **3. Admin › Listar eventos recentes** | só um evento `evt_redelivery_demo` |

O id `evt_redelivery_demo` é fixo. Depois do primeiro envio, ele sempre responde `duplicate`, mesmo em outros pedidos. Para ver o `202` de novo, troque o valor do header `X-Event-Id` no request.

### 3. Evento fora de ordem

Continue com o mesmo pedido, já confirmado.

| # | Request | Esperado |
|---|---|---|
| 1 | **1. Webhooks › Evento fora de ordem (recusa antiga)** | `202` |
| 2 | **3. Admin › Listar eventos recentes** | evento `PROCESSED`, nota `Transition not applicable: STALE_EVENT` |
| 3 | **2. Pedidos › Consultar pedido** | continua `PAID` / `CONFIRMED` |

A recusa tem `created_at` de 2020, anterior à confirmação, então é descartada.

### 4. Estorno

Continue com o mesmo pedido.

| # | Request | Esperado |
|---|---|---|
| 1 | **1. Webhooks › Pagamento estornado** | `202` |
| 2 | **2. Pedidos › Consultar pedido** | pedido `REFUNDED`, pagamento `REFUNDED` |

### 5. Pagamento recusado

| # | Request | Esperado |
|---|---|---|
| 1 | **4. Simulação › Criar pedido** | `201` (pedido novo) |
| 2 | **1. Webhooks › Pagamento recusado** | `202` |
| 3 | **2. Pedidos › Consultar pedido** | pedido continua `PENDING_PAYMENT`, pagamento `REFUSED` |

### 6. Falhas e dead-letter

| # | Request | Esperado |
|---|---|---|
| 1 | **1. Webhooks › Valor divergente (DEAD imediato)** | `202` |
| 2 | **3. Admin › Listar eventos DEAD** | evento `DEAD` com 1 tentativa e erro `PaymentDataMismatchException` |
| 3 | **1. Webhooks › Pagamento inexistente (retentativa)** | `202` |
| 4 | **3. Admin › Listar eventos recentes** (repita algumas vezes) | evento `RETRY_SCHEDULED`, com as tentativas subindo e erro `PaymentNotFoundException` |
| 5 | **1. Webhooks › Tipo de evento desconhecido** | `202`; no Admin, evento `IGNORED` |
| 6 | **1. Webhooks › Assinatura inválida (401)** | `401`; nada novo no Admin |

O erro de valor é permanente, por isso vai direto para `DEAD`. O de pagamento inexistente é transitório, porque o pagamento pode ser criado depois. Ele é tentado de novo após 10s, 30s, 2m, 10m e 1h, e só vira `DEAD` depois da 6ª tentativa, mais de uma hora depois.

Para ver o `DEAD` por esgotamento em poucos segundos, suba a aplicação com um backoff curto:

```powershell
$env:SPRING_PROFILES_ACTIVE="local"; .\gradlew.bat bootRun --args="--webhook.worker.events.backoff=2s --webhook.worker.events.max-attempts=3"
```

### 7. Reprocessamento manual

| # | Request | Esperado |
|---|---|---|
| 1 | **3. Admin › Listar eventos DEAD** | guarda o id do primeiro evento em `webhookEventId` |
| 2 | **3. Admin › Reprocessar evento** | `202`; evento `RETRY_SCHEDULED` com 0 tentativas |
| 3 | **3. Admin › Consultar evento** (aguarde ~3s) | volta para `DEAD` |

O evento volta para `DEAD` porque a causa não foi corrigida, e esse é o comportamento esperado. Em produção, o reprocessamento vem depois da correção (ex.: o pagamento que faltava foi criado). Reprocessar um evento que não está `DEAD` responde `422`.

### 8. Simulação pela própria aplicação

| # | Request | Esperado |
|---|---|---|
| 1 | **4. Simulação › Criar pedido** | `201` |
| 2 | **4. Simulação › Simular evento pela aplicação** | `200`, com `httpStatus: 202` |
| 3 | **2. Pedidos › Consultar pedido** | pedido `PAID` |

Aqui é a aplicação que monta, assina e envia o webhook para o próprio endpoint, como o provedor faria.

### 9. Métricas

Envie **5. Observabilidade › Métricas** e procure por `webhook_received_total` (aceitos e duplicados), `webhook_processed_total`, `webhook_failed_total`, `webhook_dead_total` e `webhook_notification_sent_total`. Os números refletem tudo o que foi feito no roteiro.

### 10. O aviso chegando na loja

Com a loja ([`cliente-webhook`](../cliente-webhook)) rodando, cada pagamento confirmado gera um webhook de saída. Acompanhe pelos dois lados:

| # | Collection | Request | Esperado |
|---|---|---|---|
| 1 | Plataforma | **3. Admin › Listar notificações (entregas à loja)** | notificação `SENT` |
| 2 | Loja | **1. Loja › Avisos recebidos** | aviso `APPLIED` |
| 3 | Loja | **1. Loja › Consultar pedido** | `READY_TO_SHIP` |

Os cenários de falha da loja (fora do ar, lenta e recusando) estão no [README do cliente-webhook](../cliente-webhook/README.md#roteiro-no-postman).

Pedidos de exemplo já carregados no banco: `ord_1042`/`pay_001` (PIX), `ord_1043`/`pay_002` (cartão de crédito), `ord_1044`/`pay_003` (boleto). Também dá para usar o Swagger em http://localhost:8080/swagger-ui.html.

## Endpoints

| Método e rota | Descrição | Disponível |
|---|---|---|
| `POST /v1/webhooks/payments` | Recebe o webhook (`X-Event-Id`, `X-Timestamp`, `X-Signature`) | sempre |
| `GET /v1/orders/{orderId}` | Pedido e estado dos pagamentos | sempre |
| `GET /admin/webhook-events?status=DEAD&limit=50` | Eventos da inbox | `webhook.admin.enabled` |
| `GET /admin/webhook-events/{id}` | Detalhe de um evento | `webhook.admin.enabled` |
| `POST /admin/webhook-events/{id}/reprocess` | Recoloca um evento DEAD na fila | `webhook.admin.enabled` |
| `GET /admin/notifications?limit=20` | Entregas à loja: status, tentativas e último erro | `webhook.admin.enabled` |
| `POST /dev/orders` | Cria pedido com pagamento pendente | `webhook.dev.simulation-enabled` |
| `POST /dev/simulations/payment-events` | Envia um webhook assinado pelo caminho HTTP real | `webhook.dev.simulation-enabled` |
| `GET /actuator/health`, `/actuator/prometheus` | Saúde e métricas | sempre |

Os endpoints de admin e dev não têm autenticação e só ficam ligados no profile `local`.

### Contrato do webhook

```http
POST /v1/webhooks/payments
Content-Type: application/json
X-Event-Id: evt_01HX...
X-Timestamp: 1795701791
X-Signature: sha256=<hex de HMAC-SHA256(secret, "{X-Timestamp}.{body}")>

{"id":"evt_01HX...","type":"payment.confirmed","created_at":"2026-11-26T14:03:11Z",
 "data":{"payment_id":"pay_001","order_id":"ord_1042","amount":15990,"currency":"BRL"}}
```

| Resposta | Quando |
|---|---|
| `202` | Evento novo, aceito |
| `200` | Evento já recebido (`duplicate`) |
| `400` | Header ausente, timestamp não numérico, JSON inválido, `X-Event-Id` diferente do `id` |
| `401` | Assinatura inválida ou fora da janela de tempo |
| `413` | Corpo acima de 64 KB |
| `415` | `Content-Type` diferente de JSON |

### Webhook de saída (para a loja)

```http
POST {WEBHOOK_NOTIFICATION_URL}
Content-Type: application/json
X-Event-Id: <id da notificação no outbox, igual em todas as tentativas>
X-Timestamp: 1795701791
X-Signature: sha256=<hex de HMAC-SHA256(WEBHOOK_NOTIFICATION_SECRET, "{X-Timestamp}.{body}")>

{"id":"<id da notificação>","type":"payment.confirmed","occurred_at":"2026-11-26T14:03:11Z",
 "data":{"order_id":"ord_1042","payment_id":"pay_001","amount":15990,"currency":"BRL","payment_status":"CONFIRMED"}}
```

| Resposta da loja | O que a plataforma faz |
|---|---|
| `2xx` | Marca como `SENT` |
| `5xx`, `408`, `429`, timeout, conexão recusada | Nova tentativa com backoff (30s, 2m, 10m, 1h); `DEAD` após 5 tentativas |
| Demais `4xx` | Recusa definitiva: `DEAD` na hora |

A entrega é "pelo menos uma vez": se a plataforma não receber a resposta (timeout), ela reenvia o mesmo aviso. Cabe à loja descartar a reentrega pelo `X-Event-Id`.

## Métricas

`webhook.received` (tipo, resultado), `webhook.processed` (tipo, status), `webhook.failed` (tipo, retry/dead), `webhook.dead`, `webhook.processing.time`, `webhook.notification.sent` e `webhook.notification.failed`. Tipos de evento desconhecidos viram `unknown`, para não explodir a cardinalidade.

## Testes

```powershell
.\gradlew.bat check
```

Roda 242 testes e exige cobertura mínima de 80% (JaCoCo). Os testes de integração usam Testcontainers, então o Docker precisa estar ativo.

| Tipo | Exemplos |
|---|---|
| Domínio (JUnit puro) | Matriz de transições, eventos fora de ordem, `Money`, `RetryPolicy` |
| Aplicação (Mockito) | Retentativa, DEAD, lease perdido, classificação de falhas |
| Webhook de saída (`MockRestServiceServer`) | Assinatura sobre o corpo exato, classificação de `2xx`/`4xx`/`5xx`/timeout |
| API (`@WebMvcTest`) | 202, 200, 400, 401, 413, 415, mensagens de erro |
| Repositórios (Postgres real) | `ON CONFLICT`, `SKIP LOCKED` com workers concorrentes, lease, fencing |
| Ponta a ponta | Fluxo completo, reentregas simultâneas, retentativa até o pagamento existir, reprocessamento de DEAD |
| Arquitetura (ArchUnit) | Regra de dependência entre as camadas |

Testcontainers é usado em vez de H2 porque `ON CONFLICT`, `SKIP LOCKED` e `JSONB` não têm equivalência confiável no H2.

## Próximos passos possíveis

- Trocar a fila em Postgres por um broker (Kafka, RabbitMQ, Pub/Sub) implementando outra saída para o processamento.
- Adicionar jitter ao backoff, para evitar retentativas sincronizadas.
- Autenticar os endpoints de admin para uso fora do ambiente local.
- Rotação de segredo HMAC (aceitar dois segredos durante a troca).
- Particionar ou arquivar a inbox para volumes altos.

O plano detalhado está em [docs/PLANO-IMPLEMENTACAO.md](docs/PLANO-IMPLEMENTACAO.md).
