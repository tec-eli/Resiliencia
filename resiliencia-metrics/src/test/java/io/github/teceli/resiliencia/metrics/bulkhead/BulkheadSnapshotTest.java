package io.github.teceli.resiliencia.metrics.bulkhead;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkheadSnapshotTest {

    @Test
    void should_exposeNameAndCount_when_activeCallsConstructed() {
        var activeCalls = new BulkheadSnapshot.ActiveCalls("myBulkhead", 4);

        assertThat(activeCalls.name()).isEqualTo("myBulkhead");
        assertThat(activeCalls.count()).isEqualTo(4);
    }

    @Test
    void should_beEqual_when_sameNameAndCount() {
        var first = new BulkheadSnapshot.ActiveCalls("myBulkhead", 4);
        var second = new BulkheadSnapshot.ActiveCalls("myBulkhead", 4);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void should_notBeEqual_when_countDiffers() {
        var withThree = new BulkheadSnapshot.ActiveCalls("myBulkhead", 3);
        var withFour = new BulkheadSnapshot.ActiveCalls("myBulkhead", 4);

        assertThat(withThree).isNotEqualTo(withFour);
    }

    @Test
    void should_allowZeroCount_when_noActiveCalls() {
        var activeCalls = new BulkheadSnapshot.ActiveCalls("myBulkhead", 0);

        assertThat(activeCalls.count()).isZero();
    }

    @Test
    // Documents current behavior: the record performs no validation, so a negative count — which
    // cannot occur from real Bulkhead usage — is still accepted rather than rejected.
    void should_allowNegativeCount_when_valueIsInvalidForARealBulkhead() {
        var activeCalls = new BulkheadSnapshot.ActiveCalls("myBulkhead", -1);

        assertThat(activeCalls.count()).isEqualTo(-1);
    }

    @Test
    // Documents current behavior: the record performs no validation, so a null name is accepted
    // rather than rejected at construction.
    void should_allowNullName_when_nameNotProvided() {
        var activeCalls = new BulkheadSnapshot.ActiveCalls(null, 4);

        assertThat(activeCalls.name()).isNull();
    }
}
