package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.WorkerSettings;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.domain.service.RetryPolicy;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessPendingWebhookEventsUseCaseTest {

    private static final Duration LEASE = Duration.ofMinutes(2);

    @Mock
    private WebhookEventRepository repository;
    @Mock
    private ProcessWebhookEventUseCase processor;

    private ProcessPendingWebhookEventsUseCase useCase() {
        var settings = new WorkerSettings(20, LEASE, new RetryPolicy(6, List.of(Duration.ofSeconds(10))));
        return new ProcessPendingWebhookEventsUseCase(repository, processor, settings,
                Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    @Test
    void deadLettersAbandonedEventsThenProcessesClaimedInArrivalOrder() {
        WebhookEvent older = event(TestData.NOW.minusSeconds(60));
        WebhookEvent newer = event(TestData.NOW.minusSeconds(10));
        when(repository.deadLetterAbandoned(TestData.NOW, 6)).thenReturn(1);
        when(repository.claimDue(TestData.NOW, TestData.NOW.plus(LEASE), 20)).thenReturn(List.of(newer, older));

        int claimed = useCase().execute();

        assertThat(claimed).isEqualTo(2);
        InOrder order = inOrder(repository, processor);
        order.verify(repository).deadLetterAbandoned(TestData.NOW, 6);
        order.verify(repository).claimDue(TestData.NOW, TestData.NOW.plus(LEASE), 20);
        order.verify(processor).execute(older);
        order.verify(processor).execute(newer);
    }

    @Test
    void doesNothingWhenNoEventIsDue() {
        when(repository.claimDue(TestData.NOW, TestData.NOW.plus(LEASE), 20)).thenReturn(List.of());

        assertThat(useCase().execute()).isZero();
        verifyNoInteractions(processor);
    }

    private static WebhookEvent event(java.time.Instant receivedAt) {
        return new WebhookEvent(UUID.randomUUID(), "gateway", "evt_" + receivedAt, "payment.confirmed", "{}",
                WebhookEventStatus.PROCESSING, 1, receivedAt, receivedAt, receivedAt, null, null, null);
    }
}
