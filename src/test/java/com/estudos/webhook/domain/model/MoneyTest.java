package com.estudos.webhook.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class MoneyTest {

    @Test
    void createsValidAmount() {
        Money money = new Money(15990, "BRL");

        assertThat(money.amountInCents()).isEqualTo(15990);
        assertThat(money.currency()).isEqualTo("BRL");
    }

    @Test
    void equalityIsByValue() {
        assertThat(new Money(100, "BRL")).isEqualTo(new Money(100, "BRL")).isNotEqualTo(new Money(100, "USD"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -15990})
    void rejectsNonPositiveAmount(long amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(amount, "BRL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "BR", "brl", "BRLX", "12A"})
    void rejectsInvalidCurrency(String currency) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(100, currency));
    }

    @Test
    void rejectsNullCurrency() {
        assertThatNullPointerException().isThrownBy(() -> new Money(100, null));
    }
}
