package com.estudos.webhook.infrastructure.notification;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.port.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Envio simulado: apenas registra a notificação no log. Usado quando não há URL de cliente
 * configurada ({@code webhook.notification.endpoint-url} vazio).
 */
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(UUID notificationId, NotificationMessage message) {
        log.info("Aviso {} do pedido {} (pagamento {}, status {}) apenas registrado no log: nenhuma URL de loja "
                        + "configurada em webhook.notification.endpoint-url", message.type(), message.orderId(),
                message.paymentId(), message.paymentStatus());
    }
}
