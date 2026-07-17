package io.github.teceli.resiliencia.core.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the default {@link Resilient#callAsync} implementation: virtual-thread
 * execution and interruption-based cancellation.
 */
class ResilientCallAsyncTest {

    @Test
    void should_completeWithValue_when_operationSucceeds() throws Exception {
        var future = passthrough().callAsync(() -> "done");

        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("done");
    }

    @Test
    void should_completeExceptionally_when_operationFails() {
        var boom = new IllegalStateException("boom");

        var future = passthrough().callAsync(() -> {
            throw boom;
        });

        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .withCause(boom);
    }

    @Test
    void should_runOnAnotherVirtualThread_when_calledAsync() throws Exception {
        var caller = Thread.currentThread();
        var workerThread = new AtomicReference<Thread>();

        passthrough().callAsync(() -> {
            workerThread.set(Thread.currentThread());
            return "done";
        }).get(5, TimeUnit.SECONDS);

        assertThat(workerThread.get()).isNotSameAs(caller);
        assertThat(workerThread.get().isVirtual()).isTrue();
    }

    @Test
    void should_interruptOperation_when_futureCancelled() throws Exception {
        var operationStarted = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);

        var future = passthrough().callAsync(() -> {
            operationStarted.countDown();
            try {
                Thread.sleep(30_000);
                return "never";
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new ResilientException("interrupted", e);
            }
        });
        assertThat(operationStarted.await(5, TimeUnit.SECONDS)).isTrue();

        future.cancel(true);

        assertThat(interrupted.await(5, TimeUnit.SECONDS))
                .as("cancelling the future should interrupt the worker thread")
                .isTrue();
        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void should_throwNullPointerException_when_operationIsNull() {
        assertThatNullPointerException().isThrownBy(() -> passthrough().callAsync(null));
    }

    @Test
    @Execution(ExecutionMode.SAME_THREAD)
    void should_completeExceptionallyAndRethrowOnWorkerThread_when_operationThrowsError() throws Exception {
        var boom = new OutOfMemoryError("simulated");
        var originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        var uncaught = new CountDownLatch(1);
        var uncaughtThrowable = new AtomicReference<Throwable>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            uncaughtThrowable.set(e);
            uncaught.countDown();
        });

        try {
            var future = passthrough().callAsync(() -> {
                throw boom;
            });

            assertThatExceptionOfType(ExecutionException.class)
                    .isThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .withCause(boom);

            assertThat(uncaught.await(5, TimeUnit.SECONDS))
                    .as("the Error must still propagate uncaught on the worker thread, not just complete "
                            + "the future — it is never treated as a recoverable business outcome")
                    .isTrue();
            assertThat(uncaughtThrowable.get()).isSameAs(boom);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        }
    }

    /**
     * Minimal Resilient implementation so the interface's default callAsync is what's under test.
     */
    private static Resilient<String> passthrough() {
        return new Resilient<>() {
            @Override
            public String call(Operation<String> operation) throws ResilientException {
                return operation.execute();
            }

            @Override
            public Outcome<String> outcome(Operation<String> operation) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
