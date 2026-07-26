package io.github.viniciusssantos.accountshield.outbox.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OutboxBackoffCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

    @Test
    void delayIsBetweenHalfAndFullOfTheUncappedExponentialValue() {
        OutboxBackoffCalculator calculator = new OutboxBackoffCalculator(
                Duration.ofSeconds(1), Duration.ofMinutes(10), new Random(42));

        Instant next = calculator.nextAttemptAt(NOW, 2);

        long delayMillis = Duration.between(NOW, next).toMillis();
        long uncapped = Duration.ofSeconds(1).toMillis() * 4; // base * 2^2
        assertThat(delayMillis).isBetween(uncapped / 2, uncapped);
    }

    @Test
    void delayNeverExceedsMaxDelayRegardlessOfAttemptCount() {
        OutboxBackoffCalculator calculator = new OutboxBackoffCalculator(
                Duration.ofSeconds(1), Duration.ofMinutes(5), new Random(7));

        for (int attempt = 0; attempt < 40; attempt++) {
            Instant next = calculator.nextAttemptAt(NOW, attempt);
            long delayMillis = Duration.between(NOW, next).toMillis();
            assertThat(delayMillis).isLessThanOrEqualTo(Duration.ofMinutes(5).toMillis());
        }
    }

    @Test
    void delayGrowsWithAttemptCountBeforeHittingTheCap() {
        OutboxBackoffCalculator calculator = new OutboxBackoffCalculator(
                Duration.ofSeconds(1), Duration.ofMinutes(10), new Random(1));

        long delayAttempt0 = Duration.between(NOW, calculator.nextAttemptAt(NOW, 0)).toMillis();
        long delayAttempt3 = Duration.between(NOW, calculator.nextAttemptAt(NOW, 3)).toMillis();

        assertThat(delayAttempt3).isGreaterThan(delayAttempt0);
    }
}
