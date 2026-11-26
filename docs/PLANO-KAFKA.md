# Plano de implementação: esteira com Kafka

Este plano troca o **transporte interno** da esteira (hoje, tabelas do Postgres consultadas por workers) por **tópicos do Kafka**. As pontas continuam HTTP: o provedor chama a plataforma e a plataforma chama a loja. O domínio, a assinatura HMAC, o contrato com a loja e o projeto `cliente-webhook` não mudam.

Objetivo de estudo: aprender producer, consumer, partições, chave, consumer group, offset, rebalanceamento, retry topics, dead-letter topic, lag e outbox com relay, usando um problema que já está resolvido e testado.

## 0. Decisões para validar

| # | Decisão | Recomendação | Alternativa |
|---|---|---|---|
| D1 | Onde o Kafka entra na recepção | **O controller publica direto no Kafka** e só responde `202` depois da confirmação do broker. A tabela `webhook_events` vira o registro de processamento do consumer (idempotência e consulta no Admin). | Manter a inbox no Postgres e levar os eventos para o Kafka depois, via outbox. Mais seguro, mas o Kafka fica escondido no meio e o aprendizado diminui. |
| D2 | Convivência com a versão atual | **Branch `feature/kafka`**, substituindo os workers de polling. A `main` continua com a versão Postgres, para comparar lado a lado. | Uma propriedade `webhook.transport=database\|kafka` com as duas versões no mesmo código. Mais flexível, mas dobra o código e os testes. |
| D3 | Retentativa | **Retry topics sem bloqueio** (`@RetryableTopic`): o evento com falha vai para um tópico de espera, e a partição segue andando. | Retentativa com bloqueio (`DefaultErrorHandler` com backoff): a partição para até o evento passar ou ir para a DLT. Mantém a ordem, mas um evento ruim trava os outros. |
| D4 | Publicação do outbox | **Relay por polling**: um job lê o outbox e publica no tópico. | Debezium (CDC) lendo o WAL do Postgres. Fica como etapa opcional no fim, porque exige Kafka Connect. |

## 1. Visão geral

```
                         PLATAFORMA (webhook, 8080)
Provedor ──HTTP──▶ PaymentWebhookController
                   valida HMAC → publica (key = order_id) → 202
                          │
                          ▼
              ┌─ payment-webhooks (3 partições) ─┐
              │                                  │
              ▼                                  │ falha transitória
     PaymentWebhookListener (consumer group)     ├──▶ payment-webhooks-retry-10s ─▶ ...-retry-1h
     idempotência em webhook_events              │                                    │
     aplica no pedido + grava outbox (1 tx)      │     esgotou ou erro permanente     ▼
              │                                  └────────────────────────▶ payment-webhooks-dlt
              ▼
     notification_outbox ──▶ NotificationRelayJob ──▶ payment-notifications (3 partições)
                                                            │
                                                            ▼
                                          NotificationDispatchListener ──HTTP──▶ Loja (8081)
                                          falha 5xx/timeout ─▶ retry topics · 4xx ─▶ DLT
```

O que **sai** da versão atual:
- `ProcessPendingEventsJob`, `ProcessPendingWebhookEventsUseCase` e o `claimDue` com lease para eventos;
- `DispatchNotificationsJob` e o `claimDue` de notificações;
- o `RetryPolicy` como agendador de retentativa. A política passa a ser a configuração dos retry topics.

O que **fica**: domínio (`Order`, `Payment`, transições, `STALE_EVENT`), os handlers, `FailureClassifier`, `HttpNotificationSender`, `HmacSigner`, os endpoints de Admin e a simulação, as métricas e a loja.

## 2. Infraestrutura (Docker, grátis)

Adicionar ao `docker-compose.yml`:

| Serviço | Imagem | Porta no host | Para quê |
|---|---|---|---|
| `kafka` | `apache/kafka` (modo KRaft, sem Zookeeper, nó único) | `9092` | Broker |
| `kafka-ui` | `kafbat/kafka-ui` | `8085` | Ver tópicos, partições, mensagens, consumer groups e lag |

A versão das imagens fica fixada no compose, e não `latest`. Os tópicos são criados pela aplicação (beans `NewTopic`): 3 partições, réplica 1 (só existe um broker local).

## 3. Dependências

- `org.springframework.kafka:spring-kafka`, com versão gerenciada pelo Spring Boot;
- `org.testcontainers:kafka` para os testes de integração;
- tudo pelo version catalog (`gradle/libs.versions.toml`), como o resto do projeto.

## 4. Etapas

### Etapa 1: Recepção publica no Kafka

- Nova porta em `application`: `WebhookEventPublisher.publish(ReceivedWebhookEvent)`. O `RegisterWebhookEventUseCase` passa a publicar em vez de gravar.
- Implementação `KafkaWebhookEventPublisher` em `infrastructure/messaging`:
  - **valor** = corpo original, exatamente como chegou (o mesmo que foi assinado);
  - **chave** = `order_id`, para que os eventos de um pedido caiam na mesma partição e sejam processados em ordem;
  - **headers** = `provider`, `event-id`, `event-type`, `received-at`;
  - producer com `acks=all` e `enable.idempotence=true`; o envio espera a confirmação com timeout curto (ex.: 3s).
