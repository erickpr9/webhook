package com.estudos.webhook.application.usecase;

import com.estudos.webhook.application.dto.HandlingOutcome;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

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

    @Test
    void processesEventAndCompletesItInTheSameTransaction() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 1);
        when(payloadReader.read(event.payload())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(TestData.eventData(), event.occurredAt()))
                .thenReturn(HandlingOutcome.applied("Payment CONFIRMED, order PAID"));
        when(repository.complete(event.id(), 1, WebhookEventStatus.PROCESSED, TestData.NOW,
                "Payment CONFIRMED, order PAID")).thenReturn(true);

        useCase.execute(event);

        verify(metrics).eventCompleted(CONFIRMED, WebhookEventStatus.PROCESSED);
        verify(metrics).processingTime(eq(CONFIRMED), any(Duration.class));
        verify(repository, never()).scheduleRetry(any(), anyInt(), any(), anyString());
        verify(repository, never()).markDead(any(), anyInt(), anyString());
    }

    @Test
    void unsupportedTypeIsIgnoredWithoutReadingThePayload() {
        WebhookEvent event = TestData.processingEvent("customer.created", 1);
        when(repository.complete(event.id(), 1, WebhookEventStatus.IGNORED, TestData.NOW, "Unsupported event type"))
                .thenReturn(true);

        useCase.execute(event);

        verifyNoInteractions(payloadReader);
        verify(metrics).eventCompleted("customer.created", WebhookEventStatus.IGNORED);
    }

    @Test
    void lostLeaseRollsBackWithoutRecordingFailure() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 1);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenReturn(HandlingOutcome.applied("ok"));
        when(repository.complete(any(), anyInt(), any(), any(), any())).thenReturn(false);

        useCase.execute(event);

        verify(repository, never()).scheduleRetry(any(), anyInt(), any(), anyString());
        verify(repository, never()).markDead(any(), anyInt(), anyString());
        verify(metrics, never()).eventCompleted(any(), any());
    }

    @Test
    void transientFailureSchedulesRetryWithBackoff() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 2);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new PaymentNotFoundException(TestData.PAYMENT_ID));
        when(repository.scheduleRetry(eq(event.id()), eq(2), any(), anyString())).thenReturn(true);

        useCase.execute(event);

        verify(repository).scheduleRetry(eq(event.id()), eq(2), eq(TestData.NOW.plusSeconds(30)),
                startsWith("PaymentNotFoundException"));
        verify(metrics).eventRetryScheduled(CONFIRMED);
    }

    @Test
    void concurrentUpdateIsRetried() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 1);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new ConcurrentUpdateException("Payment", "pay"));

        useCase.execute(event);

        verify(repository).scheduleRetry(eq(event.id()), eq(1), eq(TestData.NOW.plusSeconds(10)), anyString());
    }

    @Test
    void exhaustedAttemptsMoveEventToDead() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 3);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new IllegalStateException("database down"));

        useCase.execute(event);

        verify(repository).markDead(event.id(), 3, "IllegalStateException: database down");
        verify(metrics).eventDead(CONFIRMED);
    }

    @Test
    void malformedPayloadIsPermanentAndGoesStraightToDead() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 1);
        when(payloadReader.read(any())).thenThrow(new MalformedPayloadException("missing data"));

        useCase.execute(event);

        verify(repository).markDead(eq(event.id()), eq(1), startsWith("MalformedPayloadException"));
        verify(repository, never()).scheduleRetry(any(), anyInt(), any(), anyString());
    }

    @Test
    void businessRuleViolationIsPermanent() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 1);
        when(payloadReader.read(any())).thenReturn(TestData.eventData());
        when(confirmHandler.handle(any(), any())).thenThrow(new PaymentDataMismatchException(TestData.PAYMENT_ID));

        useCase.execute(event);

        verify(repository).markDead(eq(event.id()), eq(1), startsWith("PaymentDataMismatchException"));
    }

    @Test
    void failureRecordingThatLosesTheLeaseIsTolerated() {
        WebhookEvent event = TestData.processingEvent(CONFIRMED, 1);
        when(payloadReader.read(any())).thenThrow(new MalformedPayloadException("bad"));
        when(repository.markDead(any(), anyInt(), anyString())).thenReturn(false);

        useCase.execute(event);

        verify(metrics).eventDead(CONFIRMED);
    }

    @Test
    void clearsLoggingContextAfterProcessing() {
        WebhookEvent event = TestData.processingEvent("customer.created", 1);
        when(repository.complete(any(), anyInt(), any(), any(), any())).thenReturn(true);

        useCase.execute(event);

        assertThat(MDC.get(ProcessWebhookEventUseCase.MDC_EVENT_ID)).isNull();
        assertThat(MDC.get(ProcessWebhookEventUseCase.MDC_EVENT_TYPE)).isNull();
        assertThat(MDC.get(ProcessWebhookEventUseCase.MDC_ATTEMPT)).isNull();
    }
}
