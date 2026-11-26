package com.estudos.webhook.application.port;

import com.estudos.webhook.application.dto.OutboxNotification;
import com.estudos.webhook.application.exception.EventPublishingException;

/**
 * Porta usada pelo relay do outbox: publica uma notificação na esteira de entrega.
 *
 * <p>Deve retornar só depois da confirmação durável. Pode publicar a mesma notificação mais de
 * uma vez (o relay reenvia se cair antes de marcar), por isso a entrega é idempotente pelo id.
 */
public interface NotificationPublisher {

    /**
     * @throws EventPublishingException se não foi possível publicar a tempo
     */
    void publish(OutboxNotification notification);
}
