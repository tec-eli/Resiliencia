package io.github.teceli.resiliencia.patterns.bulkhead;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link BulkheadFullException} constructor validation.
 */
class BulkheadFullExceptionTest {

    @Test
    void should_throwNullPointerException_when_nameIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new BulkheadFullException(null, 1, Duration.ZERO))
                .withMessageContaining("name");
    }

    @Test
    void should_throwNullPointerException_when_maxWaitIsNull() {
        assertThatNullPointerException()
                .isThrownBy(() -> new BulkheadFullException("bulkhead", 1, null))
                .withMessageContaining("maxWait");
    }

    @Test
    void should_buildException_when_allArgumentsAreValid() {
        var exception = new BulkheadFullException("bulkhead", 3, Duration.ofMillis(50));

        assertThat(exception.name()).isEqualTo("bulkhead");
        assertThat(exception.maxConcurrentCalls()).isEqualTo(3);
        assertThat(exception.maxWait()).isEqualTo(Duration.ofMillis(50));
    }
}
