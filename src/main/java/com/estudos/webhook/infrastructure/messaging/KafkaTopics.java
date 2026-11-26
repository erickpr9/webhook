package com.estudos.webhook.infrastructure.messaging;

/**
 * Tópicos e headers da esteira.
 *
 * <p>Os tópicos de retry e de dead-letter são criados pelo Spring Kafka a partir dos principais,
 * com sufixos: {@code payment-webhooks-retry-10000}, ..., {@code payment-webhooks-dlt}.
 */
public final class KafkaTopics {

    /** Webhooks recebidos do provedor. Chave: pedido. Valor: corpo original. */
    public static final String PAYMENT_WEBHOOKS = "payment-webhooks";

    /** Notificações a entregar à loja. Chave: pedido. Valor: notificação em JSON. */
    public static final String PAYMENT_NOTIFICATIONS = "payment-notifications";

    /** Consumer group que processa os webhooks. */
    public static final String WEBHOOK_PROCESSOR_GROUP = "webhook-processor";

    /** Consumer group que entrega as notificações. */
    public static final String NOTIFICATION_DISPATCHER_GROUP = "notification-dispatcher";

    public static final String HEADER_PROVIDER = "provider";
    public static final String HEADER_EVENT_ID = "event-id";
    public static final String HEADER_EVENT_TYPE = "event-type";
    public static final String HEADER_OCCURRED_AT = "occurred-at";
    public static final String HEADER_RECEIVED_AT = "received-at";
    public static final String HEADER_NOTIFICATION_ID = "notification-id";

    private KafkaTopics() {
    }
}
