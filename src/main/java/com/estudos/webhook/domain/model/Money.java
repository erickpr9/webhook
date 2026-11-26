package com.estudos.webhook.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Valor monetário em centavos, sempre positivo, com moeda ISO 4217 (ex.: {@code BRL}).
 *
 * <p>Usar centavos em {@code long} evita os erros de arredondamento de {@code double}.
 */
public record Money(long amountInCents, String currency) {

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
        if (amountInCents <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero, got " + amountInCents);
        }
        if (!CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO 4217 code, got '" + currency + "'");
        }
    }
}
