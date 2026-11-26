package com.estudos.webhook.domain.model;

import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class WebhookEventTest {

    @Test
    void receivedEventIsReadyForImmediateProcessing() {
        UUID id = UUID.randomUUID();

        WebhookEvent event = WebhookEvent.received(id, "gateway", "evt_1", "payment.confirmed", "{}",
                TestData.NOW.minusSeconds(5), TestData.NOW);

        assertThat(event.id()).isEqualTo(id);
        assertThat(event.status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(event.attempts()).isZero();
        assertThat(event.nextAttemptAt()).isEqualTo(TestData.NOW);
        assertThat(event.processedAt()).isNull();
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThatNullPointerException().isThrownBy(() -> WebhookEvent.received(UUID.randomUUID(), "gateway", null,
                "payment.confirmed", "{}", TestData.NOW, TestData.NOW));
    }

    @Test
    void rejectsNegativeAttempts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new WebhookEvent(UUID.randomUUID(), "gateway", "evt",
                "type", "{}", WebhookEventStatus.RECEIVED, -1, TestData.NOW, TestData.NOW, TestData.NOW,
                null, null, null));
    }
}
