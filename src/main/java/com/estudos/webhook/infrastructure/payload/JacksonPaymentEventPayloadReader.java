package com.estudos.webhook.infrastructure.payload;

import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.exception.MalformedPayloadException;
import com.estudos.webhook.application.port.PaymentEventPayloadReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Lê o bloco {@code data} do payload do provedor:
 *
 * <pre>{@code
 * { "data": { "payment_id": "...", "order_id": "...", "amount": 15990, "currency": "BRL" } }
 * }</pre>
 */
@Component
public class JacksonPaymentEventPayloadReader implements PaymentEventPayloadReader {

    private final ObjectMapper objectMapper;

    public JacksonPaymentEventPayloadReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentEventData read(String payload) {
        JsonNode data = parse(payload).path("data");
        if (!data.isObject()) {
            throw new MalformedPayloadException("Payload has no 'data' object");
        }
        return new PaymentEventData(
                requiredText(data, "payment_id"),
                requiredText(data, "order_id"),
                requiredAmount(data),
                requiredText(data, "currency"));
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new MalformedPayloadException("Payload is not valid JSON", e);
        }
    }

    private static String requiredText(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new MalformedPayloadException("Field 'data." + field + "' is missing or not a string");
        }
        return value.asText();
    }

    private static long requiredAmount(JsonNode data) {
        JsonNode value = data.get("amount");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new MalformedPayloadException("Field 'data.amount' is missing or not an integer (cents)");
        }
        return value.asLong();
    }
}
