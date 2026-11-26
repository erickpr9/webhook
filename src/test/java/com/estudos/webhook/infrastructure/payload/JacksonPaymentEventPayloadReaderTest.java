package com.estudos.webhook.infrastructure.payload;

import com.estudos.webhook.application.dto.PaymentEventData;
import com.estudos.webhook.application.exception.MalformedPayloadException;
import com.estudos.webhook.support.TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonPaymentEventPayloadReaderTest {

    private final JacksonPaymentEventPayloadReader reader = new JacksonPaymentEventPayloadReader(new ObjectMapper());

    @Test
    void readsPaymentData() {
        PaymentEventData data = reader.read(TestData.payload("evt_1", "payment.confirmed", TestData.NOW));

        assertThat(data).isEqualTo(TestData.eventData());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{}",
            "{\"data\":[]}",
            "{\"data\":{\"order_id\":\"o\",\"amount\":1,\"currency\":\"BRL\"}}",
            "{\"data\":{\"payment_id\":\" \",\"order_id\":\"o\",\"amount\":1,\"currency\":\"BRL\"}}",
            "{\"data\":{\"payment_id\":1,\"order_id\":\"o\",\"amount\":1,\"currency\":\"BRL\"}}",
            "{\"data\":{\"payment_id\":\"p\",\"order_id\":\"o\",\"amount\":\"1\",\"currency\":\"BRL\"}}",
            "{\"data\":{\"payment_id\":\"p\",\"order_id\":\"o\",\"amount\":15.9,\"currency\":\"BRL\"}}",
            "{\"data\":{\"payment_id\":\"p\",\"order_id\":\"o\",\"amount\":1}}"
    })
    void rejectsMalformedPayloads(String payload) {
        assertThatThrownBy(() -> reader.read(payload)).isInstanceOf(MalformedPayloadException.class);
    }
}
