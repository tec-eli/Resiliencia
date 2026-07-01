# Spec — Core types

These types are the foundation of the entire API. Every pattern depends on them.

---

## Resilient

The base contract implemented by all patterns and by Policy. Defines three execution modes that every resilient
operation must support:

- **Blocking execution** — runs on the calling thread. Throws a ResilienciaException subtype on failure.
- **Async execution** — runs asynchronously. Returns a future that completes exceptionally on failure.
- **Outcome execution** — never throws. Returns a sealed Outcome type representing success, failure, or timeout.

Any single pattern and any composed Policy are interchangeable as a Resilient — callers do not need to know which they
hold.

`Resilient<T>` also exposes `PatternKind patternKind()`, defaulting to `CUSTOM`. This is a closed enum
(`RATE_LIMITER, CIRCUIT_BREAKER, BULKHEAD, RETRY, TIMEOUT, CUSTOM`) used internally by `Policy` to validate
composition order at construction time (see `policy.md`). It is distinct from the observability-facing
`ResilienceEvent.patternName(): String` — `patternKind()` exists for compile-time-safe internal control flow, not
for external reporting.

---

## Outcome

A sealed type representing the result of a protected operation. Three variants:

- **Success** — the operation completed normally. Carries the return value.
- **Failure** — the operation threw an exception. Carries the original throwable.
- **TimedOut** — the operation was cancelled because it exceeded the time limit. Carries no value.

The compiler enforces exhaustive handling. All three cases must be addressed.

`TimedOut` is only produced by Timeout and by any Policy containing one.

---

## Exceptions

All exceptions thrown by resiliencia are unchecked and extend a common base type. Each subtype carries structured
context as typed fields — not just a message string.

| Exception | Thrown by | Key fields |
|---|---|---|
| `RetryExhaustedException` | Retry | attempt count, last cause |
| `ResilienciaTimeoutException` | Timeout | configured limit, actual elapsed |
| `CircuitBreakerOpenException` | CircuitBreaker | name, open since, remaining wait |
| `BulkheadFullException` | Bulkhead | name, max concurrent calls |
| `RateLimiterException` | RateLimiter | name, retry after |
| `InvalidPolicyException` | Policy (at construction) | problem description, suggested fix |

The original operation's exception is always reachable, either as a typed field or via the standard cause chain.

A `throws ResilienciaException` clause may still appear on method signatures as documentation — it costs the caller
nothing, since no `catch` or further `throws` is required for an unchecked type.

---

## Design rationale

**Unchecked exceptions.** Checked exceptions are not compatible with `Supplier`, `Callable`, and other functional
interfaces without wrappers, and would pollute lambda signatures throughout the API. Unchecked exceptions keep method
signatures clean and match the direction of modern Java libraries (Spring, Micronaut). The trade-off — the compiler no
longer reminds callers to handle failures — is mitigated three ways: `outcome()` as an alternative that never throws,
clear Javadoc on what each method can throw, and the event system surfacing failures even when nothing is caught.

A pure Try/Either-style result type was considered as the *only* mechanism and rejected — `outcome()` provides that
style for users who want it, without forcing it on users who prefer traditional exception handling.
