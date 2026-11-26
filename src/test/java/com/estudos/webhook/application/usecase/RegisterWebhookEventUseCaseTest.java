package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.dto.RegisterWebhookEventCommand;
import com.estudos.webhook.application.exception.EventPublishingException;
import com.estudos.webhook.application.port.WebhookEventPublisher;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RegisterWebhookEventUseCaseTest {

    private static final Instant OCCURRED_AT = TestData.NOW.minusSeconds(2);

    @Mock
    private WebhookEventPublisher publisher;
    @Mock
    private WebhookMetrics metrics;

    private RegisterWebhookEventUseCase useCase() {
        return new RegisterWebhookEventUseCase(publisher, metrics, Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    private static RegisterWebhookEventCommand command() {
        return new RegisterWebhookEventCommand("payment-gateway", "evt_1", "payment.confirmed", OCCURRED_AT,
                "ord_1042", "{\"id\":\"evt_1\"}");
    }

    @Test
    void publishesTheEventWithItsOrderingKey() {
        useCase().execute(command());

        ArgumentCaptor<ReceivedWebhookEvent> event = ArgumentCaptor.forClass(ReceivedWebhookEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue()).isEqualTo(new ReceivedWebhookEvent("payment-gateway", "evt_1",
                "payment.confirmed", OCCURRED_AT, TestData.NOW, "ord_1042", "{\"id\":\"evt_1\"}"));
        verify(metrics).eventReceived("payment.confirmed", "accepted");
    }

    @Test
    void unavailablePipelineIsReportedAndPropagated() {
        doThrow(new EventPublishingException("broker down", null)).when(publisher).publish(any());

        assertThatThrownBy(() -> useCase().execute(command())).isInstanceOf(EventPublishingException.class);

        verify(metrics).eventReceived("payment.confirmed", "unavailable");
        verify(metrics, never()).eventReceived("payment.confirmed", "accepted");
    }
}
