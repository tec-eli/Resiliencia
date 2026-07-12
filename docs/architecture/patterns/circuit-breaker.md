# Spec — CircuitBreaker

Monitors call outcomes and opens the circuit when failure or slow-call rates exceed configured thresholds. Prevents
calls to a degraded downstream service from propagating load.

---

## Behavior

The CircuitBreaker maintains a sliding window of the last N call outcomes. After each call it recalculates the failure
rate and the slow-call rate. If either rate exceeds its threshold, the circuit opens.

When open, all calls are rejected immediately without executing the operation. After a configured wait, the circuit
moves to half-open and allows a small number of test calls through. If those succeed, the circuit closes. If any fail,
it opens again.

A name is required. It appears in events and exceptions to identify which breaker fired.

### State machine

Three states:

- **Closed** — normal operation. All calls go through. Outcomes are recorded.
- **Open** — all calls rejected immediately. No operation is executed.
- **HalfOpen** — a limited number of test calls are permitted. Outcomes determine the next transition.

Transitions:

- `Closed → Open` when failure rate or slow-call rate exceeds its threshold, evaluated after the sliding window is full
- `Open → HalfOpen` after `waitDurationInOpenState` elapses
- `HalfOpen → Closed` when all permitted test calls succeed
- `HalfOpen → Open` when any permitted test call fails

The current state is inspectable at any time. The `Open` state carries the timestamp it was entered and the remaining
wait duration.

### HalfOpen admission under concurrent bursts

`permittedCallsInHalfOpenState` caps admission for calls that are evaluated *while the circuit is observed to be
HalfOpen*. It is not a hard cap on a logical burst of concurrent calls: once enough admitted test calls succeed to
close the circuit, `HalfOpen → Closed` happens immediately, and Closed admits unconditionally by design. Other calls
from the very same burst that make their first admission check only after that transition are evaluated against the
new Closed state, not the HalfOpen episode they raced against, and are admitted like any other Closed call.

Under heavy concurrent contention with fast operations, this can let more calls through than
`permittedCallsInHalfOpenState` for a single HalfOpen episode. This is an inherent trade-off of a lock-free admission
check with no synchronization barrier between callers: bounding it exactly would require callers to register intent
before the breaker decides to close, which is not part of the calling contract (same limitation other lock-free
circuit breaker implementations have). In practice this only matters for calls racing the exact HalfOpen-to-Closed
transition instant — normal traffic is spread out enough in time that the boundary is never contended this way.

The `HalfOpen → Open` direction races the same way, mirrored. Several admitted trial calls can be in flight at once
(up to `permittedCallsInHalfOpenState`, or slightly more per the admission race above), and any one of them failing
must reopen the circuit. Resolution is first-CAS-wins on the shared HalfOpen state: the first trial call whose
outcome successfully swaps it for a new Open state is the failure that "wins" — its reason is what the `Opened`
event reports, and its instant becomes `openedAt`. This is a race to *record* an outcome, not a race to *start* a
call, so the winning failure is not necessarily the first trial call that was admitted.

The other admitted trial calls are never aborted: the breaker has no cancellation mechanism for a call it has
already let through, so every admitted trial call always runs to completion regardless of what the state does in
the meantime. Each one is still recorded into the sliding window and reported via `CallRecorded` — that bookkeeping
happens unconditionally, before the current state is even consulted. What changes is only what happens *after* the
recording: once the shared HalfOpen state has already been swapped out by the winning failure, any other trial
call's own attempt to act on its outcome against that same, now-stale state fails its compare-and-swap and is a
no-op — whether that other outcome was itself a failure (no second `Opened` event, `openedAt` is not pushed later by
a straggler) or a success (it does not spuriously count toward closing a circuit that is already Open). Exactly one
`Opened` event is emitted per HalfOpen episode, no matter how many admitted trial calls fail.

