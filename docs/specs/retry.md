# Spec — Retry

Retries a failed operation a configurable number of times with optional waiting between attempts.

---

## Behavior

On each failure, Retry checks `shouldRetry` — a single `Predicate<Throwable>` — to decide whether the exception is
eligible for retry. If it is, and attempts remains, it waits (the delay grows by `backoffMultiplier` after each
attempt) and tries again. Two optional backoff modifiers harden this against real-world failure storms:
`jitterFactor` shifts each delay uniformly within `[delay * (1 - f), delay * (1 + f)]` so clients that failed
together don't retry together (thundering herd), and `maxDelayMs` clamps every delay — including the initial one,
and after jitter is applied — preventing unbounded exponential growth. Neither modifier is validated against the
other: an initial delay above the cap is clamped, not rejected. If the operation succeeds on any attempt, the result is returned normally. If all attempts
are exhausted, a `RetryExhaustedException` is thrown carrying the total attempt count and the last exception.

### Exception classification: Transient vs. Permanent failures

**By default**, Retry only retries on `IOException` and its subclasses, which are assumed to be transient:
network timeouts, connection resets, DNS failures, and similar infrastructure faults. Other exception types
(e.g., `RuntimeException`, `NullPointerException`) are assumed to be permanent (logic errors, bugs) and are not retried.

This classification reduces wasted retry attempts: retrying a permanent failure (e.g., invalid argument) has no
chance of success and delays failure reporting. For operations where non-IOExceptions are transient (e.g., a custom
`TemporaryServiceException`), override the default predicate via `withShouldRetry()`.

Retry is configured via `Retry.<T>create()` followed by `withX` copy methods (each returns a new, independently
usable `Retry` instance rather than mutating the receiver):

```java
var retry = Retry.<String>create()
        .withMaxAttempts(3)
        .withInitialDelay(100)
        .withBackoffMultiplier(2.0);
        // Uses default: retries only IOException

var customRetry = Retry.<String>create()
        .withMaxAttempts(3)
        .withShouldRetry(e -> e instanceof IOException || e instanceof CustomTemporaryException);
```

There is no result-based retry (retrying because the returned value matches a predicate) and no separate
`ignoreOn`/`retryOn` exception-type lists — both are expressed through the single `shouldRetry` predicate.

---

## Configuration surface

| Property | Required | Description | Default |
|---|---|---|---|
| `maxAttempts` | no | Total attempts including the first call. Must be >= 1 | 3 |
| `initialDelayMs` | no | Wait before the first retry, in milliseconds. Must be >= 0 | 100 |
| `backoffMultiplier` | no | Factor the delay is multiplied by after each attempt. Must be >= 1.0 | 2.0 |
| `maxDelayMs` | no | Hard cap on every backoff delay, applied after jitter. Must be >= 0 | uncapped |
| `jitterFactor` | no | Uniform randomization of each delay within `±factor`. Must be in [0.0, 1.0] | 0.0 (off) |
| `shouldRetry` | no | Predicate — retry only if it returns true for the thrown exception | `e -> e instanceof IOException` |
| `listeners` | no | `ResilienceEvent.Listener` instances notified of each `RetryEvent` | none |

---

## Events

Retry emits a `RetryEvent` after each significant moment:

- **AttemptFailed** — a call was made and failed. Carries: timestamp, attempt number, the thrown exception.
- **Success** — a call completed successfully. Carries: timestamp, total attempts taken.
- **Exhausted** — all attempts failed. Carries: timestamp, total attempts, the last exception.

---

## Failure

Throws `RetryExhaustedException` when all attempts are exhausted.

---

## Future work

Named backoff strategies (fixed/exponential/linear as distinct configuration options, rather than a single
multiplier), exception-type filtering (`retryOn`/`ignoreOn` lists), and result-based retry (`retryOnResult`) are not
implemented and not scheduled.
