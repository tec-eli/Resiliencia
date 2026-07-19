package io.github.teceli.resiliencia.metrics.ratelimiter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterCountersTest {

    @Test
    void should_exposeNameAndOutcome_when_callConstructed() {
        var call = new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.PERMITTED);

        assertThat(call.name()).isEqualTo("myLimiter");
        assertThat(call.outcome()).isEqualTo(RateLimiterCounters.Outcome.PERMITTED);
    }

    @Test
    void should_beEqual_when_sameNameAndOutcome() {
        var first = new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.REJECTED);
        var second = new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.REJECTED);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_notBeEqual_when_outcomeDiffers() {
        var permitted = new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.PERMITTED);
        var rejected = new RateLimiterCounters.Call("myLimiter", RateLimiterCounters.Outcome.REJECTED);

        assertThat(permitted).isNotEqualTo(rejected);
    }

    @Test
    void should_allowNullName_when_nameNotProvided() {
        var call = new RateLimiterCounters.Call(null, RateLimiterCounters.Outcome.PERMITTED);

        assertThat(call.name()).as("record performs no validation, so a null name is accepted rather than rejected")
            .isNull();
    }
}
