package com.estudos.webhook.infrastructure.config;

import com.estudos.webhook.application.handler.ConfirmPaymentHandler;
import com.estudos.webhook.application.handler.PaymentEventHandler;
import com.estudos.webhook.application.handler.PaymentEventHandlerRegistry;
import com.estudos.webhook.application.handler.RefundPaymentHandler;
import com.estudos.webhook.application.handler.RefusePaymentHandler;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.NotificationSender;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.application.port.TransactionRunner;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.application.usecase.CreateOrderUseCase;
import com.estudos.webhook.application.usecase.DispatchPendingNotificationsUseCase;
import com.estudos.webhook.application.usecase.GetOrderUseCase;
import com.estudos.webhook.application.usecase.GetWebhookEventUseCase;
import com.estudos.webhook.application.usecase.ListNotificationsUseCase;
import com.estudos.webhook.application.usecase.ListWebhookEventsUseCase;
import com.estudos.webhook.application.usecase.ProcessPendingWebhookEventsUseCase;
import com.estudos.webhook.application.usecase.ProcessWebhookEventUseCase;
import com.estudos.webhook.application.usecase.RegisterWebhookEventUseCase;
import com.estudos.webhook.application.usecase.ReprocessWebhookEventUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Monta os casos de uso. A camada de aplicação não conhece o Spring: toda a ligação entre
 * portas e adaptadores acontece aqui.
 */
@Configuration(proxyBeanMethods = false)
public class UseCaseConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    @Bean
    ConfirmPaymentHandler confirmPaymentHandler(PaymentRepository payments, OrderRepository orders,
                                                NotificationOutbox outbox, Clock clock) {
        return new ConfirmPaymentHandler(payments, orders, outbox, clock);
    }

    @Bean
    RefusePaymentHandler refusePaymentHandler(PaymentRepository payments, OrderRepository orders,
                                              NotificationOutbox outbox, Clock clock) {
        return new RefusePaymentHandler(payments, orders, outbox, clock);
    }

    @Bean
    RefundPaymentHandler refundPaymentHandler(PaymentRepository payments, OrderRepository orders,
                                              NotificationOutbox outbox, Clock clock) {
        return new RefundPaymentHandler(payments, orders, outbox, clock);
    }

    @Bean
    PaymentEventHandlerRegistry paymentEventHandlerRegistry(List<PaymentEventHandler> handlers) {
        return new PaymentEventHandlerRegistry(handlers);
    }

    // ── Inbox ───────────────────────────────────────────────────────────────

    @Bean
    RegisterWebhookEventUseCase registerWebhookEventUseCase(WebhookEventRepository repository,
                                                            WebhookMetrics metrics, Clock clock) {
        return new RegisterWebhookEventUseCase(repository, metrics, clock);
    }

    @Bean
    ProcessWebhookEventUseCase processWebhookEventUseCase(WebhookEventRepository repository,
                                                          PaymentEventPayloadReader payloadReader,
                                                          PaymentEventHandlerRegistry handlers,
                                                          TransactionRunner transactions,
                                                          WorkerProperties properties,
                                                          WebhookMetrics metrics, Clock clock) {
        return new ProcessWebhookEventUseCase(repository, payloadReader, handlers, transactions,
                properties.events().toSettings().retryPolicy(), metrics, clock);
    }

    @Bean
    ProcessPendingWebhookEventsUseCase processPendingWebhookEventsUseCase(WebhookEventRepository repository,
                                                                          ProcessWebhookEventUseCase processor,
                                                                          WorkerProperties properties, Clock clock) {
        return new ProcessPendingWebhookEventsUseCase(repository, processor, properties.events().toSettings(), clock);
    }

    @Bean
    ReprocessWebhookEventUseCase reprocessWebhookEventUseCase(WebhookEventRepository repository, Clock clock) {
        return new ReprocessWebhookEventUseCase(repository, clock);
    }

    @Bean
    ListWebhookEventsUseCase listWebhookEventsUseCase(WebhookEventRepository repository) {
        return new ListWebhookEventsUseCase(repository);
    }

    @Bean
    GetWebhookEventUseCase getWebhookEventUseCase(WebhookEventRepository repository) {
        return new GetWebhookEventUseCase(repository);
    }

    // ── Notificações ────────────────────────────────────────────────────────

    @Bean
    DispatchPendingNotificationsUseCase dispatchPendingNotificationsUseCase(NotificationOutbox outbox,
                                                                            NotificationSender sender,
                                                                            WorkerProperties properties,
                                                                            WebhookMetrics metrics, Clock clock) {
        return new DispatchPendingNotificationsUseCase(outbox, sender, properties.notifications().toSettings(),
                metrics, clock);
    }

    @Bean
    ListNotificationsUseCase listNotificationsUseCase(NotificationOutbox outbox) {
        return new ListNotificationsUseCase(outbox);
    }

    // ── Pedidos ─────────────────────────────────────────────────────────────

    @Bean
    CreateOrderUseCase createOrderUseCase(OrderRepository orders, PaymentRepository payments,
                                          TransactionRunner transactions, Clock clock) {
        return new CreateOrderUseCase(orders, payments, transactions, clock);
    }

    @Bean
    GetOrderUseCase getOrderUseCase(OrderRepository orders, PaymentRepository payments) {
        return new GetOrderUseCase(orders, payments);
    }
}
