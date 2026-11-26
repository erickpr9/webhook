package com.estudos.webhook.infrastructure.notification;

import com.estudos.webhook.application.dto.NotificationMessage;
import com.estudos.webhook.application.exception.PermanentDeliveryException;
import com.estudos.webhook.shared.security.HmacSigner;
import com.estudos.webhook.support.TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpNotificationSenderTest {

    private static final URI ENDPOINT = URI.create("http://client.local/webhooks/payment-notifications");
    private static final String SECRET = "client-secret-with-at-least-thirty-two-chars";
    private static final UUID NOTIFICATION_ID = UUID.fromString("0d8f6f5e-2c1b-4c55-9d1e-6a3b2c1d0e9f");
    private static final NotificationMessage MESSAGE = new NotificationMessage("PAYMENT_CONFIRMED",
            TestData.PAYMENT_ID, TestData.ORDER_ID, 15990, "BRL", "CONFIRMED", TestData.NOW.minusSeconds(3));

    private final HmacSigner signer = new HmacSigner(SECRET);
    private MockRestServiceServer server;
    private HttpNotificationSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new HttpNotificationSender(builder, ENDPOINT, signer, new ObjectMapper(),
                Clock.fixed(TestData.NOW, ZoneOffset.UTC));
    }

    @Test
    void postsSignedNotificationWithStableId() {
        String timestamp = String.valueOf(TestData.NOW.getEpochSecond());
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Event-Id", NOTIFICATION_ID.toString()))
                .andExpect(header("X-Timestamp", timestamp))
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.type").value("payment.confirmed"))
                .andExpect(jsonPath("$.occurred_at").value(MESSAGE.occurredAt().toString()))
                .andExpect(jsonPath("$.data.order_id").value(TestData.ORDER_ID))
                .andExpect(jsonPath("$.data.payment_id").value(TestData.PAYMENT_ID))
                .andExpect(jsonPath("$.data.amount").value(15990))
                .andExpect(jsonPath("$.data.payment_status").value("CONFIRMED"))
                .andExpect(request -> {
                    // A assinatura confere com o corpo exato enviado.
                    byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
                    assertThat(request.getHeaders().getFirst("X-Signature"))
                            .isEqualTo(signer.signatureHeader(timestamp, body));
                    assertThat(new String(body, StandardCharsets.UTF_8)).startsWith("{\"id\":");
                })
                .andRespond(withSuccess());

        sender.send(NOTIFICATION_ID, MESSAGE);

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 408, 429})
    void transientErrorsAreRetryable(int status) {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> sender.send(NOTIFICATION_ID, MESSAGE))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining(String.valueOf(status));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 410, 422})
    void clientRejectionsArePermanent(int status) {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> sender.send(NOTIFICATION_ID, MESSAGE))
                .isInstanceOf(PermanentDeliveryException.class)
                .hasMessageContaining(String.valueOf(status));
    }

    @Test
    void unreachableEndpointIsRetryable() {
        // O RestClient converte a IOException da conexão em ResourceAccessException.
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new IOException("Connection refused");
        });

        assertThatThrownBy(() -> sender.send(NOTIFICATION_ID, MESSAGE))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void unknownNotificationTypeIsPermanent() {
        var unknown = new NotificationMessage("SOMETHING_ELSE", "pay", "ord", 1, "BRL", "PENDING", TestData.NOW);

        assertThatThrownBy(() -> sender.send(NOTIFICATION_ID, unknown)).isInstanceOf(PermanentDeliveryException.class);
    }
}
