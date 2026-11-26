package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.ConsumeOutcome;
import com.estudos.webhook.application.dto.HandlingOutcome;
import com.estudos.webhook.application.dto.ReceivedWebhookEvent;
import com.estudos.webhook.application.exception.ConcurrentUpdateException;
import com.estudos.webhook.application.exception.MalformedPayloadException;
import com.estudos.webhook.application.handler.PaymentEventHandler;
import com.estudos.webhook.application.handler.PaymentEventHandlerRegistry;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.domain.exception.PaymentDataMismatchException;
import com.estudos.webhook.domain.exception.PaymentNotFoundException;
import com.estudos.webhook.domain.model.PaymentEventType;
import com.estudos.webhook.domain.model.WebhookEvent;
import com.estudos.webhook.domain.model.WebhookEventStatus;
import com.estudos.webhook.domain.service.RetryPolicy;
import com.estudos.webhook.support.DirectTransactionRunner;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessWebhookEventUseCaseTest {

    private static final String CONFIRMED = "payment.confirmed";

    @Mock
    private WebhookEventRepository repository;
    @Mock
    private PaymentEventPayloadReader payloadReader;
    @Mock
    private WebhookMetrics metrics;
    @Mock
    private PaymentEventHandler confirmHandler;

    private ProcessWebhookEventUseCase useCase;

    @BeforeEach
    void setUp() {
        List<PaymentEventHandler> handlers = Arrays.stream(PaymentEventType.values())
                .map(this::handlerFor)
                .toList();
        var retryPolicy = new RetryPolicy(3, List.of(Duration.ofSeconds(10), Duration.ofSeconds(30)));
        useCase = new ProcessWebhookEventUseCase(repository, payloadReader, new PaymentEventHandlerRegistry(handlers),
                new DirectTransactionRunner(), retryPolicy, metrics, Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    private PaymentEventHandler handlerFor(PaymentEventType type) {
        if (type == PaymentEventType.PAYMENT_CONFIRMED) {
            lenient().when(confirmHandler.supportedType()).thenReturn(type);
            return confirmHandler;
        }
        PaymentEventHandler other = org.mockito.Mockito.mock(PaymentEventHandler.class);
        when(other.supportedType()).thenReturn(type);
        return other;
    }

    /** O registro do evento, como o banco devolve depois do insert, no estado e tentativas dados. */
    private WebhookEvent storedAs(ReceivedWebhookEvent message, WebhookEventStatus status, int attempts) {
        WebhookEvent stored = TestData.stored(message, status, attempts);
        when(repository.lockForProcessing(message.provider(), message.providerEventId()))
                .thenReturn(Optional.of(stored));
        return stored;
    }

    @Test
    void firstDeliveryIsRecordedAndProcessedInTheSameTransaction() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RECEIVED, 0);
        when(payloadReader.read(stored.payload())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(TestData.eventData(), stored.occurredAt()))
                .thenReturn(HandlingOutcome.applied("Payment CONFIRMED, order PAID"));

        ConsumeOutcome outcome = useCase.execute(message);

        assertThat(outcome).isEqualTo(ConsumeOutcome.COMPLETED);
        ArgumentCaptor<WebhookEvent> inserted = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(repository).insertIfAbsent(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(inserted.getValue().providerEventId()).isEqualTo(message.providerEventId());
        assertThat(inserted.getValue().payload()).isEqualTo(message.payload());
        verify(repository).complete(stored.id(), 1, WebhookEventStatus.PROCESSED, TestData.NOW,
                "Payment CONFIRMED, order PAID");
        verify(metrics).eventCompleted(CONFIRMED, WebhookEventStatus.PROCESSED);
        verify(metrics).processingTime(eq(CONFIRMED), any(Duration.class));
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventStatus.class, names = {"PROCESSED", "IGNORED", "DEAD"})
    void redeliveryOfAFinishedEventHasNoEffect(WebhookEventStatus finalStatus) {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        storedAs(message, finalStatus, 1);

        ConsumeOutcome outcome = useCase.execute(message);

        assertThat(outcome).isEqualTo(ConsumeOutcome.ALREADY_HANDLED);
        verifyNoInteractions(payloadReader);
        verify(confirmHandler, never()).handle(any(), any());
        verify(repository, never()).complete(any(), anyInt(), any(), any(), any());
        verify(metrics, never()).eventCompleted(any(), any());
    }

    @Test
    void retryCopyContinuesTheAttemptCount() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RETRY_SCHEDULED, 1);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenReturn(HandlingOutcome.applied("ok"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.COMPLETED);

        verify(repository).complete(stored.id(), 2, WebhookEventStatus.PROCESSED, TestData.NOW, "ok");
    }

    @Test
    void unsupportedTypeIsIgnoredWithoutReadingThePayload() {
        ReceivedWebhookEvent message = TestData.message("customer.created");
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RECEIVED, 0);

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.COMPLETED);

        verifyNoInteractions(payloadReader);
        verify(repository).complete(stored.id(), 1, WebhookEventStatus.IGNORED, TestData.NOW, "Unsupported event type");
        verify(metrics).eventCompleted("customer.created", WebhookEventStatus.IGNORED);
    }

    @Test
    void transientFailureIsRecordedAndAsksForRetryWithBackoff() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RETRY_SCHEDULED, 1);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new PaymentNotFoundException(TestData.PAYMENT_ID));

        ConsumeOutcome outcome = useCase.execute(message);

        assertThat(outcome).isEqualTo(ConsumeOutcome.RETRY);
        verify(repository).recordRetry(eq(stored.id()), eq(2), eq(TestData.NOW.plusSeconds(30)),
                startsWith("PaymentNotFoundException"));
        verify(metrics).eventRetryScheduled(CONFIRMED);
    }

    @Test
    void concurrentUpdateIsRetried() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RECEIVED, 0);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new ConcurrentUpdateException("Payment", "pay"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.RETRY);

        verify(repository).recordRetry(eq(stored.id()), eq(1), eq(TestData.NOW.plusSeconds(10)), anyString());
    }

    @Test
    void lastAttemptMovesEventToDead() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RETRY_SCHEDULED, 2);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.DEAD);

        verify(repository).markDead(stored.id(), 3, "IllegalStateException: database down");
        verify(metrics).eventDead(CONFIRMED);
    }

    @Test
    void malformedPayloadIsPermanentAndGoesStraightToDead() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RECEIVED, 0);
        when(payloadReader.read(any())).thenThrow(new MalformedPayloadException("missing data"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.DEAD);

        verify(repository).markDead(eq(stored.id()), eq(1), startsWith("MalformedPayloadException"));
        verify(repository, never()).recordRetry(any(), anyInt(), any(), anyString());
    }

    @Test
    void businessRuleViolationIsPermanent() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RECEIVED, 0);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new PaymentDataMismatchException(TestData.PAYMENT_ID));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.DEAD);

        verify(repository).markDead(eq(stored.id()), eq(1), startsWith("PaymentDataMismatchException"));
    }

    @Test
    void failureWhileRecordingTheFailureStillRoutesTheMessage() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        when(repository.lockForProcessing(any(), any())).thenThrow(new IllegalStateException("database down"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.RETRY);
        verify(repository, never()).recordRetry(any(), anyInt(), any(), anyString());
    }

    @Test
    void permanentFailureWhileRecordingStillGoesToDeadLetter() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = TestData.stored(message, WebhookEventStatus.RECEIVED, 0);
        when(repository.lockForProcessing(any(), any()))
                .thenReturn(Optional.of(stored))
                .thenThrow(new IllegalStateException("database down"));
        when(payloadReader.read(any())).thenThrow(new MalformedPayloadException("bad"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.DEAD);
    }

    @Test
    void anotherCopyFinishingFirstMakesTheFailureIrrelevant() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        when(repository.lockForProcessing(any(), any()))
                .thenReturn(Optional.of(TestData.stored(message, WebhookEventStatus.RECEIVED, 0)))
                .thenReturn(Optional.of(TestData.stored(message, WebhookEventStatus.PROCESSED, 1)));
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new ConcurrentUpdateException("Payment", "pay"));

        assertThat(useCase.execute(message)).isEqualTo(ConsumeOutcome.ALREADY_HANDLED);
        verify(repository, never()).recordRetry(any(), anyInt(), any(), anyString());
    }

    @Test
    void deadLetteredEventIsMarkedDeadIfStillPending() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        WebhookEvent stored = storedAs(message, WebhookEventStatus.RETRY_SCHEDULED, 2);

        useCase.deadLettered(message, "exhausted");

        verify(repository).markDead(stored.id(), 2, "exhausted");
        verify(metrics).eventDead(CONFIRMED);
    }

    @Test
    void deadLetteredEventAlreadyFinalIsLeftAlone() {
        ReceivedWebhookEvent message = TestData.message(CONFIRMED);
        storedAs(message, WebhookEventStatus.DEAD, 3);

        useCase.deadLettered(message, "exhausted");

        verify(repository, never()).markDead(any(), anyInt(), anyString());
    }

    @Test
    void clearsLoggingContextAfterProcessing() {
        ReceivedWebhookEvent message = TestData.message("customer.created");
        storedAs(message, WebhookEventStatus.RECEIVED, 0);

        useCase.execute(message);

        assertThat(MDC.get(ProcessWebhookEventUseCase.MDC_EVENT_ID)).isNull();
        assertThat(MDC.get(ProcessWebhookEventUseCase.MDC_EVENT_TYPE)).isNull();
        assertThat(MDC.get(ProcessWebhookEventUseCase.MDC_ATTEMPT)).isNull();
    }
}
