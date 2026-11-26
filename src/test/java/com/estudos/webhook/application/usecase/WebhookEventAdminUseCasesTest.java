package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.WebhookEventView;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.exception.MalformedPayloadException;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.estudos.webhook.application.port.WebhookEventPublisher;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.exception.WebhookEventNotFoundException;
import com.estudos.webhook.domain.exception.WebhookEventNotReprocessableException;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookEventAdminUseCasesTest {

    @Mock
    private WebhookEventRepository repository;
    @Mock
    private WebhookEventPublisher publisher;
    @Mock
    private PaymentEventPayloadReader payloadReader;

    private final Clock clock = Clock.fixed(TestData.NOW, ZoneOffset.UTC);

    private ReprocessWebhookEventUseCase reprocess() {
        return new ReprocessWebhookEventUseCase(repository, publisher, payloadReader, clock);
    }

    private static WebhookEvent withStatus(UUID id, WebhookEventStatus status) {
        return new WebhookEvent(id, "gateway", "evt_1", "payment.confirmed", "{}", status, 6, TestData.NOW,
                TestData.NOW, TestData.NOW, null, null, "boom");
    }

    @Test
    void reprocessRequeuesDeadEvent() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(withStatus(id, WebhookEventStatus.DEAD)),
                Optional.of(withStatus(id, WebhookEventStatus.RETRY_SCHEDULED)));
        when(repository.requeue(id, TestData.NOW)).thenReturn(true);

        when(payloadReader.read("{}")).thenReturn(TestData.eventData());

        WebhookEventView view = reprocess().execute(id);

        assertThat(view.status()).isEqualTo("RETRY_SCHEDULED");
        assertThat(view.lastError()).isEqualTo("boom");
        verify(publisher).publish(new ReceivedWebhookEvent("gateway", "evt_1", "payment.confirmed", TestData.NOW,
                TestData.NOW, TestData.ORDER_ID, "{}"));
    }

    @Test
    void reprocessOfUnreadablePayloadUsesTheEventAsOrderingKey() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(withStatus(id, WebhookEventStatus.DEAD)),
                Optional.of(withStatus(id, WebhookEventStatus.RETRY_SCHEDULED)));
        when(repository.requeue(id, TestData.NOW)).thenReturn(true);
        when(payloadReader.read("{}")).thenThrow(new MalformedPayloadException("bad"));

        reprocess().execute(id);

        ArgumentCaptor<ReceivedWebhookEvent> published = ArgumentCaptor.forClass(ReceivedWebhookEvent.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue().partitionKey()).isEqualTo("evt_1");
    }

    @Test
    void reprocessRejectsEventThatIsNotDead() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(withStatus(id, WebhookEventStatus.PROCESSED)));

        assertThatThrownBy(() -> reprocess().execute(id))
                .isInstanceOf(WebhookEventNotReprocessableException.class)
                .hasMessageContaining("PROCESSED");
        verify(repository, never()).requeue(any(), any());
        verifyNoInteractions(publisher);
    }

    @Test
    void reprocessDetectsConcurrentRequeue() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(withStatus(id, WebhookEventStatus.DEAD)),
                Optional.of(withStatus(id, WebhookEventStatus.RETRY_SCHEDULED)));
        when(repository.requeue(id, TestData.NOW)).thenReturn(false);

        assertThatThrownBy(() -> reprocess().execute(id))
                .isInstanceOf(WebhookEventNotReprocessableException.class)
                .hasMessageContaining("RETRY_SCHEDULED");
    }

    @Test
    void reprocessOfUnknownEventIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reprocess().execute(id))
                .isInstanceOf(WebhookEventNotFoundException.class);
    }

    @Test
    void getReturnsViewOrNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(withStatus(id, WebhookEventStatus.DEAD)));
        var useCase = new GetWebhookEventUseCase(repository);

        assertThat(useCase.execute(id).id()).isEqualTo(id);
        assertThatThrownBy(() -> useCase.execute(UUID.randomUUID())).isInstanceOf(WebhookEventNotFoundException.class);
    }

    @Test
    void listClampsLimitToProtectTheDatabase() {
        when(repository.findRecent(Optional.of(WebhookEventStatus.DEAD), ListWebhookEventsUseCase.MAX_LIMIT))
                .thenReturn(List.of(withStatus(UUID.randomUUID(), WebhookEventStatus.DEAD)));
        when(repository.findRecent(Optional.empty(), 1)).thenReturn(List.of());
        var useCase = new ListWebhookEventsUseCase(repository);

        assertThat(useCase.execute(Optional.of(WebhookEventStatus.DEAD), 10_000)).hasSize(1);
        assertThat(useCase.execute(Optional.empty(), 0)).isEmpty();
    }
}
