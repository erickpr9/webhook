package com.estudos.webhook.infrastructure.notification;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.exception.PermanentDeliveryException;
import com.estudos.webhook.application.port.NotificationSender;
import com.estudos.webhook.domain.model.PaymentEventType;
import com.estudos.webhook.shared.security.HmacSigner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Webhook de saída: avisa o cliente sobre a mudança no pagamento com um POST assinado.
 *
 * <p>Usa o mesmo contrato que este sistema exige de quem o chama: {@code X-Event-Id},
 * {@code X-Timestamp} e {@code X-Signature = sha256=HMAC-SHA256(secret, "{timestamp}.{body}")}.
 * O {@code X-Event-Id} é o id da notificação no outbox, igual em todas as tentativas, para o
 * cliente descartar reentregas.
 *
 * <p>Classificação da resposta:
 * <ul>
 *   <li>{@code 2xx}: entregue;</li>
 *   <li>{@code 408}, {@code 429}, {@code 5xx}, timeout ou conexão recusada: falha transitória,
 *       nova tentativa com backoff;</li>
 *   <li>demais {@code 4xx}: recusa definitiva ({@link PermanentDeliveryException}), vai para DEAD.</li>
 * </ul>
 */
public final class HttpNotificationSender implements NotificationSender {

    static final String EVENT_ID_HEADER = "X-Event-Id";
    static final String TIMESTAMP_HEADER = "X-Timestamp";
    static final String SIGNATURE_HEADER = "X-Signature";

    private static final Logger log = LoggerFactory.getLogger(HttpNotificationSender.class);

    private final RestClient restClient;
    private final URI endpoint;
    private final HmacSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpNotificationSender(RestClient.Builder restClientBuilder, URI endpoint, HmacSigner signer,
                                  ObjectMapper objectMapper, Clock clock) {
        this.restClient = restClientBuilder.build();
        this.endpoint = Objects.requireNonNull(endpoint);
        this.signer = Objects.requireNonNull(signer);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void send(UUID notificationId, NotificationMessage message) {
        byte[] body = toJson(notificationId, message);
        String timestamp = String.valueOf(clock.instant().getEpochSecond());

        try {
            restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(EVENT_ID_HEADER, notificationId.toString())
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, signer.signatureHeader(timestamp, body))
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw failureFor(response.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException unreachable) {
            throw new NotificationDeliveryException(
                    "Client endpoint unreachable or timed out: " + unreachable.getMessage(), unreachable);
        }
        log.info("Loja avisada com sucesso: {} do pedido {} entregue em {} (aviso {})",
                eventCode(message.type()), message.orderId(), endpoint, notificationId);
    }

    private RuntimeException failureFor(HttpStatusCode status) {
        int code = status.value();
        if (status.is5xxServerError() || code == 408 || code == 429) {
            return new NotificationDeliveryException("Client endpoint answered " + code);
        }
        return new PermanentDeliveryException("Client endpoint rejected the notification with " + code);
    }

    private byte[] toJson(UUID notificationId, NotificationMessage message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_id", message.orderId());
        data.put("payment_id", message.paymentId());
        data.put("amount", message.amountInCents());
        data.put("currency", message.currency());
        data.put("payment_status", message.paymentStatus());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", notificationId.toString());
        envelope.put("type", eventCode(message.type()));
        envelope.put("occurred_at", message.occurredAt().toString());
        envelope.put("data", data);
        try {
            return objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize notification", e);
        }
    }

    /** Converte {@code PAYMENT_CONFIRMED} em {@code payment.confirmed}, o mesmo vocabulário da entrada. */
    private static String eventCode(String notificationType) {
        try {
            return PaymentEventType.valueOf(notificationType).code();
        } catch (IllegalArgumentException unknown) {
            throw new PermanentDeliveryException("Unknown notification type " + notificationType);
        }
    }
}
