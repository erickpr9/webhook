package com.estudos.webhook.api.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Converte o corpo cru do webhook em {@link WebhookEnvelope} e aplica a Bean Validation.
 *
 * <p>O parse é manual (e não via {@code @RequestBody WebhookEnvelope}) porque a assinatura
 * precisa ser calculada sobre os bytes exatos recebidos, antes de qualquer desserialização.
 */
public final class WebhookEnvelopeReader {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public WebhookEnvelopeReader(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.validator = Objects.requireNonNull(validator);
    }

    /**
     * @throws InvalidWebhookRequestException se o JSON for inválido ou faltar campo obrigatório
     */
    public WebhookEnvelope read(byte[] body) {
        WebhookEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, WebhookEnvelope.class);
        } catch (IOException e) {
            throw new InvalidWebhookRequestException("Request body is not a valid webhook JSON payload");
        }
        if (envelope == null) {
            throw new InvalidWebhookRequestException("Request body must not be empty");
        }

        List<String> violations = validator.validate(envelope).stream()
                .sorted(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .map(WebhookEnvelopeReader::describe)
                .toList();
        if (!violations.isEmpty()) {
            throw new InvalidWebhookRequestException("Webhook payload is invalid", violations);
        }
        return envelope;
    }

    private static String describe(ConstraintViolation<WebhookEnvelope> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }
}
