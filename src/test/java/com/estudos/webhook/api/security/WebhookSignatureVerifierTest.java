package com.estudos.webhook.api.security;

import com.estudos.webhook.shared.security.HmacSigner;
import com.estudos.webhook.api.request.InvalidWebhookRequestException;
import com.estudos.webhook.support.TestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret-with-at-least-thirty-two-characters";
    private static final byte[] BODY = "{\"id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final long NOW_EPOCH = TestData.NOW.getEpochSecond();

    private final HmacSigner signer = new HmacSigner(SECRET);
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(signer, Duration.ofMinutes(5),
            Clock.fixed(TestData.NOW, ZoneOffset.UTC));

    @Test
    void signerMatchesAnIndependentHmacImplementation() throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(("123." + new String(BODY, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));

        assertThat(signer.signatureHeader("123", BODY)).isEqualTo("sha256=" + HexFormat.of().formatHex(expected));
    }

    @Test
    void acceptsValidSignature() {
        String timestamp = String.valueOf(NOW_EPOCH);

        assertThatCode(() -> verifier.verify(timestamp, signer.signatureHeader(timestamp, BODY), BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTimestampAtTheEdgeOfTheWindow() {
        String timestamp = String.valueOf(NOW_EPOCH - 300);

        assertThatCode(() -> verifier.verify(timestamp, signer.signatureHeader(timestamp, BODY), BODY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301, -86_400})
    void rejectsTimestampOutsideTheWindow(long offsetSeconds) {
        String timestamp = String.valueOf(NOW_EPOCH + offsetSeconds);

        assertThatThrownBy(() -> verifier.verify(timestamp, signer.signatureHeader(timestamp, BODY), BODY))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("window");
    }

    @Test
    void rejectsTamperedBody() {
        String timestamp = String.valueOf(NOW_EPOCH);
        String signature = signer.signatureHeader(timestamp, BODY);
        byte[] tampered = "{\"id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> verifier.verify(timestamp, signature, tampered))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void rejectsSignatureReusedWithAnotherTimestamp() {
        String original = String.valueOf(NOW_EPOCH - 10);
        String signature = signer.signatureHeader(original, BODY);

        assertThatThrownBy(() -> verifier.verify(String.valueOf(NOW_EPOCH), signature, BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void rejectsSignatureMadeWithAnotherSecret() {
        String timestamp = String.valueOf(NOW_EPOCH);
        String signature = new HmacSigner("another-secret-with-at-least-thirty-two-chars").signatureHeader(timestamp, BODY);

        assertThatThrownBy(() -> verifier.verify(timestamp, signature, BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "sha1=abcdef", "sha256=zz", "sha256=abc"})
    void rejectsMalformedSignature(String signature) {
        assertThatThrownBy(() -> verifier.verify(String.valueOf(NOW_EPOCH), signature, BODY))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "now", "12.5", "99999999999999999999"})
    void rejectsNonNumericTimestampAsBadRequest(String timestamp) {
        assertThatThrownBy(() -> verifier.verify(timestamp, "sha256=00", BODY))
                .isInstanceOf(InvalidWebhookRequestException.class);
    }
}
