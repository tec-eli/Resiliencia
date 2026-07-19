package io.github.teceli.resiliencia.patterns.ratelimiter;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link RateLimiterException} constructor validation.
 */
class RateLimiterExceptionTest {

    @Test
    void should_throwNullPointerException_when_nameIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RateLimiterException(null, 1, Duration.ofMillis(100), Duration.ZERO))
                .withMessageContaining("name");
    }

    @Test
    void should_throwNullPointerException_when_periodIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RateLimiterException("rate-limiter", 1, null, Duration.ZERO))
                .withMessageContaining("period");
    }

    @Test
    void should_throwNullPointerException_when_maxWaitIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RateLimiterException("rate-limiter", 1, Duration.ofMillis(100), null))
                .withMessageContaining("maxWait");
    }

    @Test
    void should_buildException_when_allArgumentsAreValid() {
        var exception = new RateLimiterException("rate-limiter", 5, Duration.ofSeconds(1), Duration.ofMillis(200));

        assertThat(exception.name()).isEqualTo("rate-limiter");
        assertThat(exception.limit()).isEqualTo(5);
        assertThat(exception.period()).isEqualTo(Duration.ofSeconds(1));
        assertThat(exception.maxWait()).isEqualTo(Duration.ofMillis(200));
    }
}
