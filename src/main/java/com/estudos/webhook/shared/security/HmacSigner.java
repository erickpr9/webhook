package com.estudos.webhook.shared.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Assinatura HMAC-SHA256 do conteúdo {@code "{timestamp}.{body}"}.
 *
 * <p>Incluir o timestamp no conteúdo assinado impede que um atacante reaproveite uma
 * assinatura válida trocando apenas o header de horário.
 */
public final class HmacSigner {

    /** Prefixo do header de assinatura: {@code sha256=<hex>}. */
    public static final String SIGNATURE_PREFIX = "sha256=";

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public HmacSigner(String secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    /** Calcula a assinatura binária de {@code timestamp + "." + body}. */
    public byte[] sign(String timestamp, byte[] body) {
        try {
            // Mac não é thread-safe: uma instância por chamada.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Valor pronto para o header {@code X-Signature}. */
    public String signatureHeader(String timestamp, byte[] body) {
        return SIGNATURE_PREFIX + HexFormat.of().formatHex(sign(timestamp, body));
    }
}
