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

`Error` is never matched by `recordOn`/`ignoreOn` — it isn't a business failure. See "Error handling" in
`docs/ARCHITECTURE.md` for the library-wide convention. In HalfOpen, an `Error` still resolves the consumed test-call
permit (counted as a failed test call) before propagating, so it cannot leak the HalfOpen budget.

---

## Configuration surface

| Property | Required | Description |
|---|---|---|
| `name` | yes | Identifier used in events and exceptions |
| `failureRateThreshold` | no | Fraction of failures that triggers open. Default: 0.5 |
| `slowCallRateThreshold` | no | Fraction of slow calls that triggers open. Default: 1.0 |
| `slowCallDurationThreshold` | no | What counts as a slow call. Default: no limit |
| `slidingWindow` | no | Number of calls evaluated for rate calculation. Default: 10 |
| `waitDurationInOpenState` | no | Time in Open before transitioning to HalfOpen. Default: 60s |
| `permittedCallsInHalfOpenState` | no | Test calls allowed in HalfOpen. Default: 3 |
| `recordOn` | no | Exception types that count as failures. Default: any Exception |
| `ignoreOn` | no | Exception types that are not recorded |
| `recordOnResult` | no | Predicate — record a failure based on return value |

---

## Events

- **Opened** — circuit transitioned to Open. Carries: name, reason.
- **Closed** — circuit transitioned to Closed. Carries: name, number of successful test calls.
- **HalfOpened** — circuit transitioned to HalfOpen. Carries: name.
- **CallRecorded** — a call outcome was recorded. Carries: name, success/failure, elapsed time, current failure rate.

---

## Failure

Throws `CircuitBreakerOpenException` when a call is rejected because the circuit is open. Fields: name, open since, 
remaining wait.
