package com.estudos.webhook.api.controller;

import com.estudos.webhook.shared.security.HmacSigner;
import com.estudos.webhook.application.dto.RegisterWebhookEventCommand;
import com.estudos.webhook.application.dto.RegistrationResult;
import com.estudos.webhook.application.usecase.RegisterWebhookEventUseCase;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentWebhookController.class)
@Import(WebMvcTestSupport.class)
@ActiveProfiles("test")
class PaymentWebhookControllerTest {

    private static final String URL = "/v1/webhooks/payments";
    private static final String EVENT_ID = "evt_123";
    private static final Instant CREATED_AT = TestData.NOW.minusSeconds(5);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private HmacSigner signer;
    @MockitoBean
    private RegisterWebhookEventUseCase registerWebhookEvent;

    private final String timestamp = String.valueOf(TestData.NOW.getEpochSecond());

    private static String validBody() {
        return TestData.payload(EVENT_ID, "payment.confirmed", CREATED_AT);
    }

    private MockHttpServletRequestBuilder signedRequest(String eventId, String body) {
        return post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PaymentWebhookController.EVENT_ID_HEADER, eventId)
                .header(PaymentWebhookController.TIMESTAMP_HEADER, timestamp)
                .header(PaymentWebhookController.SIGNATURE_HEADER,
                        signer.signatureHeader(timestamp, body.getBytes(StandardCharsets.UTF_8)))
                .content(body);
    }

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    @Test
    void acceptsNewEventAndRegistersItInTheInbox() throws Exception {
        when(registerWebhookEvent.execute(any())).thenReturn(new RegistrationResult(EVENT_ID, false));

        send(signedRequest(EVENT_ID, validBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("received"))
                .andExpect(jsonPath("$.eventId").value(EVENT_ID));

        ArgumentCaptor<RegisterWebhookEventCommand> command = ArgumentCaptor.forClass(RegisterWebhookEventCommand.class);
        verify(registerWebhookEvent).execute(command.capture());
        assertThat(command.getValue()).isEqualTo(new RegisterWebhookEventCommand("payment-gateway", EVENT_ID,
                "payment.confirmed", CREATED_AT, validBody()));
    }

    @Test
    void answersOkForRedeliveredEvent() throws Exception {
        when(registerWebhookEvent.execute(any())).thenReturn(new RegistrationResult(EVENT_ID, true));

        send(signedRequest(EVENT_ID, validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }

    @Test
    void rejectsMissingSignatureHeader() throws Exception {
        send(post(URL).contentType(MediaType.APPLICATION_JSON)
                .header(PaymentWebhookController.EVENT_ID_HEADER, EVENT_ID)
                .header(PaymentWebhookController.TIMESTAMP_HEADER, timestamp)
                .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required header X-Signature"));
        verifyNoInteractions(registerWebhookEvent);
    }

    @Test
    void rejectsInvalidSignatureWithGenericMessage() throws Exception {
        send(signedRequest(EVENT_ID, validBody()).header(PaymentWebhookController.SIGNATURE_HEADER, "sha256=00"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid webhook signature"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value(URL));
        verifyNoInteractions(registerWebhookEvent);
    }

    @Test
    void rejectsReplayedRequestOutsideTheTimeWindow() throws Exception {
        String oldTimestamp = String.valueOf(TestData.NOW.minusSeconds(600).getEpochSecond());
        byte[] body = validBody().getBytes(StandardCharsets.UTF_8);

        send(post(URL).contentType(MediaType.APPLICATION_JSON)
                .header(PaymentWebhookController.EVENT_ID_HEADER, EVENT_ID)
                .header(PaymentWebhookController.TIMESTAMP_HEADER, oldTimestamp)
                .header(PaymentWebhookController.SIGNATURE_HEADER, signer.signatureHeader(oldTimestamp, body))
                .content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registerWebhookEvent);
    }

    @Test
    void rejectsNonNumericTimestamp() throws Exception {
        send(signedRequest(EVENT_ID, validBody()).header(PaymentWebhookController.TIMESTAMP_HEADER, "yesterday"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsEventIdHeaderThatDiffersFromPayload() throws Exception {
        send(signedRequest("evt_other", validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Event-Id does not match the payload id"));
        verifyNoInteractions(registerWebhookEvent);
    }

    @Test
    void rejectsMalformedJsonEvenWhenSigned() throws Exception {
        send(signedRequest(EVENT_ID, "{broken"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsValidationDetails() throws Exception {
        send(signedRequest(EVENT_ID, "{\"id\":\"evt_123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem("type: must not be blank")));
    }

    @Test
    void rejectsBodyAboveTheLimit() throws Exception {
        String huge = "{\"id\":\"" + "x".repeat(70_000) + "\"}";

        send(signedRequest(EVENT_ID, huge))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(registerWebhookEvent);
    }

    @Test
    void rejectsNonJsonContentType() throws Exception {
        send(signedRequest(EVENT_ID, validBody()).contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsUnsupportedMethod() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(URL))
                .andExpect(status().isMethodNotAllowed());
    }
}
