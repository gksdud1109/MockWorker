package com.realteeth.mockworker.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffCalculatorTest {

    private final Duration initial = Duration.ofSeconds(1);
    private final Duration max = Duration.ofSeconds(30);

    @Test
    void zero_done_returns_initial() {
        assertThat(BackoffCalculator.next(initial, max, 0)).isEqualTo(initial);
    }

    @Test
    void each_attempt_doubles_the_interval() {
        assertThat(BackoffCalculator.next(initial, max, 1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(BackoffCalculator.next(initial, max, 2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(BackoffCalculator.next(initial, max, 3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void respects_ceiling() {
        assertThat(BackoffCalculator.next(initial, max, 10)).isEqualTo(max);
    }

    @Test
    void negative_done_count_treated_as_zero() {
        assertThat(BackoffCalculator.next(initial, max, -1)).isEqualTo(initial);
    }
}
