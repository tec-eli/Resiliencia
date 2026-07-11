# resiliencia-compose

`Policy` — explicit, user-ordered composition of resilience patterns into a single `Resilient<T>`.

This module depends **only** on `resiliencia-core` (specifically `Resilient<T>`), never on
`resiliencia-patterns`. Composition is structural: `Policy` wraps whatever `Resilient<T>` instances it's
given, regardless of which module produced them.

## Scope

- `Policy` — immutable, fluent composition container; user defines wrap order explicitly.
- Order validation at construction time, checked transitively against every pattern already in the chain:
  - **Confirmed broken orders** → `InvalidPolicyException` thrown immediately (e.g. Retry wrapping
    CircuitBreaker, Bulkhead wrapping CircuitBreaker, Bulkhead wrapping RateLimiter).
  - **Questionable-but-valid orders** → logged via SLF4J at `WARN`, construction proceeds (e.g. Timeout
    wrapping Retry, Retry wrapping RateLimiter, Retry wrapping Bulkhead).
- `useOptimumOrder(...)` — factory accepting patterns in any order, applies the recommended order internally
  (RateLimiter → CircuitBreaker → Bulkhead → Retry → Timeout, outermost to innermost); bypasses the
  guardrail by design.
- No global registry — every `Policy` instance is independent and explicitly constructed.

## Non-goals

- Does not implement any pattern itself (no Retry/Timeout/etc. logic) — see `resiliencia-patterns`.
- Does not depend on `resiliencia-patterns` — validation reasons about pattern *identity*
  (`PatternKind`), not concrete pattern classes.
- No metrics or framework wiring.

See `docs/architecture/compose/policy.md` for the full order-validation rule set and rationale.