- Kafka fora do ar → `503 Service Unavailable`. O provedor reenvia depois, como qualquer provedor faz.
- **Muda no contrato:** a recepção não consulta o banco, então não sabe mais se o evento é repetido. Ela sempre responde `202`, e a deduplicação acontece no consumer. O README vai registrar esse trade-off: a recepção fica mais rápida e independente do banco, e a resposta fica menos informativa.

### Etapa 2: Consumer de eventos com idempotência

- `PaymentWebhookListener` (`@KafkaListener`, grupo `webhook-processor`, `concurrency=3`) chama o `ProcessWebhookEventUseCase`.
- A idempotência usa a tabela `webhook_events`, que já existe:
  1. `insertIfAbsent` do evento com status `RECEIVED`;
  2. "claim" condicional: `UPDATE ... SET status='PROCESSING' WHERE status IN ('RECEIVED','RETRY_SCHEDULED')`. Se nenhuma linha mudar, o evento já foi tratado e a mensagem é só confirmada;
  3. aplica a transição, grava o outbox e marca `PROCESSED`, **tudo na mesma transação**.
- O offset só é confirmado depois que o listener termina (`AckMode.RECORD`). Se a aplicação cair no meio, o Kafka entrega de novo, e o passo 2 evita o efeito em dobro. É o **at-least-once com efeito único** na prática.
- Migration `V4`: remove `locked_until` e os índices de polling, que deixam de ser usados.

### Etapa 3: Retentativa e dead-letter dos eventos

- `@RetryableTopic` no listener: tópicos `payment-webhooks-retry-*` com os mesmos atrasos de hoje (10s, 30s, 2m, 10m, 1h) e, por fim, `payment-webhooks-dlt`.
- Os erros permanentes classificados pelo `FailureClassifier` (`BusinessException`, `MalformedPayloadException`) vão **direto para a DLT**, sem retentativa.
- A cada falha, o registro em `webhook_events` é atualizado em transação separada (`RETRY_SCHEDULED`, `attempts`, `last_error`). Ao chegar na DLT, vira `DEAD`. Assim o Admin continua mostrando tudo.
- **Ponto de estudo:** os retry topics sem bloqueio quebram a ordem por pedido. Um estorno pode passar na frente de uma confirmação que está esperando. A regra de `STALE_EVENT` do domínio (comparação por `occurred_at`) é o que protege a consistência. O README vai mostrar esse caso.
- `POST /admin/webhook-events/{id}/reprocess` passa a recolocar o evento como `RETRY_SCHEDULED` e **republicar** o payload guardado no tópico principal.

### Etapa 4: Outbox e relay

- O outbox continua sendo gravado na mesma transação da mudança no pedido. Essa parte não muda e é o que garante não perder o aviso.
- `NotificationRelayJob` (a cada 1s) pega as linhas `PENDING` com `FOR UPDATE SKIP LOCKED`, publica em `payment-notifications` (chave `order_id`, header `notification-id`) e marca `PUBLISHED`.
- Se o relay cair depois de publicar e antes de marcar, a mensagem é publicada de novo. A loja já deduplica pelo `X-Event-Id` (id da notificação), então isso é seguro.
- Migration `V4`: novo status `PUBLISHED` na constraint do outbox.

### Etapa 5: Consumer de notificações (entrega à loja)

- `NotificationDispatchListener` (grupo `notification-dispatcher`) chama o `HttpNotificationSender`, que não muda.
- `@RetryableTopic`: `payment-notifications-retry-*` (30s, 2m, 10m, 1h) e `payment-notifications-dlt`. Respostas 4xx (exceto 408 e 429) vão direto para a DLT, como hoje.
- O status no outbox (`SENT`, `RETRY_SCHEDULED`, `DEAD`, com `attempts` e `last_error`) continua sendo atualizado, então **Listar notificações** e a pasta de carga do Postman seguem funcionando.

### Etapa 6: Observabilidade e Admin

- Métricas do Kafka (producer, consumer e lag) entram no `/actuator/prometheus` pelo Micrometer, além dos contadores `webhook_*` atuais.
- `GET /actuator/health` passa a mostrar o Kafka.
- Logs em pt-BR com partição e offset (ex.: `Evento evt_x consumido (partição 2, offset 418)`).
- A collection do Postman ganha links e instruções para o Kafka UI. Os requests não mudam.

### Etapa 7: Testes

| Tipo | O que cobre |
|---|---|
| Unidade | Use cases com o publisher fake; classificação de erro (retry ou DLT) |
| Integração (Testcontainers: Kafka e Postgres) | Publicação com chave e headers; consumo com idempotência (mesma mensagem duas vezes = um efeito); retry até a DLT; erro permanente direto na DLT; relay publicando e marcando |
| Ponta a ponta | Webhook → tópico → pedido `PAID` → outbox → tópico → loja simulada (servidor HTTP falso) recebendo o aviso assinado |
| Arquitetura (ArchUnit) | `org.springframework.kafka` só em `infrastructure` |

