package com.estudos.webhook.api.filter;

import java.io.IOException;

/**
 * O corpo da requisição passou do limite configurado. Resulta em {@code 413}.
 *
 * <p>Estende {@link IOException} porque é lançada durante a leitura do stream da requisição.
 */
public class PayloadTooLargeException extends IOException {

    public PayloadTooLargeException(long maxBytes) {
        super("Request body exceeds the limit of " + maxBytes + " bytes");
    }
}
