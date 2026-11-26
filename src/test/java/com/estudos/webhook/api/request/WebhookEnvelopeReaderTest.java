package com.estudos.webhook.api.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEnvelopeReaderTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    private final WebhookEnvelopeReader reader = new WebhookEnvelopeReader(
            new ObjectMapper().registerModule(new JavaTimeModule())
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            VALIDATORS.getValidator());

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsEnvelopeAndIgnoresData() {
        WebhookEnvelope envelope = reader.read(json("""
                {"id":"evt_1","type":"payment.confirmed","created_at":"2026-11-26T14:03:11Z","data":{"x":1}}"""));

        assertThat(envelope).isEqualTo(new WebhookEnvelope("evt_1", "payment.confirmed",
                Instant.parse("2026-11-26T14:03:11Z")));
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> reader.read(json("{not json")))
                .isInstanceOf(InvalidWebhookRequestException.class)
                .hasMessageContaining("not a valid");
    }

    @Test
    void rejectsNullBody() {
        assertThatThrownBy(() -> reader.read(json("null")))
                .isInstanceOf(InvalidWebhookRequestException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void reportsEveryMissingField() {
        assertThatThrownBy(() -> reader.read(json("{\"id\":\"\"}")))
                .isInstanceOf(InvalidWebhookRequestException.class)
                .satisfies(ex -> assertThat(((InvalidWebhookRequestException) ex).details())
                        .hasSize(3)
                        .anyMatch(detail -> detail.startsWith("createdAt"))
                        .anyMatch(detail -> detail.startsWith("id"))
                        .anyMatch(detail -> detail.startsWith("type")));
    }

    @Test
    void rejectsIdLongerThanTheColumn() {
        String longId = "e".repeat(101);

        assertThatThrownBy(() -> reader.read(json("""
                {"id":"%s","type":"payment.confirmed","created_at":"2026-11-26T14:03:11Z"}""".formatted(longId))))
                .isInstanceOf(InvalidWebhookRequestException.class);
    }
}
