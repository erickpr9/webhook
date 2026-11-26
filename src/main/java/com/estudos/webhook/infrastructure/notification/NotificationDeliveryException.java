package com.estudos.webhook.infrastructure.notification;

/**
 * Falha transitória na entrega ao cliente (fora do ar, timeout, {@code 5xx}, {@code 429}).
 * A notificação será enviada de novo conforme a política de retentativa.
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
