package com.estudos.webhook.infrastructure.notification;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingNotificationSenderTest {

    @Test
    void neverFails() {
        var message = new NotificationMessage("PAYMENT_CONFIRMED", "pay", "ord", 1, "BRL", "CONFIRMED", TestData.NOW);

        assertThatCode(() -> new LoggingNotificationSender().send(UUID.randomUUID(), message))
                .doesNotThrowAnyException();
    }
}
