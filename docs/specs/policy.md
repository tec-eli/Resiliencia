# Spec — Policy

Composes multiple patterns into a single execution chain. A Policy is itself a Resilient — it is interchangeable with
any single pattern anywhere a Resilient is expected.

---

## Behavior

Patterns are declared in an explicit order. That order is the execution order, from outermost to innermost, wrapping
the actual operation at the center. Each pattern in the chain delegates to the next.

Example with three patterns — CircuitBreaker, Retry, Timeout:

```
CircuitBreaker → Retry → Timeout → operation
```

The CircuitBreaker sees the entire retry+timeout call. The Retry sees each individual timed attempt. The Timeout bounds
each single attempt.

### Order validation

Order matters semantically. Two ordering pitfalls are known, and they are treated differently on purpose:

| Ordering | Problem | Enforcement |
|---|---|---|
| Retry wrapping CircuitBreaker | The retry loop keeps burning retry budget against an already-open circuit, making zero real calls. No legitimate use case. | Rejected at construction — throws `InvalidPolicyException` |
| Timeout wrapping Retry | Correct order for *per-attempt* timeout, which is what the library implements today. Only a footgun if the user actually wants an *overall/total deadline* across the whole retry loop — a concept the library does not model yet. | Logged as `WARN` via SLF4J; construction proceeds |

The Retry/CircuitBreaker check is **transitive**: when `and(pattern)` is called, `Policy` checks the newly added
pattern's kind against *all* patterns already in the chain, not just the immediately preceding one — Bulkhead and/or
RateLimiter can legitimately sit between CircuitBreaker and Retry in the full recommended order below.

Pattern identity for this check comes from `Resilient<T>.patternKind()` (see `core.md`) — a closed `PatternKind`
enum, not `instanceof` (fragile against future decorators/wrappers) and not the observability-facing
`patternName(): String` (typo-prone, no compiler safety).

Both `Policy.compose(x).and(y)...` and `Policy.useDefault(...)` go through the same guardrail. `useDefault()` is a
second entry point onto the same `Policy` type — not a separate builder — and is not a silent reordering mechanism:
`Policy` never reorders a user-supplied chain.

### Recommended order

```
RateLimiter → CircuitBreaker → Bulkhead → Retry → Timeout
```

- **RateLimiter outermost** — rejects excess load before any other pattern spends work on it
- **CircuitBreaker before Bulkhead and Retry** — an open circuit should short-circuit before a permit is reserved or a retry loop starts
- **Bulkhead before Retry** — one permit is held for the whole retry loop, not re-acquired per attempt
- **Retry before Timeout** — Timeout is per-attempt, so it must sit on the innermost layer to apply to each attempt individually

`Policy.useDefault(...)` applies this order without requiring the user to chain `.and()` manually. It produces the
same `Policy` type as explicit composition — this is a shortcut, not a new builder.

---

## Configuration surface

| Property | Required | Description |
|---|---|---|
| patterns | yes | Ordered list of patterns, outermost first |

A Policy with a single pattern is valid. A Policy with zero patterns is a construction error.

---

## Failure

`InvalidPolicyException` is thrown at construction time when:
- the pattern list is empty, or
- Retry wraps CircuitBreaker anywhere in the chain (transitive check).

Fields: problem description, suggested fix.

At call time, Policy propagates whichever exception the innermost failing pattern throws. No new exception type is
introduced by Policy itself for call-time failures.

---

## Design rationale

**Explicit container, not nested decorators or a functional pipeline.** `Policy` as a first-class, inspectable,
injectable object reads better than `circuitBreaker.wrap(retry.wrap(timeout))` (which reads inside-out) and doesn't
require functional-programming familiarity the way a pure `Function`-composition pipeline would. Both alternatives
were considered and rejected for readability and DI-compatibility reasons.

**Asymmetric guardrail, by design.** A single validation strategy (warn-only, or reject-only) doesn't fit both known
pitfalls equally, because they aren't equally bad:

- WARN-only for both pairs was rejected — it would leave the pairing with *zero* legitimate use case
  (Retry/CircuitBreaker) unguarded at construction time, relying entirely on someone noticing a log line.
- `InvalidPolicyException` for both pairs was rejected — Timeout wrapping Retry is a valid, commonly-needed
  configuration (per-attempt timeout); blocking it would reject correct usage to guard against a misunderstanding
  that doesn't apply to every user.

So: hard failure for the pairing with no legitimate use, a warning for the pairing that's only sometimes a mistake.

### Open

- The "overall/total deadline across the retry loop" concept isn't designed yet. Once it exists, the Timeout/Retry
  WARN may be replaced with a real check (e.g. warn only if no overall-deadline construct is configured).
- The known-bad-pair table is two hardcoded pairs today. Extending it to Bulkhead/RateLimiter interactions (e.g.
  "RateLimiter never inside Retry") requires adding `PatternKind` values and touching `Policy.and()` again — expected
  as those patterns get implemented, not a design flaw today.