This is the same single-permit-per-trial admission model as the Closed race, mirrored rather than contradicted:
calls that make their first admission check only after the flip to Open see Open and are rejected (or start
waiting out `waitDurationInOpenState`), instead of being incorrectly granted a HalfOpen permit against a budget
that has already been decided. Where the Closed side is permissive by design (post-transition calls are admitted
unconditionally), the Open side is restrictive by design (post-transition calls are rejected outright) — both sides
are just the current state, freshly re-checked at admission time, doing what it always does.

### Rate thresholds

Both thresholds are expressed as fractions between 0.0 and 1.0. A value of `0.5` means 50%.

- **Failure rate** — fraction of calls that threw a recorded exception
- **Slow-call rate** — fraction of calls that exceeded `slowCallDurationThreshold`

Thresholds are only evaluated once the sliding window has accumulated enough calls. Before that, the circuit stays
closed regardless of outcomes.

### Exception filtering

`recordOn` defines which exception types count as failures. `ignoreOn` defines which are never recorded. `ignoreOn`
takes precedence — an exception matched by both is ignored.

A result predicate (`recordOnResult`) can also record a failure based on the return value, even if no exception was
thrown.

`Error` is never matched by `recordOn`/`ignoreOn` — it isn't a business failure. A `java.lang.Error` signals a
condition the JVM itself may not recover from, so it is never wrapped into `Outcome.Failure` or evaluated against
these filters; it always propagates uncaught on the calling thread (library-wide convention, see "Error handling" in
`docs/architecture/ARCHITECTURE.md`). In HalfOpen, an `Error` still resolves the consumed test-call permit (counted
as a failed test call) before propagating, so it cannot leak the HalfOpen budget.

---

## Configuration surface

| Property                        | Required | Description                                                    |
|---------------------------------|----------|----------------------------------------------------------------|
| `name`                          | yes      | Identifier used in events and exceptions (instance-specific)   |
| `failureRateThreshold`          | no       | Fraction of failures that triggers open. Default: 0.5          |
| `slowCallRateThreshold`         | no       | Fraction of slow calls that triggers open. Default: 1.0        |
| `slowCallDurationThreshold`     | no       | What counts as a slow call. Default: no limit                  |
| `slidingWindowSize`             | no       | Number of calls evaluated for rate calculation. Default: 10    |
| `waitDurationInOpenState`       | no       | Time in Open before transitioning to HalfOpen. Default: 60s    |
| `permittedCallsInHalfOpenState` | no       | Test calls allowed in HalfOpen. Default: 3                     |
| `recordOn`                      | no       | Exception types that count as failures. Default: any Exception |
| `ignoreOn`                      | no       | Exception types that are not recorded                          |
| `recordOnResult`                | no       | Predicate — record a failure based on return value             |
| `withListener()`                | no       | Subscribe to circuit events (e.g. Opened, Closed, CallRecorded)|
| `withClock()`                   | no       | Use custom Clock instead of system (mainly for testing)        |
| `state()`                       | inspect  | Get current state (Closed/Open/HalfOpen) with remaining wait   |

---

## Key Concepts

### `name` vs `patternName()`

- **`name`**: Instance-specific identifier (e.g., `"payment-service-cb"`, `"auth-retry-timeout"`). Appears in events and exceptions. Set at construction and must be unique per instance.
- **`patternName()`**: Type identifier, always `"circuit-breaker"`. Used for observability/telemetry when grouping by pattern type, independent of instance.

---

## Events

- **Opened** — circuit transitioned to Open. Carries: name, reason.
- **Closed** — circuit transitioned to Closed. Carries: name, number of successful test calls.
- **HalfOpened** — circuit transitioned to HalfOpen. Carries: name.
- **CallRecorded** — a call outcome was recorded. Carries: name, success/failure, elapsed time, current failure rate.
- **Rejected** — a call was rejected without executing, because the circuit was Open or HalfOpen with no test-call
  permits left. Carries: name, phase (`OPEN` or `HALF_OPEN`). Not emitted for a call that proceeds to a HalfOpen test
  call after the wait duration elapses.

---

## Failure

Throws `CircuitBreakerOpenException` when a call is rejected because the circuit is open. Fields: name, open since,
remaining wait.
