package com.estudos.webhook.api.security;

import com.estudos.webhook.shared.security.HmacSigner;
import com.estudos.webhook.api.request.InvalidWebhookRequestException;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Autentica o webhook: janela de tempo (anti-replay) e assinatura HMAC.
 *
 * <p>A janela é verificada antes da assinatura por ser mais barata. A comparação da assinatura
 * usa {@link MessageDigest#isEqual}, em tempo constante, para não vazar informação por timing.
 */
public final class WebhookSignatureVerifier {

    private final HmacSigner signer;
    private final Duration tolerance;
    private final Clock clock;

    public WebhookSignatureVerifier(HmacSigner signer, Duration tolerance, Clock clock) {
        this.signer = Objects.requireNonNull(signer);
        this.tolerance = Objects.requireNonNull(tolerance);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * @param timestampHeader valor de {@code X-Timestamp} (epoch em segundos)
     * @param signatureHeader valor de {@code X-Signature} ({@code sha256=<hex>})
     * @param body            corpo cru da requisição, exatamente como recebido
     * @throws InvalidWebhookRequestException se o timestamp não for numérico
     * @throws InvalidSignatureException      se estiver fora da janela ou a assinatura não conferir
     */
    public void verify(String timestampHeader, String signatureHeader, byte[] body) {
        Instant sentAt = parseTimestamp(timestampHeader);
        if (Duration.between(sentAt, clock.instant()).abs().compareTo(tolerance) > 0) {
            throw new InvalidSignatureException("Timestamp is outside the accepted window");
        }

        byte[] provided = parseSignature(signatureHeader);
        byte[] expected = signer.sign(timestampHeader, body);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new InvalidSignatureException("Signature does not match");
        }
    }

    private static Instant parseTimestamp(String header) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(header.trim()));
        } catch (RuntimeException e) {
            throw new InvalidWebhookRequestException("X-Timestamp must be the epoch time in seconds");
        }
    }

    private static byte[] parseSignature(String header) {
        if (!header.startsWith(HmacSigner.SIGNATURE_PREFIX)) {
            throw new InvalidSignatureException("Signature must use the format sha256=<hex>");
        }
        try {
            return HexFormat.of().parseHex(header.substring(HmacSigner.SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new InvalidSignatureException("Signature must use the format sha256=<hex>");
        }
    }
}
