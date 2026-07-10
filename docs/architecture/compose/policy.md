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

Order matters semantically. Known ordering pitfalls fall into two categories, enforced differently on purpose:

- **No legitimate use case** — the reversed order wastes a scarce or limited resource (a retry budget, a bulkhead
  permit) on a call that a cheap, earlier check would have already rejected. Rejected at construction.
- **Legitimate alternate semantics** — the reversed order is a real, sometimes-needed configuration, just not the
  default recommendation. Logged as a warning, construction proceeds.

| Ordering                         | Problem                                                                                                                                                                                                                                    | Enforcement                                                                                                            |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Retry wrapping CircuitBreaker    | The retry loop keeps burning retry budget against an already-open circuit, making zero real calls. No legitimate use case.                                                                                                                 | Rejected at construction — throws `InvalidPolicyException`                                                             |
| Bulkhead wrapping CircuitBreaker | A permit is reserved before the circuit state is known, wasting bulkhead capacity on a call that fails immediately once the circuit check runs. No legitimate use case.                                                                    | Rejected at construction — throws `InvalidPolicyException`                                                             |
| Bulkhead wrapping RateLimiter    | A permit is reserved before the rate limit is checked, wasting bulkhead capacity on a call that gets rejected once the rate-limit check runs. No legitimate use case.                                                                      | Rejected at construction — throws `InvalidPolicyException`                                                             |
| Timeout wrapping Retry           | Correct order for *per-attempt* timeout, which is what the library implements today. Only a mistake if the user actually wants an *overall/total deadline* across the whole retry loop — see below.                                        | Logged as `WARN` via SLF4J; construction proceeds. Suppressed if Retry has an overall deadline configured (see below). |
| Retry wrapping RateLimiter       | Each attempt is independently subject to the rate limit instead of the whole call being gated once, outermost. Legitimate when the limiter exists to bound the rate of outbound calls per attempt (e.g. an external API's own rate limit). | Logged as `WARN` via SLF4J; construction proceeds                                                                      |
| Retry wrapping Bulkhead          | The permit is re-acquired per attempt instead of held for the whole retry loop. Legitimate when the intent is to avoid monopolizing a permit during backoff waits.                                                                         | Logged as `WARN` via SLF4J; construction proceeds                                                                      |

All rejected pairs are checked **transitively**: when `and(pattern)` is called, `Policy` checks the newly added
pattern's kind against *all* patterns already in the chain, not just the immediately preceding one — other patterns
can legitimately sit between the two that actually conflict in the full optimum order below.

Pattern identity for this check comes from `Resilient<T>.patternKind()` (see `core.md`) — a closed `PatternKind`
enum, not `instanceof` (fragile against future decorators/wrappers) and not the observability-facing
`patternName(): String` (typo-prone, no compiler safety).

**Nested Policy.** A `Policy` is itself a `Resilient` and can be composed into another `Policy` via `.and(...)`.
Because `Policy` reports `PatternKind.CUSTOM` (the `Resilient` default — it never overrides `patternKind()`), a naive
check against a nested `Policy`'s own kind would never see what it actually contains, silently bypassing the
guardrail for exactly the pairings it exists to catch. Instead, `Policy` flattens: before checking a rule, it
recursively expands every pattern already in the chain, and the pattern being added, into the set of `PatternKind`s
they actually contribute — descending into any nested `Policy` — and checks the rule against those flattened sets.
A `Retry` composed around a `Policy` that internally contains a `CircuitBreaker` is rejected exactly as if the
`CircuitBreaker` had been added directly, no matter how many levels of nesting sit in between.

Both `Policy.compose(x).and(y)...` and `Policy.useOptimumOrder(...)` go through the same guardrail.
`useOptimumOrder()` is a second entry point onto the same `Policy` type — not a separate builder — and is not a
silent reordering mechanism: `Policy` never reorders a user-supplied chain.

### Optimum order

```
RateLimiter → CircuitBreaker → Bulkhead → Retry → Timeout
```

- **RateLimiter outermost** — rejects excess load before any other pattern spends work on it
- **CircuitBreaker before Bulkhead and Retry** — an open circuit should short-circuit before a permit is reserved or a
  retry loop starts
- **Bulkhead before Retry** — one permit is held for the whole retry loop, not re-acquired per attempt
- **Retry before Timeout** — Timeout is per-attempt, so it must sit on the innermost layer to apply to each attempt
  individually. **This does not happen automatically with `Retry.create()`'s defaults**: the default `shouldRetry`
  only matches `IOException`, and `Timeout` throws `ResilientTimeoutException`, which isn't one. Retrying a
  per-attempt timeout requires extending `shouldRetry` to cover `ResilientTimeoutException` explicitly (see
  `retry.md`'s "Exception classification" section) — this ordering makes that retry *possible*, it doesn't make it
  happen by itself.

`Policy.useOptimumOrder(...)` applies this order without requiring the user to chain `.and()` manually. It produces
the same `Policy` type as explicit composition — this is a shortcut, not a new builder.

### Overall deadline (Retry)

The "overall/total deadline across the retry loop" concept lives on `Retry` itself, not as a separate pattern and
not as a `Policy`-level construct. The deadline bounds Retry's own loop — it is a property of how long Retry is
willing to keep attempting, the same category of concern as its existing backoff strategy, not a new composable
protection. This keeps `Policy` free of special cases outside the ordered pattern chain.

When an overall deadline is configured on Retry, the Timeout-wrapping-Retry `WARN` (above) does not fire: the user
has explicitly acknowledged the total-duration concern, so the per-attempt-only reading of Timeout is no longer an
oversight to flag. Concretely, `Resilient<T>` exposes `hasOwnDeadline()` (default `false`); `Retry` overrides it to
report whether `withOverallDeadline(...)` has been configured, and `Policy`'s ordering validation checks it before
logging the Timeout-wraps-Retry warning — no `instanceof` needed, the same polymorphic mechanism `patternKind()`
already uses for structural checks.

This does not introduce a new `PatternKind` or touch order validation's structural rules — it's orthogonal to both,
only adding a suppression condition to one existing `WARN` rule. The field itself is specified in `retry.md`.

---

## Configuration surface

| Property | Required | Description                               |
|----------|----------|-------------------------------------------|
| patterns | yes      | Ordered list of patterns, outermost first |

A Policy with a single pattern is valid. A Policy with zero patterns is a construction error.

---

## Failure

`InvalidPolicyException` is thrown at construction time when:

- the pattern list is empty,
- Retry wraps CircuitBreaker anywhere in the chain (transitive check),
- Bulkhead wraps CircuitBreaker anywhere in the chain (transitive check), or
- Bulkhead wraps RateLimiter anywhere in the chain (transitive check).

Fields: problem description, suggested fix.

At call time, Policy propagates whichever exception the innermost failing pattern throws. No new exception type is
introduced by Policy itself for call-time failures.

---

## Design rationale

**Explicit container, not nested decorators or a functional pipeline.** `Policy` as a first-class, inspectable,
injectable object reads better than `circuitBreaker.wrap(retry.wrap(timeout))` (which reads inside-out) and doesn't
require functional-programming familiarity the way a pure `Function`-composition pipeline would. Both alternatives
were considered and rejected for readability and DI-compatibility reasons.

**Asymmetric guardrail, by design.** A single validation strategy (warn-only, or reject-only) doesn't fit every known
pitfall equally, because they aren't equally bad. The dividing line is whether a cheap, rejecting check (CircuitBreaker,
RateLimiter) is being skipped in favor of reserving a scarce resource first (a retry budget, a bulkhead permit):

- WARN-only for every pair was rejected — it would leave the pairings with *zero* legitimate use case
  (Retry/CircuitBreaker, Bulkhead/CircuitBreaker, Bulkhead/RateLimiter) unguarded at construction time, relying
  entirely on someone noticing a log line.
- `InvalidPolicyException` for every pair was rejected — Timeout wrapping Retry, Retry wrapping RateLimiter, and
  Retry wrapping Bulkhead are all valid, sometimes-needed configurations; blocking them would reject correct usage
  to guard against a misunderstanding that doesn't apply to every user.

So: hard failure for pairings with no legitimate use, a warning for pairings that are only sometimes a mistake.

### Open

- No new `PatternKind` values were needed for the Bulkhead/RateLimiter pairs above — they reuse the existing enum.
- All 6 ordering rules in the table above are implemented in `Policy.ORDERING_RULES`, and Retry's overall-deadline
  field (`withOverallDeadline(...)`, `hasOwnDeadline()`) is implemented — see `retry.md`.
- Nested-`Policy` flattening for order validation (see above) is implemented in `Policy.flattenKinds(...)`,
  exercised by `PolicyOrderValidationTest`.