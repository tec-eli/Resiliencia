package io.github.teceli.resiliencia.metrics.ratelimiter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterSnapshotTest {

    @Test
    void should_exposeNameAndRemaining_when_remainingPermitsConstructed() {
        var remainingPermits = new RateLimiterSnapshot.RemainingPermits("myLimiter", 97);

        assertThat(remainingPermits.name()).isEqualTo("myLimiter");
        assertThat(remainingPermits.remaining()).isEqualTo(97);
    }

    @Test
    void should_beEqual_when_sameNameAndRemaining() {
        var first = new RateLimiterSnapshot.RemainingPermits("myLimiter", 97);
        var second = new RateLimiterSnapshot.RemainingPermits("myLimiter", 97);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_notBeEqual_when_remainingDiffers() {
        var withNinetySeven = new RateLimiterSnapshot.RemainingPermits("myLimiter", 97);
        var withZero = new RateLimiterSnapshot.RemainingPermits("myLimiter", 0);

        assertThat(withNinetySeven).isNotEqualTo(withZero);
    }
}
