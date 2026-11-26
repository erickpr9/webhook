package com.estudos.webhook.domain.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(6, List.of(
            Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(2),
            Duration.ofMinutes(10), Duration.ofHours(1)));

    @ParameterizedTest(name = "after attempt {0} wait {1}")
    @CsvSource({"1, PT10S", "2, PT30S", "3, PT2M", "4, PT10M", "5, PT1H", "9, PT1H"})
    void delayFollowsBackoffAndRepeatsLastInterval(int attempts, Duration expected) {
        assertThat(policy.delayAfter(attempts)).isEqualTo(expected);
    }

    @Test
    void zeroAttemptsUsesFirstInterval() {
        assertThat(policy.delayAfter(0)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void exhaustedAtMaxAttempts() {
        assertThat(policy.isExhausted(5)).isFalse();
        assertThat(policy.isExhausted(6)).isTrue();
        assertThat(policy.isExhausted(7)).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(6);
    }

    @Test
    void exponentialBackoffGrowsUntilTheMaximum() {
        RetryPolicy exponential = RetryPolicy.exponential(6, Duration.ofSeconds(10), 3, Duration.ofMinutes(5));

        assertThat(exponential.maxAttempts()).isEqualTo(6);
        assertThat(exponential.delayAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(exponential.delayAfter(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(exponential.delayAfter(3)).isEqualTo(Duration.ofSeconds(90));
        assertThat(exponential.delayAfter(4)).isEqualTo(Duration.ofSeconds(270));
        assertThat(exponential.delayAfter(5)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void exponentialBackoffWithSingleAttemptStillHasAnInterval() {
        assertThat(RetryPolicy.exponential(1, Duration.ofSeconds(1), 2, Duration.ofSeconds(1)).delayAfter(1))
                .isEqualTo(Duration.ofSeconds(1));
        assertThatIllegalArgumentException().isThrownBy(
                () -> RetryPolicy.exponential(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(0, List.of(Duration.ofSeconds(1))));
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryPolicy(3, List.of()));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(3, List.of(Duration.ofSeconds(-1))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryPolicy(3, Arrays.asList(Duration.ofSeconds(1), null)));
    }

    @Test
    void backoffIsDefensivelyCopied() {
        var intervals = new java.util.ArrayList<>(List.of(Duration.ofSeconds(1)));
        RetryPolicy copy = new RetryPolicy(2, intervals);

        intervals.set(0, Duration.ofHours(9));

        assertThat(copy.delayAfter(1)).isEqualTo(Duration.ofSeconds(1));
    }
}
