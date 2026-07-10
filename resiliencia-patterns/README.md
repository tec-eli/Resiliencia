# resiliencia-patterns

The five resilience patterns: Retry, Timeout, CircuitBreaker, Bulkhead, RateLimiter.

Each pattern is a standalone `Resilient<T>` implementation, built on virtual threads, usable on its own or
composed via `resiliencia-compose`. This module depends only on `resiliencia-core`.

## Scope

- `Retry` — fixed, exponential, and linear backoff strategies; deadline handling (hard stop: deadline
  re-checked immediately after sleep, before the next attempt is launched).
- `Timeout` — bounds execution via virtual thread cancellation.
- `CircuitBreaker` — rate-based thresholds (fractions, e.g. `0.5` = 50%), not count-based; sliding window
  state machine (Closed/Open/Half-Open).
- `Bulkhead` — concurrency limiting via permits.
- `RateLimiter` — window-based request throttling.
- Fluent builder per pattern producing immutable, reusable instances (`XxxPattern.create()...`).
- Typed events per pattern (e.g. `RetryEvent`) for observability, consumed by `resiliencia-metrics` and
  user listeners.
- Unchecked exceptions per pattern, all extending `ResilientException`.

## Non-goals

- No composition/ordering logic (`Policy`, order validation) — see `resiliencia-compose`.
- No metrics aggregation or backend integration — see `resiliencia-metrics`.
- No framework-specific wiring (Spring/Quarkus/Micronaut annotations) — see the respective integration
  modules.
- No domain-specific behavior (e.g. LLM provider retry semantics) — belongs in consumer code.
