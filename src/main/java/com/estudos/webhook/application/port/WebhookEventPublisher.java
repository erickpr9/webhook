package com.estudos.webhook.application.port;

import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.exception.EventPublishingException;

/**
 * Porta de entrada na esteira: publica um webhook recebido para processamento assíncrono.
 *
 * <p>Só deve retornar depois que a mensagem estiver gravada de forma durável (no Kafka, com a
 * confirmação do broker). Assim, responder 202 ao provedor significa que o evento não se perde.
 */
public interface WebhookEventPublisher {

    /**
     * @throws EventPublishingException se não foi possível publicar a tempo
     */
    void publish(ReceivedWebhookEvent event);
}
