package com.estudos.webhook.dev;

import com.estudos.webhook.api.controller.PaymentWebhookController;
import com.estudos.webhook.shared.security.HmacSigner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simula o provedor de pagamentos: monta o evento, assina com o segredo compartilhado e
 * envia por HTTP para o próprio endpoint de webhook, percorrendo o caminho real de entrada.
 */
public final class PaymentProviderSimulator {

    private static final String WEBHOOK_PATH = "/v1/webhooks/payments";

    private final HmacSigner signer;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final Clock clock;
    private final RestClient restClient;

    public PaymentProviderSimulator(HmacSigner signer, ObjectMapper objectMapper, Environment environment, Clock clock,
                                    RestClient.Builder restClientBuilder) {
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.clock = clock;
        // Respostas 4xx/5xx são parte da simulação: devolve o status em vez de lançar exceção.
        this.restClient = restClientBuilder
                .defaultStatusHandler(status -> true, (request, response) -> {
                })
                .build();
    }

    /**
     * Envia um evento assinado.
     *
     * @param event dados do evento a simular
     * @return status e corpo devolvidos pelo endpoint de webhook
     */
    public SimulationResult send(SimulatedEvent event) {
        byte[] body = toJson(event);
        String timestamp = String.valueOf(clock.instant().getEpochSecond());

        ResponseEntity<String> response = restClient.post()
                .uri(baseUrl() + WEBHOOK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PaymentWebhookController.EVENT_ID_HEADER, event.eventId())
                .header(PaymentWebhookController.TIMESTAMP_HEADER, timestamp)
                .header(PaymentWebhookController.SIGNATURE_HEADER, signer.signatureHeader(timestamp, body))
                .body(body)
                .retrieve()
                .toEntity(String.class);

        return new SimulationResult(event.eventId(), response.getStatusCode().value(), response.getBody());
    }

    private byte[] toJson(SimulatedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment_id", event.paymentId());
        data.put("order_id", event.orderId());
        data.put("amount", event.amountInCents());
        data.put("currency", event.currency());
        data.put("method", event.method());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", event.eventId());
        envelope.put("type", event.type());
        envelope.put("created_at", event.occurredAt().toString());
        envelope.put("data", data);
        try {
            return objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize simulated event", e);
        }
    }

    private String baseUrl() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port;
    }

    /**
     * Evento a ser simulado.
     */
    public record SimulatedEvent(String eventId, String type, Instant occurredAt, String paymentId, String orderId,
                                 long amountInCents, String currency, String method) {
    }

    /**
     * Resultado do envio.
     */
    public record SimulationResult(String eventId, int httpStatus, String responseBody) {
    }
}
