package com.estudos.webhook;

import com.estudos.webhook.api.controller.PaymentWebhookController;
import com.estudos.webhook.shared.security.HmacSigner;
import com.estudos.webhook.application.dto.CreateOrderCommand;
import com.estudos.webhook.application.dto.CreatedOrder;
import com.estudos.webhook.application.port.OrderRepository;
import com.estudos.webhook.application.port.PaymentRepository;
import com.estudos.webhook.application.usecase.CreateOrderUseCase;
import com.estudos.webhook.domain.model.Money;
import com.estudos.webhook.domain.model.Order;
import com.estudos.webhook.domain.model.Payment;
import com.estudos.webhook.domain.model.PaymentMethod;
import com.estudos.webhook.support.IntegrationTest;
import com.estudos.webhook.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Esteira completa, de ponta a ponta, com Kafka e Postgres reais: HTTP assinado → tópico de
 * webhooks → consumer → domínio + outbox → relay → tópico de notificações → consumer → envio.
 * Consumers e relay ligados, com atrasos de retentativa de milissegundos.
 *
 * <p>O contexto é descartado ao final ({@link DirtiesContext}): com os consumers ligados, ele
 * continuaria lendo os tópicos e o banco compartilhados com os outros testes.
 */
@AutoConfigureObservability(tracing = false)
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webhook.kafka.listeners-enabled=true",
        "webhook.kafka.events.retry.attempts=3",
        "webhook.kafka.events.retry.delay-ms=200",
        "webhook.kafka.events.retry.multiplier=2",
        "webhook.kafka.events.retry.max-delay-ms=1000",
        "webhook.kafka.notifications.retry.attempts=3",
        "webhook.kafka.notifications.retry.delay-ms=200",
        "webhook.kafka.notifications.retry.multiplier=2",
        "webhook.kafka.notifications.retry.max-delay-ms=1000",
        "webhook.relay.enabled=true",
        "webhook.relay.interval=100ms",
        "webhook.admin.enabled=true",
        "webhook.dev.simulation-enabled=true"
})
class PaymentWebhookFlowIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String WEBHOOK_URL = "/v1/webhooks/payments";

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private HmacSigner signer;
    @Autowired
    private CreateOrderUseCase createOrder;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private PaymentRepository payments;
    @Autowired
    private ObjectMapper objectMapper;

    // ── Helpers ─────────────────────────────────────────────────────────────

    private CreatedOrder newOrder() {
        return createOrder.execute(new CreateOrderCommand(15990, "BRL", "PIX"));
    }

    private ResponseEntity<String> sendEvent(String eventId, String type, Instant createdAt, CreatedOrder order,
                                             long amount) {
        String body = TestData.payload(eventId, type, createdAt, order.paymentId(), order.orderId(), amount);
        return sendSigned(eventId, body.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<String> sendEvent(String eventId, String type, CreatedOrder order) {
        return sendEvent(eventId, type, Instant.now(), order, 15990);
    }

    private ResponseEntity<String> sendSigned(String eventId, byte[] body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(PaymentWebhookController.EVENT_ID_HEADER, eventId);
        headers.set(PaymentWebhookController.TIMESTAMP_HEADER, timestamp);
        headers.set(PaymentWebhookController.SIGNATURE_HEADER, signer.signatureHeader(timestamp, body));
        return http.exchange(WEBHOOK_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode getJson(String url) throws Exception {
        ResponseEntity<String> response = http.getForEntity(url, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private String orderStatus(String orderId) throws Exception {
        return getJson("/v1/orders/" + orderId).path("status").asText();
    }

    private String paymentStatus(String orderId) throws Exception {
        return getJson("/v1/orders/" + orderId).path("payments").path(0).path("status").asText();
    }

    private String eventStatus(String providerEventId) {
        return jdbcTemplate.queryForObject("SELECT status FROM webhook_events WHERE provider_event_id = ?",
                String.class, providerEventId);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    // ── Cenários ────────────────────────────────────────────────────────────

    @Test
    void confirmedPaymentFlowsFromWebhookToNotification() throws Exception {
        CreatedOrder order = newOrder();

        ResponseEntity<String> response = sendEvent("evt_happy", "payment.confirmed", order);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(eventStatus("evt_happy")).isEqualTo("PROCESSED");
            assertThat(orderStatus(order.orderId())).isEqualTo("PAID");
            assertThat(paymentStatus(order.orderId())).isEqualTo("CONFIRMED");
            assertThat(count("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ? AND status = 'SENT'",
                    order.paymentId())).isEqualTo(1);
        });
    }

    @Test
    void confirmationThenRefundEndsRefunded() throws Exception {
        CreatedOrder order = newOrder();
        Instant confirmedAt = Instant.now().minusSeconds(10);

        sendEvent("evt_c", "payment.confirmed", confirmedAt, order, 15990);
        await().atMost(TIMEOUT).until(() -> "PAID".equals(orderStatus(order.orderId())));
        sendEvent("evt_r", "payment.refunded", confirmedAt.plusSeconds(5), order, 15990);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(orderStatus(order.orderId())).isEqualTo("REFUNDED");
            assertThat(paymentStatus(order.orderId())).isEqualTo("REFUNDED");
        });
    }

    @Test
    void redeliveryIsAcceptedButProcessedOnce() throws Exception {
        CreatedOrder order = newOrder();

        assertThat(sendEvent("evt_dup", "payment.confirmed", order).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // A recepção não consulta o banco: a reentrega também é aceita, e o consumo descarta a cópia.
        assertThat(sendEvent("evt_dup", "payment.confirmed", order).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        await().atMost(TIMEOUT).until(() -> "PROCESSED".equals(eventStatus("evt_dup")));
        // Tempo para a segunda mensagem ser consumida (mesma chave, mesma partição, logo depois).
        await().pollDelay(Duration.ofSeconds(1)).atMost(TIMEOUT).until(() -> true);
        assertThat(count("SELECT count(*) FROM webhook_events WHERE provider_event_id = 'evt_dup'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?", order.paymentId()))
                .isEqualTo(1);
    }

    @Test
    void simultaneousRedeliveriesHaveASingleEffect() throws Exception {
        CreatedOrder order = newOrder();
        byte[] body = TestData.payload("evt_burst", "payment.confirmed", Instant.now(), order.paymentId(),
                order.orderId(), 15990).getBytes(StandardCharsets.UTF_8);
        int requests = 10;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<HttpStatus> statuses = new ArrayList<>();
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return sendSigned("evt_burst", body);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<String>> future : futures) {
                statuses.add(HttpStatus.valueOf(future.get().getStatusCode().value()));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsOnly(HttpStatus.ACCEPTED);
        await().atMost(TIMEOUT).until(() -> "PAID".equals(orderStatus(order.orderId())));
        await().pollDelay(Duration.ofSeconds(1)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(count("SELECT count(*) FROM webhook_events WHERE provider_event_id = 'evt_burst'")).isEqualTo(1);
            assertThat(count("SELECT attempts FROM webhook_events WHERE provider_event_id = 'evt_burst'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM notification_outbox WHERE aggregate_id = ?", order.paymentId()))
                    .isEqualTo(1);
        });
    }

    @Test
    void staleRefusalAfterConfirmationIsIgnored() throws Exception {
        CreatedOrder order = newOrder();
        Instant confirmedAt = Instant.now().minusSeconds(5);

        sendEvent("evt_conf", "payment.confirmed", confirmedAt, order, 15990);
        await().atMost(TIMEOUT).until(() -> "PROCESSED".equals(eventStatus("evt_conf")));
        sendEvent("evt_late_refusal", "payment.refused", confirmedAt.minusSeconds(60), order, 15990);

        await().atMost(TIMEOUT).until(() -> "PROCESSED".equals(eventStatus("evt_late_refusal")));
        assertThat(paymentStatus(order.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT processing_note FROM webhook_events WHERE provider_event_id = 'evt_late_refusal'",
                String.class)).contains("STALE_EVENT");
    }

    @Test
    void eventForPaymentNotYetCreatedIsRetriedUntilItExists() throws Exception {
        var order = new CreatedOrder("ord_late", "pay_late");
        sendEvent("evt_early", "payment.confirmed", order);

        // A primeira tentativa falha porque o pagamento ainda não existe.
        await().atMost(TIMEOUT).until(() -> List.of("RETRY_SCHEDULED", "DEAD").contains(eventStatus("evt_early")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error FROM webhook_events WHERE provider_event_id = 'evt_early'", String.class))
                .startsWith("PaymentNotFoundException");

        Instant now = Instant.now();
        orders.insert(Order.create("ord_late", new Money(15990, "BRL")), now);
        payments.insert(Payment.newPending("pay_late", "ord_late", new Money(15990, "BRL"), PaymentMethod.PIX), now);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(eventStatus("evt_early")).isIn("PROCESSED", "DEAD");
        });
        // Com 3 tentativas em poucos milissegundos o evento pode esgotar antes da criação; nesse caso, reprocessa.
        if ("DEAD".equals(eventStatus("evt_early"))) {
            reprocess("evt_early");
        }
        await().atMost(TIMEOUT).until(() -> "PAID".equals(orderStatus("ord_late")));
    }

    @Test
    void exhaustedEventGoesToDeadAndCanBeReprocessed() throws Exception {
        var order = new CreatedOrder("ord_dead", "pay_dead");
        sendEvent("evt_dead", "payment.confirmed", order);

        await().atMost(TIMEOUT).until(() -> "DEAD".equals(eventStatus("evt_dead")));
        JsonNode deadEvents = getJson("/admin/webhook-events?status=DEAD");
        assertThat(deadEvents.findValuesAsText("providerEventId")).contains("evt_dead");
        assertThat(count("SELECT attempts FROM webhook_events WHERE provider_event_id = 'evt_dead'")).isEqualTo(3);

        Instant now = Instant.now();
        orders.insert(Order.create("ord_dead", new Money(15990, "BRL")), now);
        payments.insert(Payment.newPending("pay_dead", "ord_dead", new Money(15990, "BRL"), PaymentMethod.PIX), now);
        reprocess("evt_dead");

        await().atMost(TIMEOUT).until(() -> "PAID".equals(orderStatus("ord_dead")));
        assertThat(eventStatus("evt_dead")).isEqualTo("PROCESSED");
    }

    @Test
    void amountMismatchIsDeadLetteredImmediately() throws Exception {
        CreatedOrder order = newOrder();

        sendEvent("evt_mismatch", "payment.confirmed", Instant.now(), order, 1);

        await().atMost(TIMEOUT).until(() -> "DEAD".equals(eventStatus("evt_mismatch")));
        assertThat(count("SELECT attempts FROM webhook_events WHERE provider_event_id = 'evt_mismatch'")).isEqualTo(1);
        assertThat(orderStatus(order.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void unknownEventTypeIsStoredAndIgnored() {
        String body = "{\"id\":\"evt_other\",\"type\":\"customer.created\",\"created_at\":\"%s\"}"
                .formatted(Instant.now());

        assertThat(sendSigned("evt_other", body.getBytes(StandardCharsets.UTF_8)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(TIMEOUT).until(() -> "IGNORED".equals(eventStatus("evt_other")));
    }

    @Test
    void invalidSignatureIsRejectedAndNothingIsStored() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(PaymentWebhookController.EVENT_ID_HEADER, "evt_forged");
        headers.set(PaymentWebhookController.TIMESTAMP_HEADER, String.valueOf(Instant.now().getEpochSecond()));
        headers.set(PaymentWebhookController.SIGNATURE_HEADER, "sha256=" + "0".repeat(64));
        String body = TestData.payload("evt_forged", "payment.confirmed", Instant.now());

        ResponseEntity<String> response = http.exchange(WEBHOOK_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("SELECT count(*) FROM webhook_events")).isZero();
    }

    @Test
    void devSimulatorDrivesTheRealEndpoint() throws Exception {
        ResponseEntity<String> created = http.postForEntity("/dev/orders",
                Map.of("amountInCents", 4990, "currency", "BRL", "method", "CREDIT_CARD"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = objectMapper.readTree(created.getBody()).path("orderId").asText();

        ResponseEntity<String> simulated = http.postForEntity("/dev/simulations/payment-events",
                Map.of("orderId", orderId, "type", "payment.confirmed", "eventId", "evt_sim"), String.class);

        assertThat(simulated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(simulated.getBody()).path("httpStatus").asInt()).isEqualTo(202);
        await().atMost(TIMEOUT).until(() -> "PAID".equals(orderStatus(orderId)));
    }

    @Test
    void healthAndMetricsAreExposed() throws Exception {
        assertThat(getJson("/actuator/health").path("status").asText()).isEqualTo("UP");

        sendEvent("evt_metrics", "payment.confirmed", newOrder());
        await().atMost(TIMEOUT).until(() -> "PROCESSED".equals(eventStatus("evt_metrics")));

        String prometheus = http.getForObject("/actuator/prometheus", String.class);
        assertThat(prometheus)
                .contains("webhook_received_total")
                .contains("webhook_processed_total")
                .contains("webhook_processing_time_seconds");
    }

    private void reprocess(String providerEventId) {
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM webhook_events WHERE provider_event_id = ?",
                UUID.class, providerEventId);
        ResponseEntity<String> response = http.postForEntity("/admin/webhook-events/" + id + "/reprocess", null,
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
