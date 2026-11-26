package com.estudos.webhook.application.port;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.exception.PermanentDeliveryException;

import java.util.UUID;

/**
 * Porta de envio de notificações ao cliente (webhook de saída, e-mail, push etc.).
 *
 * <p>O {@code notificationId} é o mesmo em todas as tentativas de uma notificação, para que o
 * destinatário consiga descartar reentregas (idempotência do lado de quem recebe).
 *
 * <p>Implementações devem lançar exceção quando o envio falhar: {@link PermanentDeliveryException}
 * quando não adianta tentar de novo; qualquer outra exceção aciona a retentativa.
 */
public interface NotificationSender {

    void send(UUID notificationId, NotificationMessage message);
}