O gate de cobertura de 80% continua valendo.

### Etapa 8: Documentação e roteiro de experimentos

O README ganha um roteiro para ver cada conceito do Kafka acontecendo:

| # | Experimento | O que você aprende |
|---|---|---|
| 1 | Caminho feliz, olhando a mensagem no Kafka UI | Tópico, partição, offset, chave e headers |
| 2 | Carga de 500 pedidos (Postman, pasta 6) | Lag subindo e caindo no consumer group |
| 3 | Subir uma 2ª instância durante a carga | Rebalanceamento: as partições são divididas entre as instâncias |
| 4 | Vários eventos do mesmo pedido | Mesma chave = mesma partição = ordem garantida |
| 5 | Derrubar a aplicação no meio do processamento | Reentrega após o restart e idempotência evitando efeito em dobro |
| 6 | Parar o container do Kafka e enviar um webhook | `503` na recepção: o broker como dependência crítica |
| 7 | Pagamento inexistente | Mensagem passando pelos tópicos de retry até a DLT |
| 8 | Valor divergente | Erro permanente indo direto para a DLT |
| 9 | Reprocessar um evento `DEAD` | Republicar a partir do registro no banco |
| 10 | Loja fora do ar | Retentativa da entrega pelos tópicos de notificação |

Também entra uma seção **Postgres como fila × Kafka**, comparando as duas branches: vazão, complexidade, ordem, reprocessamento e o que cada uma exige para operar.

### Etapa 9 (opcional): CDC com Debezium

Trocar o `NotificationRelayJob` pelo Debezium lendo o WAL do Postgres. Ele publica o outbox no Kafka sem polling. Fica para depois, porque exige Kafka Connect e configuração de replicação lógica no Postgres.

## 5. O que não muda

- O projeto `cliente-webhook` (loja): zero alterações. Isso mostra que o transporte interno é um detalhe de quem envia.
- O contrato HTTP de entrada (headers, assinatura, payload), exceto pelo `200 duplicate` (veja a Etapa 1).
- A arquitetura hexagonal: o Kafka entra só como adaptador em `infrastructure`, atrás de portas.

## 6. Riscos e cuidados

| Risco | Cuidado |
|---|---|
| Docker no Windows com mais dois containers | Kafka com heap pequeno (`KAFKA_HEAP_OPTS=-Xmx512m`) |
| Retry topics com atrasos longos atrasam os testes | Atrasos configuráveis; nos testes, de milissegundos |
| Ordem quebrada pelos retry topics | Já coberta pela regra de `STALE_EVENT`; teste específico para isso |
| Recepção depende do broker | `503` claro e métrica de falha de publicação |

## 7. Ordem de entrega

Primeiro a infraestrutura e as dependências (seções 2 e 3), depois as etapas 1 a 8, uma por vez, com testes verdes ao final de cada uma. Cada etapa vira um commit na branch `feature/kafka`.

## 8. O que mudou em relação ao plano

Registro do que foi diferente na implementação, e por quê.

| Ponto | Plano | Implementado | Motivo |
|---|---|---|---|
| Atrasos dos retry topics | Os mesmos de antes (10s, 30s, 2m, 10m, 1h) | Exponencial: 10s, 30s, 90s, 270s, 810s (`delay-ms`, `multiplier`, `max-delay-ms`) | O `@RetryableTopic` trabalha com backoff fixo ou exponencial, não com uma lista arbitrária. A `RetryPolicy` do domínio ganhou `RetryPolicy.exponential`, alimentada pelas mesmas propriedades, para o banco e o broker concordarem |
| Status do evento | Claim com `UPDATE ... SET status = 'PROCESSING'` | Sem `PROCESSING`: insert, `SELECT ... FOR UPDATE` e processamento na mesma transação | Com o claim em transação separada, uma queda logo depois deixaria o evento preso em `PROCESSING`, e a reentrega o pularia. Com o lock da linha, uma queda desfaz tudo e a reentrega processa normalmente |
| Visibilidade no Admin | Igual à versão anterior | Um evento só aparece no Admin depois de consumido | O que ainda não foi consumido está no tópico: é o lag do consumer group, visível no Kafka UI |
| Saúde do Kafka | `/actuator/health` mostrando o Kafka | `KafkaHealthIndicator` próprio (`describeCluster` com timeout de 3s) | O Spring Boot não traz health indicator para o Kafka |
| Dead-letter | DLT ao fim das tentativas | DLT + `@DltHandler` que marca `DEAD` no banco se ainda não estiver | Cobre o caso em que o banco estava fora quando a última tentativa falhou |
| Mensagens fora do formato | Não previsto | `MalformedMessageException` vai direto para a DLT | Mensagem sem os headers esperados nunca vai dar certo |
| Logs do Spring Kafka | Não previsto | `DeadLetterPublishingRecovererFactory` desligado e `org.apache.kafka` em WARN | O caso de uso já registra o motivo de cada dead-letter, e o cliente do Kafka registra toda a configuração em INFO |
| CDC com Debezium (etapa 9) | Opcional | Não implementado | Fica como próximo passo |
