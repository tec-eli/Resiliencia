package io.github.teceli.resiliencia.metrics.bulkhead;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkheadCountersTest {

    @Test
    void should_exposeNameAndOutcome_when_callConstructed() {
        var call = new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.PERMITTED);

        assertThat(call.name()).isEqualTo("myBulkhead");
        assertThat(call.outcome()).isEqualTo(BulkheadCounters.Outcome.PERMITTED);
    }

    @Test
    void should_beEqual_when_sameNameAndOutcome() {
        var first = new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.REJECTED);
        var second = new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.REJECTED);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_notBeEqual_when_outcomeDiffers() {
        var permitted = new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.PERMITTED);
        var rejected = new BulkheadCounters.Call("myBulkhead", BulkheadCounters.Outcome.REJECTED);

        assertThat(permitted).isNotEqualTo(rejected);
    }

    @Test
    // Documents current behavior: the record performs no validation, so a null name is accepted
    // rather than rejected at construction.
    void should_allowNullName_when_nameNotProvided() {
        var call = new BulkheadCounters.Call(null, BulkheadCounters.Outcome.PERMITTED);

        assertThat(call.name()).isNull();
    }
}
