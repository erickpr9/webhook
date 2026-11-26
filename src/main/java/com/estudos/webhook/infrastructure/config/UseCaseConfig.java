package com.estudos.webhook.infrastructure.config;

import com.estudos.webhook.application.handler.ConfirmPaymentHandler;
import com.estudos.webhook.application.handler.PaymentEventHandler;
import com.estudos.webhook.application.handler.PaymentEventHandlerRegistry;
import com.estudos.webhook.application.handler.RefundPaymentHandler;
import com.estudos.webhook.application.handler.RefusePaymentHandler;
import com.estudos.webhook.application.port.NotificationOutbox;
import com.estudos.webhook.application.port.NotificationPublisher;
import com.estudos.webhook.application.port.NotificationSender;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.application.port.TransactionRunner;
import com.estudos.webhook.application.port.WebhookEventPublisher;
import com.estudos.webhook.application.port.WebhookEventRepository;
import com.estudos.webhook.application.port.WebhookMetrics;
import com.estudos.webhook.application.usecase.CreateOrderUseCase;
import com.estudos.webhook.application.usecase.DeliverNotificationUseCase;
import com.estudos.webhook.application.usecase.GetOrderUseCase;
import com.estudos.webhook.application.usecase.GetWebhookEventUseCase;
import com.estudos.webhook.application.usecase.ListNotificationsUseCase;
import com.estudos.webhook.application.usecase.ListWebhookEventsUseCase;
import com.estudos.webhook.application.usecase.ProcessWebhookEventUseCase;
import com.estudos.webhook.application.usecase.RelayPendingNotificationsUseCase;
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

    // ── Webhooks ────────────────────────────────────────────────────────────

    @Bean
    RegisterWebhookEventUseCase registerWebhookEventUseCase(WebhookEventPublisher publisher,
                                                            WebhookMetrics metrics, Clock clock) {
        return new RegisterWebhookEventUseCase(publisher, metrics, clock);
    }

    @Bean
    ProcessWebhookEventUseCase processWebhookEventUseCase(WebhookEventRepository repository,
                                                          PaymentEventPayloadReader payloadReader,
                                                          PaymentEventHandlerRegistry handlers,
                                                          TransactionRunner transactions,
                                                          KafkaPipelineProperties properties,
                                                          WebhookMetrics metrics, Clock clock) {
        return new ProcessWebhookEventUseCase(repository, payloadReader, handlers, transactions,
                properties.events().retry().toPolicy(), metrics, clock);
    }

    @Bean
    ReprocessWebhookEventUseCase reprocessWebhookEventUseCase(WebhookEventRepository repository,
                                                              WebhookEventPublisher publisher,
                                                              PaymentEventPayloadReader payloadReader, Clock clock) {
        return new ReprocessWebhookEventUseCase(repository, publisher, payloadReader, clock);
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
    RelayPendingNotificationsUseCase relayPendingNotificationsUseCase(NotificationOutbox outbox,
                                                                      NotificationPublisher publisher,
                                                                      TransactionRunner transactions,
                                                                      RelayProperties properties, Clock clock) {
        return new RelayPendingNotificationsUseCase(outbox, publisher, transactions, properties.batchSize(), clock);
    }

    @Bean
    DeliverNotificationUseCase deliverNotificationUseCase(NotificationOutbox outbox, NotificationSender sender,
                                                          KafkaPipelineProperties properties,
                                                          WebhookMetrics metrics, Clock clock) {
        return new DeliverNotificationUseCase(outbox, sender, properties.notifications().retry().toPolicy(),
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
