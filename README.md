# Payment Webhook Study

Estudo de uma esteira completa de webhook de confirmação de pagamento, da origem (provedor) até o consumo (domínio e notificação ao cliente), com Java 21, Spring Boot 3.5 e **Kafka**.

```
Provedor ─POST assinado─▶ API ─publica─▶ [payment-webhooks] ─▶ consumer ─▶ pagamento / pedido + outbox
                           │              key = order_id          │              (mesma transação)
                     202 Accepted         3 partições             └─ falhou ─▶ [retry-*] ─▶ [dlt]

outbox ─relay─▶ [payment-notifications] ─▶ consumer ─POST assinado─▶ Loja (cliente-webhook)
                                                       └─ falhou ─▶ [retry-*] ─▶ [dlt]
```

> **Branch `feature/kafka`.** Esta versão usa o Kafka como transporte entre as etapas. A versão anterior, com o próprio Postgres no papel de fila (`FOR UPDATE SKIP LOCKED`), continua na branch `main`. A comparação entre as duas está em [Postgres como fila × Kafka](#postgres-como-fila--kafka).

Este sistema faz os dois papéis do webhook: **recebe** o aviso do provedor e **envia** o próprio aviso à loja (webhook de saída). A loja é o projeto [`cliente-webhook`](https://github.com/erickpr9/cliente-webhook), em outro repositório.

## O que a esteira garante

| Garantia | Como |
|---|---|
| Só aceita webhooks autênticos | HMAC-SHA256 de `{timestamp}.{body}`, comparação em tempo constante |
| Proteção contra replay | Janela de 5 minutos sobre o `X-Timestamp` (também assinado) |
| Resposta rápida ao provedor | A API só publica no Kafka e responde `202`; o processamento é assíncrono |
| Aceito = não se perde | O `202` só sai depois da confirmação do broker (`acks=all`, producer idempotente) |
| Broker fora do ar | A API responde `503` com `Retry-After`: o provedor reenvia depois |
| Ordem por pedido | Chave da mensagem = `order_id`: os eventos de um pedido caem na mesma partição |
| Nenhum evento processado duas vezes | Consumer idempotente: `UNIQUE (provider, provider_event_id)` + `ON CONFLICT DO NOTHING` + `SELECT ... FOR UPDATE` |
| Vários consumers em paralelo | Consumer group: as partições são divididas entre as instâncias |
| Recuperação se a aplicação cair | O offset só é confirmado depois do processamento; o Kafka entrega de novo |
| Atomicidade | Domínio + outbox + conclusão do evento na mesma transação |
| Falhas transitórias | Retry topics sem bloqueio (10s, 30s, 90s, 270s, 810s) e dead-letter após 6 tentativas |
| Falhas permanentes | Payload malformado e regra de negócio violada vão direto para a dead-letter |
| Eventos fora de ordem | Descarte de eventos mais antigos que o último aplicado (protege também contra a reordenação dos retry topics) |
| Concorrência no domínio | Lock otimista (`version`) em pedidos e pagamentos |
| Notificação desacoplada | Outbox + relay: o aviso nasce na mesma transação e o relay garante que chega ao Kafka |
| Aviso à loja autêntico | Webhook de saída assinado com HMAC-SHA256 (mesmo contrato da entrada) |
| Loja fora do ar não perde aviso | `5xx`, `429`, `408` e timeout vão para os retry topics; `4xx` é recusa definitiva (dead-letter) |
| Loja descarta reentregas | O `X-Event-Id` do aviso é o id da notificação, igual em todas as tentativas |
| Loja lenta não trava o consumer | Timeouts de conexão (2s) e de resposta (5s) |

## Arquitetura

Hexagonal, com a regra de dependência verificada por testes ArchUnit:

```
api ──▶ application ──▶ domain ◀── infrastructure
```

| Camada | Conteúdo | Depende de |
|---|---|---|
| `domain` | `Payment`, `Order`, `Money`, máquina de estados, `RetryPolicy` | só do JDK |
| `application` | Casos de uso, handlers (Strategy), portas | `domain`, SLF4J |
| `infrastructure` | Kafka (publishers, listeners, relay), JDBC (`NamedParameterJdbcTemplate`), Micrometer, wiring | `application`, `domain`, Spring |
| `api` | Controllers, verificação HMAC, filtro de tamanho, erros | `application` |
| `dev` | Simulador do provedor (somente profile `local`) | `api`, `application` |

A camada de aplicação não usa anotações do Spring nem conhece o Kafka. Os casos de uso falam com portas (`WebhookEventPublisher`, `NotificationPublisher`) e devolvem um `ConsumeOutcome` (`COMPLETED`, `ALREADY_HANDLED`, `RETRY`, `DEAD`), que o listener traduz em movimento na esteira. Um teste ArchUnit garante que `org.apache.kafka` e `org.springframework.kafka` só aparecem em `infrastructure`. Os casos de uso são montados em `infrastructure/config/UseCaseConfig`.

### A esteira, etapa por etapa

| Etapa | Classes | O que faz |
|---|---|---|
| Recepção | `PaymentWebhookController` → `RegisterWebhookEventUseCase` → `KafkaWebhookEventPublisher` | Valida HMAC e envelope, publica o corpo original com chave `order_id` e espera a confirmação do broker |
| Consumo | `PaymentWebhookListener` → `ProcessWebhookEventUseCase` | Grava o evento (se novo), bloqueia a linha, aplica a transição e grava o outbox, tudo numa transação |
| Retentativa | `@RetryableTopic` no listener | `RETRY` vira `RetryLaterException` (próximo tópico de retry); `DEAD` vira `DeadLetterException` (direto para a DLT) |
| Relay | `NotificationRelayJob` → `RelayPendingNotificationsUseCase` | A cada 1s publica as notificações `PENDING` (`SKIP LOCKED`) e marca `PUBLISHED` |
| Entrega | `NotificationDeliveryListener` → `DeliverNotificationUseCase` → `HttpNotificationSender` | Abre a tentativa no outbox, envia o webhook assinado à loja e marca `SENT` |

### Tópicos

| Tópico | Partições | Chave | Conteúdo |
|---|---|---|---|
| `payment-webhooks` | 3 | `order_id` (ou o id do evento) | Corpo original do webhook; headers `provider`, `event-id`, `event-type`, `occurred-at`, `received-at` |
| `payment-webhooks-retry-10000` … `-retry-810000` | 3 | igual | Cópias esperando a próxima tentativa (o sufixo é o atraso em ms) |
| `payment-webhooks-dlt` | 3 | igual | Eventos que esgotaram as tentativas ou tiveram erro permanente |
| `payment-notifications` | 3 | `order_id` | Notificação em JSON; header `notification-id` |
| `payment-notifications-retry-*` e `-dlt` | 3 | igual | Retentativa e dead-letter das entregas à loja |

Consumer groups: `webhook-processor` e `notification-dispatcher`, mais um grupo por tópico de retry e de DLT, criados pelo Spring Kafka.

## Como rodar

Pré-requisitos: Java 21 e Docker.

1. Suba a infraestrutura: Postgres na porta **5433** (para não conflitar com um Postgres local na 5432), Kafka na **9092** (modo KRaft, sem Zookeeper) e Kafka UI na **8085**.

```powershell
docker compose up -d
```

2. Rode a aplicação no profile `local`. Ele aplica os dados de exemplo, cria os tópicos e liga os endpoints de admin, de simulação e o Swagger.

```powershell
$env:SPRING_PROFILES_ACTIVE="local"; .\gradlew.bat bootRun
```

3. No Postman, importe os dois arquivos da pasta [`postman/`](postman) (ou use a collection já criada no seu workspace) e selecione o environment **Webhook Study - Local**.

4. Envie **1. Webhooks (provedor) › Pagamento confirmado** e confira em **2. Pedidos › Consultar pedido**: o pedido `ord_1042` fica `PAID`.

5. Abra o Kafka UI em http://localhost:8085 e veja a mensagem em **Topics › payment-webhooks › Messages**: chave, headers, partição e offset.

Se o banco veio da versão `main`, a migration `V4` adapta as tabelas: remove o lease dos workers e cria o status `PUBLISHED` no outbox. Para voltar para a `main` depois, recrie o banco com `docker compose down -v`.

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

Para customizar, copie `.env.example` para `.env`. Fora do profile `local`, `DB_PASSWORD` e `WEBHOOK_PAYMENT_SECRET` (mínimo de 32 caracteres) são obrigatórios: a aplicação não sobe sem eles. O endereço do Kafka vem de `KAFKA_BOOTSTRAP_SERVERS` (padrão `localhost:9092`).

| Propriedade | Padrão | Para quê |
|---|---|---|
| `webhook.kafka.partitions` | `3` | Partições de cada tópico: o paralelismo máximo de cada consumer group |
| `webhook.kafka.publish-timeout` | `5s` | Espera máxima pela confirmação do broker na recepção |
| `webhook.kafka.events.retry.*` | 6 tentativas, 10s × 3, máx. 1h | Retentativa do processamento (`attempts`, `delay-ms`, `multiplier`, `max-delay-ms`) |
| `webhook.kafka.notifications.retry.*` | 5 tentativas, 30s × 4, máx. 1h | Retentativa da entrega à loja |
| `webhook.relay.interval` e `batch-size` | `1s` e `100` | Ritmo do relay do outbox |

A entrega à loja usa `WEBHOOK_NOTIFICATION_URL` e `WEBHOOK_NOTIFICATION_SECRET`. No profile `local` elas já apontam para a loja em `http://localhost:8081`. Sem URL, as notificações só vão para o log. Se a loja não estiver rodando, as notificações passam pelos tópicos de retry e são entregues quando ela subir, dentro das tentativas.

## Roteiro de testes no Postman

A collection fica na pasta [`postman/`](postman). Na pasta **1. Webhooks (provedor)**, um pre-request script gera o `eventId`, o `createdAt` e a assinatura HMAC a cada envio, então você só clica em **Send**.

Os webhooks usam as variáveis da collection `orderId`, `paymentId` e `amount`. O request **4. Simulação › Criar pedido** cria um pedido novo e atualiza essas variáveis. Por isso o roteiro sempre começa por ele: cada pedido só pode ser confirmado uma vez.

### Onde acompanhar

O processamento é assíncrono, mas com o Kafka é quase imediato: o consumer recebe a mensagem em milissegundos, e o relay publica a notificação em até 1 segundo.

| Onde | O que mostra |
|---|---|
| **2. Pedidos › Consultar pedido** | Estado do pedido e do pagamento |
| **3. Admin › Listar eventos recentes** | Status, tentativas, nota e último erro de cada evento |
| Console da aplicação (terminal do `bootRun`) | Tópico, partição e offset de cada mensagem consumida, retentativas e dead-letter |
| Kafka UI (http://localhost:8085) | Mensagens de cada tópico, consumer groups, lag e a distribuição das partições |
| Console do Postman (*View › Show Postman Console*) | Variáveis atualizadas pelos scripts (`orderId`, `webhookEventId`) |
| **5. Observabilidade › Métricas** | Contadores `webhook_*` |

### 0. Preparação

1. Suba a infraestrutura e a aplicação no profile `local` (veja [Como rodar](#como-rodar)).
2. No Postman, selecione o environment **Webhook Study - Local**.
3. Envie **5. Observabilidade › Health**: deve responder `UP`, com o componente `kafka` mostrando o `clusterId`.

### 1. Caminho feliz: pagamento confirmado

| # | Request | Esperado |
|---|---|---|
| 1 | **4. Simulação › Criar pedido** | `201`; o console do Postman mostra o novo `orderId` |
| 2 | **2. Pedidos › Consultar pedido** | pedido `PENDING_PAYMENT`, pagamento `PENDING` |
| 3 | **1. Webhooks › Pagamento confirmado** | `202` `received` |
| 4 | **2. Pedidos › Consultar pedido** (aguarde ~3s) | pedido `PAID`, pagamento `CONFIRMED` |
| 5 | **3. Admin › Listar eventos recentes** | evento `PROCESSED`, nota `Payment CONFIRMED, order PAID` |

No console da aplicação aparece o caminho da mensagem: `Webhook recebido ... publicado na esteira`, `Evento ... consumido de payment-webhooks (partição N, offset M, chave ord_...)` e, depois, `Aviso ... consumido de payment-notifications`.

### 2. Idempotência: o provedor reenvia o mesmo evento

Continue com o mesmo pedido.

| # | Request | Esperado |
|---|---|---|
| 1 | **1. Webhooks › Reentrega (mesmo evento)** | `202` `received` |
| 2 | **1. Webhooks › Reentrega (mesmo evento)** de novo | `202` `received` também |
| 3 | **3. Admin › Listar eventos recentes** | só um evento `evt_redelivery_demo` |

A recepção não consulta o banco, então não sabe que o evento é repetido: as duas cópias vão para o tópico. Quem descarta é o consumer, e o log mostra `Mensagem repetida: o evento já tinha sido tratado`. No Kafka UI aparecem as duas mensagens, em offsets seguidos na mesma partição. Esse é o trade-off da recepção pelo Kafka: ela fica mais rápida e independente do banco, e a resposta fica menos informativa.

O id `evt_redelivery_demo` é fixo. Para repetir o cenário com outro pedido, troque o valor do header `X-Event-Id` no request.

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
| 4 | **3. Admin › Listar eventos recentes** (repita algumas vezes) | evento `RETRY_SCHEDULED`, com as tentativas subindo e erro `PaymentNotFoundException`; no Kafka UI, a mensagem aparece em `payment-webhooks-retry-10000` |
| 5 | **1. Webhooks › Tipo de evento desconhecido** | `202`; no Admin, evento `IGNORED` |
| 6 | **1. Webhooks › Assinatura inválida (401)** | `401`; nada novo no Admin |

O erro de valor é permanente, por isso vai direto para `payment-webhooks-dlt` e o evento fica `DEAD`. O de pagamento inexistente é transitório, porque o pagamento pode ser criado depois. A mensagem passa por um tópico de retry para cada atraso (10s, 30s, 90s, 270s e 810s) e só chega à DLT depois da 6ª tentativa, cerca de 20 minutos depois.

Para ver a dead-letter por esgotamento em poucos segundos, suba a aplicação com retentativas curtas:

```powershell
$env:SPRING_PROFILES_ACTIVE="local"; .\gradlew.bat bootRun --args="--webhook.kafka.events.retry.delay-ms=2000 --webhook.kafka.events.retry.multiplier=2 --webhook.kafka.events.retry.attempts=3"
```

Com isso a mensagem passa por `payment-webhooks-retry-2000` e `payment-webhooks-retry-4000` e chega a `payment-webhooks-dlt` em cerca de 6 segundos. Os nomes dos tópicos de retry mudam com os atrasos, porque o sufixo é o atraso em milissegundos.

### 7. Reprocessamento manual

| # | Request | Esperado |
|---|---|---|
| 1 | **3. Admin › Listar eventos DEAD** | guarda o id do primeiro evento em `webhookEventId` |
| 2 | **3. Admin › Reprocessar evento** | `202`; evento `RETRY_SCHEDULED` com 0 tentativas |
| 3 | **3. Admin › Consultar evento** | o erro permanente volta para `DEAD` na hora; o transitório passa de novo pelos retry topics |

O reprocessamento zera as tentativas e **publica de novo** o payload guardado no tópico principal: no Kafka UI aparece uma mensagem nova em `payment-webhooks`. O evento volta para `DEAD` porque a causa não foi corrigida, e esse é o comportamento esperado. Em produção, o reprocessamento vem depois da correção (ex.: o pagamento que faltava foi criado). Reprocessar um evento que não está `DEAD` responde `422`.

### 8. Simulação pela própria aplicação

| # | Request | Esperado |
|---|---|---|
| 1 | **4. Simulação › Criar pedido** | `201` |
| 2 | **4. Simulação › Simular evento pela aplicação** | `200`, com `httpStatus: 202` |
| 3 | **2. Pedidos › Consultar pedido** | pedido `PAID` |

Aqui é a aplicação que monta, assina e envia o webhook para o próprio endpoint, como o provedor faria.

### 9. Métricas

Envie **5. Observabilidade › Métricas** e procure por `webhook_received_total` (aceitos e `unavailable`, quando o Kafka está fora), `webhook_processed_total`, `webhook_failed_total`, `webhook_dead_total` e `webhook_notification_sent_total`. As métricas do próprio cliente do Kafka também aparecem, como `kafka_consumer_fetch_manager_records_lag_max` (lag) e `kafka_producer_record_send_total`.

### 10. O aviso chegando na loja

Com a loja ([`cliente-webhook`](https://github.com/erickpr9/cliente-webhook)) rodando, cada pagamento confirmado gera um webhook de saída. Acompanhe pelos dois lados:

| # | Collection | Request | Esperado |
|---|---|---|---|
| 1 | Plataforma | **3. Admin › Listar notificações (entregas à loja)** | notificação `SENT` |
| 2 | Loja | **1. Loja › Avisos recebidos** | aviso `APPLIED` |
| 3 | Loja | **1. Loja › Consultar pedido** | `READY_TO_SHIP` |

Os cenários de falha da loja (fora do ar, lenta e recusando) estão no [README do cliente-webhook](https://github.com/erickpr9/cliente-webhook#roteiro-no-postman).

### 11. Teste em massa

A pasta **6. Carga (dev)** cria muitos pedidos e confirma o pagamento de todos de uma vez, com um único clique. Ajuste `loadCount` (quantidade, padrão 100) e `loadConcurrency` (requests em paralelo, padrão 10) nas variáveis da collection.

| # | Request | Esperado |
|---|---|---|
| 1 | **6. Carga › Disparar carga (N pedidos + pagamentos)** | testes verdes: todos os pedidos criados e todos os webhooks aceitos (`202`), com o tempo e a vazão no Console do Postman |
| 2 | **6. Carga › Acompanhar carga** (repita a cada ~5s) | contagem por status: eventos `PROCESSED` e notificações `PUBLISHED` → `SENT` |
| 3 | **6. Carga › Acompanhar carga** | os dois testes passam quando tudo foi processado e entregue à loja |

Um evento só aparece no Admin quando é consumido. O que ainda está no tópico esperando é o **lag** do consumer group `webhook-processor`, visível no Kafka UI (**Consumers**).

Medição local, com a loja rodando, 500 pedidos e 50 requests em paralelo:

| Etapa | `main` (Postgres como fila) | `feature/kafka` |
|---|---|---|
| Aceitar os 500 webhooks | menos de 1s | 0,8s |
| Processar os 500 eventos | cerca de 50s | cerca de 2,5s |
| Entregar os 500 avisos à loja | pouco mais de 1 minuto | cerca de 7s |

Na `main`, o worker busca 20 eventos a cada 2 segundos, então a vazão fica em torno de 10 por segundo. Com o Kafka não há polling: o consumer recebe as mensagens assim que chegam, e o limite passa a ser o tempo de cada transação no banco e de cada chamada à loja. Nos dois casos, aceitar é muito mais rápido que processar: a fila (a inbox na `main`, o tópico aqui) absorve o pico.

O acompanhamento olha os 500 eventos e notificações mais recentes. Para cargas maiores, use o lag no Kafka UI ou as métricas.

Pedidos de exemplo já carregados no banco: `ord_1042`/`pay_001` (PIX), `ord_1043`/`pay_002` (cartão de crédito), `ord_1044`/`pay_003` (boleto). Também dá para usar o Swagger em http://localhost:8080/swagger-ui.html.

## Experimentos com o Kafka

Roteiro para ver cada conceito acontecendo. Deixe o Kafka UI aberto em http://localhost:8085.

| # | Experimento | Como | O que observar |
|---|---|---|---|
| 1 | Anatomia de uma mensagem | Envie **Pagamento confirmado** e abra **Topics › payment-webhooks › Messages** | Chave (`order_id`), valor (o corpo original, byte a byte), headers e offset |
| 2 | Lag | Rode a pasta **6. Carga** com `loadCount=500` e abra **Consumers › webhook-processor** | O lag sobe durante a carga e cai até zero |
| 3 | Rebalanceamento | Durante uma carga, suba uma 2ª instância com `--server.port=8082` | No Kafka UI, as 3 partições divididas entre as duas instâncias; cada evento processado uma vez só |
| 4 | Ordem por chave | Confirme e estorne o mesmo pedido em sequência | As duas mensagens na mesma partição, em offsets seguidos, processadas na ordem |
| 5 | Queda no meio do processamento | Derrube a aplicação (Ctrl+C) durante uma carga e suba de novo | As mensagens sem offset confirmado são entregues de novo, e cada pedido continua com um único aviso no outbox |
| 6 | Broker fora do ar | `docker stop webhook-kafka` e envie um webhook | `503` com `Retry-After: 30` em cerca de 5 segundos (o `publish-timeout`). Depois, `docker start webhook-kafka` |
| 7 | Retry topics | **Pagamento inexistente**, com as retentativas curtas da seção 6 | A mensagem passando por `retry-2000` e `retry-4000` e chegando à `dlt` |
| 8 | Erro permanente | **Valor divergente** | Vai direto para a `dlt`, sem passar pelos tópicos de retry |
| 9 | Reprocessamento | **Reprocessar evento** de um `DEAD` | Mensagem nova no tópico principal, republicada a partir do banco |
| 10 | Loja fora do ar | Pare a loja e confirme um pagamento | O aviso passando por `payment-notifications-retry-*` e sendo entregue quando a loja volta |

O que apareceu ao rodar esses experimentos localmente:

- **Partições limitam o paralelismo.** Com duas instâncias, cada uma com `concurrency=3`, o grupo ficou com 6 consumers para 3 partições: uma instância pegou 1 partição, a outra 2, e 3 consumers ficaram ociosos (`partitions assigned: []`). Para escalar além disso, é preciso aumentar as partições.
- **Queda sem aviso custa o `session.timeout.ms`.** Ao matar uma instância no meio de uma carga de 1000 pedidos, as partições dela ficaram paradas por cerca de 40 segundos, até o broker perceber a ausência (o padrão é 45s) e redistribuí-las. Depois disso, a outra instância terminou o trabalho: 1000 eventos processados, cada um com uma única tentativa, e nenhum aviso duplicado. Com um desligamento normal (Ctrl+C), o consumer avisa que está saindo e o rebalanceamento é imediato.
- **O mesmo vale ao reiniciar a aplicação depois de uma queda.** O grupo espera os membros antigos expirarem antes de distribuir as partições, então as primeiras mensagens podem demorar esses mesmos segundos para começar a ser consumidas.
- **Broker fora do ar.** O webhook recebeu `503` com `Retry-After: 30` depois de 5 segundos (o `publish-timeout`), e o `/actuator/health` passou a mostrar `kafka: DOWN`. Com o broker de volta, o próximo webhook foi aceito em 37 ms.

### Ordem e retry topics

Os retry topics são **sem bloqueio**: o evento que falhou sai da frente, e os próximos da mesma partição seguem. Isso é bom para a vazão, porque um evento ruim não trava os outros, mas quebra a ordem por pedido. Um estorno pode ser processado antes de uma confirmação que está esperando no tópico de retry.

O que protege a consistência é a regra de domínio que já existia: cada pagamento guarda o instante do último evento aplicado, e um evento mais antigo é descartado (`STALE_EVENT`). A alternativa seria a retentativa com bloqueio (`DefaultErrorHandler` com backoff), que mantém a ordem mas para a partição inteira até o evento passar.

### Postgres como fila × Kafka

| | `main`: Postgres como fila | `feature/kafka`: Kafka |
|---|---|---|
| Infraestrutura | Só o Postgres | Postgres e Kafka |
| Recepção | Grava no banco; sabe responder `200 duplicate` | Publica no broker; sempre `202`, deduplica no consumo |
| Latência até processar | Até um ciclo do worker (2s) | Milissegundos |
| Vazão | Limitada pelo lote e pelo intervalo do worker | Limitada pelas partições e pelo tempo de cada transação |
| Paralelismo | `SKIP LOCKED`: qualquer número de workers | Até um consumer por partição em cada consumer group |
| Ordem | Por `received_at`, sem garantia por pedido | Por partição (chave `order_id`), quebrada só pelos retry topics |
| Retentativa | `next_attempt_at` na própria linha | Tópicos de retry com atraso |
| Visibilidade do que falta | `SELECT` na inbox | Lag do consumer group |
| Vários interessados no mesmo evento | Cada um precisa de uma tabela ou coluna | Basta outro consumer group |
| Reprocessar o histórico | Só o que está no banco | Reler o tópico, dentro da retenção |

Para volumes moderados, com um único consumidor, o Postgres como fila é mais simples e suficiente. O Kafka compensa quando há picos grandes, vários serviços interessados no mesmo evento ou necessidade de reprocessar o histórico.

## Endpoints

| Método e rota | Descrição | Disponível |
|---|---|---|
| `POST /v1/webhooks/payments` | Recebe o webhook (`X-Event-Id`, `X-Timestamp`, `X-Signature`) | sempre |
| `GET /v1/orders/{orderId}` | Pedido e estado dos pagamentos | sempre |
| `GET /admin/webhook-events?status=DEAD&limit=50` | Eventos consumidos e seu resultado | `webhook.admin.enabled` |
| `GET /admin/webhook-events/{id}` | Detalhe de um evento | `webhook.admin.enabled` |
| `POST /admin/webhook-events/{id}/reprocess` | Recoloca um evento DEAD na esteira (republica no Kafka) | `webhook.admin.enabled` |
| `GET /admin/notifications?limit=20` | Entregas à loja: status, tentativas e último erro | `webhook.admin.enabled` |
| `POST /dev/orders` | Cria pedido com pagamento pendente | `webhook.dev.simulation-enabled` |
| `POST /dev/simulations/payment-events` | Envia um webhook assinado pelo caminho HTTP real | `webhook.dev.simulation-enabled` |
| `GET /actuator/health`, `/actuator/prometheus` | Saúde (banco e Kafka) e métricas | sempre |

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
| `202` | Evento aceito e gravado no Kafka (reentregas também: o consumo descarta a cópia) |
| `400` | Header ausente, timestamp não numérico, JSON inválido, `X-Event-Id` diferente do `id` |
| `401` | Assinatura inválida ou fora da janela de tempo |
| `413` | Corpo acima de 64 KB |
| `415` | `Content-Type` diferente de JSON |
| `503` | Kafka indisponível, com header `Retry-After: 30`. Nada foi aceito: o provedor deve reenviar |

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
| `5xx`, `408`, `429`, timeout, conexão recusada | Retry topics com backoff (30s, 2m, 8m, 32m); dead-letter e `DEAD` após 5 tentativas |
| Demais `4xx` | Recusa definitiva: direto para a dead-letter, `DEAD` na hora |

A entrega é "pelo menos uma vez": se a plataforma não receber a resposta (timeout), ela reenvia o mesmo aviso. Cabe à loja descartar a reentrega pelo `X-Event-Id`.

## Métricas

`webhook.received` (tipo, resultado: `accepted` ou `unavailable`), `webhook.processed` (tipo, status), `webhook.failed` (tipo, retry/dead), `webhook.dead`, `webhook.processing.time`, `webhook.notification.sent` e `webhook.notification.failed`. Tipos de evento desconhecidos viram `unknown`, para não explodir a cardinalidade. As métricas dos clientes do Kafka (producer e consumers, incluindo o lag) são registradas automaticamente pelo Spring Boot.

## Testes

```powershell
.\gradlew.bat check
```

Roda 283 testes e exige cobertura mínima de 80% (JaCoCo). Os testes de integração usam Testcontainers (Postgres e Kafka), então o Docker precisa estar ativo.

| Tipo | Exemplos |
|---|---|
| Domínio (JUnit puro) | Matriz de transições, eventos fora de ordem, `Money`, `RetryPolicy` |
| Aplicação (Mockito) | Idempotência do consumo, retentativa, DEAD, relay, classificação de falhas |
| Kafka (unidade) | Mapeamento de mensagens e headers, resultado do caso de uso → retry ou DLT, timeout de publicação |
| Webhook de saída (`MockRestServiceServer`) | Assinatura sobre o corpo exato, classificação de `2xx`/`4xx`/`5xx`/timeout |
| API (`@WebMvcTest`) | 202, 400, 401, 413, 415, 503, chave de ordenação, mensagens de erro |
| Repositórios (Postgres real) | `ON CONFLICT`, bloqueio da linha entre cópias concorrentes, `SKIP LOCKED` no relay |
| Ponta a ponta (Postgres e Kafka reais) | Fluxo completo, reentregas simultâneas, retry topics até o pagamento existir, DLT, reprocessamento |
| Arquitetura (ArchUnit) | Regra de dependência entre as camadas; Kafka só em `infrastructure` |

Testcontainers é usado em vez de H2 porque `ON CONFLICT`, `SKIP LOCKED` e `JSONB` não têm equivalência confiável no H2.

## Próximos passos possíveis

- Trocar o relay por polling pelo Debezium (CDC), lendo o outbox direto do WAL do Postgres.
- Schema das mensagens (Avro ou JSON Schema com Schema Registry), para evoluir o contrato entre producer e consumer.
- Adicionar jitter ao backoff, para evitar retentativas sincronizadas.
- Autenticar os endpoints de admin para uso fora do ambiente local.
- Rotação de segredo HMAC (aceitar dois segredos durante a troca).
- Particionar ou arquivar o registro de eventos para volumes altos.

O plano original está em [docs/PLANO-IMPLEMENTACAO.md](docs/PLANO-IMPLEMENTACAO.md), e o da migração para o Kafka em [docs/PLANO-KAFKA.md](docs/PLANO-KAFKA.md).
