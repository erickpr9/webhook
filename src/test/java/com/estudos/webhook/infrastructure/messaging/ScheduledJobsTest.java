package com.estudos.webhook.infrastructure.messaging;

import com.estudos.webhook.application.usecase.DispatchPendingNotificationsUseCase;
import com.estudos.webhook.application.usecase.ProcessPendingWebhookEventsUseCase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uma falha em um ciclo não pode derrubar o agendamento.
 */
class ScheduledJobsTest {

    @Test
    void eventJobRunsUseCaseAndSurvivesFailures() {
        var useCase = mock(ProcessPendingWebhookEventsUseCase.class);
        when(useCase.execute()).thenReturn(3).thenThrow(new IllegalStateException("db down"));
        var job = new ProcessPendingEventsJob(useCase);

        assertThatCode(job::run).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();
        verify(useCase, org.mockito.Mockito.times(2)).execute();
    }

    @Test
    void notificationJobRunsUseCaseAndSurvivesFailures() {
        var useCase = mock(DispatchPendingNotificationsUseCase.class);
        when(useCase.execute()).thenReturn(1).thenThrow(new IllegalStateException("db down"));
        var job = new DispatchNotificationsJob(useCase);

        assertThatCode(job::run).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();
        verify(useCase, org.mockito.Mockito.times(2)).execute();
    }
}
