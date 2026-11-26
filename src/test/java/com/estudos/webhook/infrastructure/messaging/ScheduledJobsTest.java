package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.usecase.RelayPendingNotificationsUseCase;
import com.estudos.webhook.infrastructure.config.RelayProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O relay esvazia o outbox em lotes, e uma falha em um ciclo não pode derrubar o agendamento.
 */
class ScheduledJobsTest {

    private static final RelayProperties PROPERTIES = new RelayProperties(true, Duration.ofSeconds(1), 10);

    @Test
    void relayKeepsPublishingWhileBatchesAreFull() {
        var useCase = mock(RelayPendingNotificationsUseCase.class);
        when(useCase.execute()).thenReturn(10, 10, 3);

        new NotificationRelayJob(useCase, PROPERTIES).run();

        verify(useCase, times(3)).execute();
    }

    @Test
    void relayStopsAfterTheBatchLimitPerCycle() {
        var useCase = mock(RelayPendingNotificationsUseCase.class);
        when(useCase.execute()).thenReturn(10);

        new NotificationRelayJob(useCase, PROPERTIES).run();

        verify(useCase, times(NotificationRelayJob.MAX_BATCHES_PER_CYCLE)).execute();
    }

    @Test
    void relaySurvivesFailures() {
        var useCase = mock(RelayPendingNotificationsUseCase.class);
        when(useCase.execute()).thenThrow(new IllegalStateException("broker down")).thenReturn(0);
        var job = new NotificationRelayJob(useCase, PROPERTIES);

        assertThatCode(job::run).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();
        verify(useCase, times(2)).execute();
    }
}
