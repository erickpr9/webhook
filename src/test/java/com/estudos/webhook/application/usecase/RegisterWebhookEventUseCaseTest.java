package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.RegisterWebhookEventCommand;
import com.estudos.webhook.application.dto.RegistrationResult;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterWebhookEventUseCaseTest {

    private static final Instant OCCURRED_AT = TestData.NOW.minusSeconds(2);

    @Mock
    private WebhookEventRepository repository;
    @Mock
    private WebhookMetrics metrics;

    private RegisterWebhookEventUseCase useCase() {
        return new RegisterWebhookEventUseCase(repository, metrics, Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    private static RegisterWebhookEventCommand command() {
        return new RegisterWebhookEventCommand("payment-gateway", "evt_1", "payment.confirmed", OCCURRED_AT,
                "{\"id\":\"evt_1\"}");
    }

    @Test
    void registersNewEventInTheInbox() {
        when(repository.insertIfAbsent(any())).thenReturn(true);

        RegistrationResult result = useCase().execute(command());

        assertThat(result).isEqualTo(new RegistrationResult("evt_1", false));
        ArgumentCaptor<WebhookEvent> event = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(repository).insertIfAbsent(event.capture());
        assertThat(event.getValue().status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(event.getValue().provider()).isEqualTo("payment-gateway");
        assertThat(event.getValue().providerEventId()).isEqualTo("evt_1");
        assertThat(event.getValue().occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.getValue().receivedAt()).isEqualTo(TestData.NOW);
        assertThat(event.getValue().payload()).isEqualTo("{\"id\":\"evt_1\"}");
        verify(metrics).eventReceived("payment.confirmed", "accepted");
    }

    @Test
    void reportsDuplicateWhenEventAlreadyExists() {
        when(repository.insertIfAbsent(any())).thenReturn(false);

        RegistrationResult result = useCase().execute(command());

        assertThat(result.duplicate()).isTrue();
        verify(metrics).eventReceived("payment.confirmed", "duplicate");
    }
}
