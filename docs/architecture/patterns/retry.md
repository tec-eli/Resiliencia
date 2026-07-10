# Spec — Retry

Retries a failed operation a configurable number of times with optional waiting between attempts.

---

## Behavior

On each failure, Retry checks `shouldRetry` — a single `Predicate<Throwable>` — to decide whether the exception is
eligible for retry. This check runs **before** the attempt count and deadline are checked, on every attempt including
the last one: a non-retryable exception always yields `RetryRejectedException`, even if it happens to occur on the
final attempt (where it would otherwise look like exhaustion). If the exception is retryable, and attempts remain, it
waits (the delay grows by `backoffMultiplier` after each attempt) and tries again. Two optional backoff modifiers
harden this against real-world failure storms: `jitterFactor` shifts each delay uniformly within
`[delay * (1 - f), delay * (1 + f)]` so clients that failed together don't retry together (thundering herd), and
`maxDelayMs` clamps every delay — including the initial one, and after jitter is applied — preventing unbounded
exponential growth. Neither modifier is validated against the other: an initial delay above the cap is clamped, not
rejected. If the operation succeeds on any attempt, the result is returned normally. If all attempts (or an overall
deadline, see below) are exhausted, a `RetryExhaustedException` is thrown carrying the total attempt count and the
last exception. If `shouldRetry` declines to retry a failure before that point, a distinct `RetryRejectedException`
is thrown instead. If the thread is interrupted while waiting for a backoff delay, a third distinct
`RetryInterruptedException` is thrown instead of either — see "Failure" below.

### Exception classification: Transient vs. Permanent failures

**By default**, Retry only retries on `IOException` and its subclasses, which are assumed to be transient:
network timeouts, connection resets, DNS failures, and similar infrastructure faults. Other exception types
(e.g., `RuntimeException`, `NullPointerException`) are assumed to be permanent (logic errors, bugs) and are not retried.

This classification reduces wasted retry attempts: retrying a permanent failure (e.g., invalid argument) has no
chance of success and delays failure reporting. For operations where non-IOExceptions are transient (e.g., a custom
`TemporaryServiceException`), override the default predicate via `withShouldRetry()`.

**This also applies when Retry is composed with other patterns.** Composing Retry outermost of Timeout, Bulkhead, or
RateLimiter (see `policy.md`'s "Optimum order") does **not** automatically retry those patterns' own exceptions
(`ResilientTimeoutException`, `BulkheadFullException`, `RateLimiterException`) — none of them are `IOException`
subclasses. If the composition is meant to retry a per-attempt timeout, a rejected permit, or a rate-limit rejection,
`shouldRetry` must be extended explicitly to cover the relevant exception type(s); the default is deliberately
narrow and does not widen itself based on what it's composed with.

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

### Overall deadline

`overallDeadlineMs` (configured via `withOverallDeadline(ms)`) bounds the total wall-clock time the retry loop is
willing to spend across all attempts and backoff waits, measured from the first attempt. It is checked only
**between** attempts — it never preempts an attempt already in progress, which stays a per-attempt `Timeout`'s
responsibility (see `policy.md`'s "Overall deadline" section for why this is a `Retry`-owned field rather than a
`Policy`-level construct). Once the deadline has passed at a point where Retry would otherwise start a new attempt,
the loop stops exactly as if the attempt budget were exhausted: it emits `RetryEvent.Exhausted` and `call()` throws
`RetryExhaustedException`, even if `maxAttempts` has not been reached. Disabled (uncapped) by default.

When configured, `Retry.hasOwnDeadline()` returns `true`, which suppresses `Policy`'s Timeout-wraps-Retry ordering
warning — composing an outer `Timeout` around a `Retry` with its own overall deadline is no longer read as a
possible oversight, since the total-duration concern has already been addressed on `Retry` itself.

### Interruption during backoff

If the thread is interrupted while sleeping between attempts, the retry loop stops immediately: it does not treat
this as the attempt budget running out, since it wasn't. The thread's interrupt status is restored (so callers doing
their own interruption-aware work downstream still observe it), `RetryEvent.Interrupted` is emitted, and `call()`
throws `RetryInterruptedException` carrying the total attempt count and the **last real failure** — the exception
from the attempt that was about to be retried when the interrupt arrived, not a wrapper around the interrupt itself.
This keeps the original failure reachable via `getCause()` the same way the other two Retry exceptions do, while
still letting callers distinguish "interrupted" (e.g. graceful shutdown, cancellation) from both "ran out of budget"
(`RetryExhaustedException`) and "this failure was never eligible for retry" (`RetryRejectedException`).

---

## Configuration surface

| Property            | Required | Description                                                                                           | Default                         |
|---------------------|----------|-------------------------------------------------------------------------------------------------------|---------------------------------|
| `maxAttempts`       | no       | Total attempts including the first call. Must be >= 1                                                 | 3                               |
| `initialDelayMs`    | no       | Wait before the first retry, in milliseconds. Must be >= 0                                            | 100                             |
| `backoffMultiplier` | no       | Factor the delay is multiplied by after each attempt. Must be >= 1.0                                  | 2.0                             |
| `maxDelayMs`        | no       | Hard cap on every backoff delay, applied after jitter. Must be >= 0                                   | uncapped                        |
| `jitterFactor`      | no       | Uniform randomization of each delay within `±factor`. Must be in [0.0, 1.0]                           | 0.0 (off)                       |
| `overallDeadlineMs` | no       | Total wall-clock budget across all attempts and backoff waits, checked between attempts. Must be >= 0 | uncapped                        |
| `shouldRetry`       | no       | Predicate — retry only if it returns true for the thrown exception                                    | `e -> e instanceof IOException` |
| `listeners`         | no       | `ResilienceEvent.Listener` instances notified of each `RetryEvent`                                    | none                            |

---

## Events

Retry emits a `RetryEvent` after each significant moment:

- **AttemptFailed** — a call was made and failed. Carries: timestamp, attempt number, the thrown exception.
- **Success** — a call completed successfully. Carries: timestamp, total attempts taken.
- **Exhausted** — all attempts failed, whether because `maxAttempts` was reached or the overall deadline elapsed.
  Carries: timestamp, total attempts, the last exception.
- **Rejected** — `shouldRetry` declined to retry a failure before the attempt budget (count or deadline) was
  exhausted. Carries: timestamp, attempt number, the rejected exception.
- **Interrupted** — the thread was interrupted while waiting for a backoff delay. Carries: timestamp, attempt
  number, the last real failure (not the interrupt itself).

---

## Failure

Throws `RetryExhaustedException` when all attempts (or the overall deadline) are exhausted. Throws
`RetryRejectedException` when `shouldRetry` declines to retry a failure before that point. Throws
`RetryInterruptedException` when the thread is interrupted while waiting for a backoff delay, before either of the
other two conditions is reached. Three distinct types so callers can tell "genuinely ran out of budget" apart from
"this failure was never eligible for retry" apart from "something interrupted the wait, unrelated to the failure
itself." All three carry the attempt count and the relevant cause via `getCause()`.

---

## Future work

Named backoff strategies (fixed/exponential/linear as distinct configuration options, rather than a single
multiplier), exception-type filtering (`retryOn`/`ignoreOn` lists), and result-based retry (`retryOnResult`) are not
implemented and not scheduled.
