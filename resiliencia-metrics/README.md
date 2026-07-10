# resiliencia-metrics

Backend-neutral metrics interfaces derived from the pattern event system: state snapshots, counters,
aggregates. This module defines the contract; it does not ship a metrics backend itself.

Depends on `resiliencia-core` and `resiliencia-patterns` (consumes typed pattern events).

## Scope

- Neutral interfaces for exposing pattern state as metrics (e.g. CircuitBreaker state snapshot, Retry
  attempt counters, Bulkhead permit gauges, RateLimiter window counters).
- Aggregation logic derived purely from typed events (`RetryEvent`, `PolicyValidationWarning`, etc.) — no
  polling, no reflection into pattern internals.
- Serves as the foundation `resiliencia-micrometer` and `resiliencia-opentelemetry` implement against.

## Non-goals

- No concrete backend integration (Micrometer, OpenTelemetry, Prometheus, etc.) — those are separate
  modules that depend on this one.
- No logging — logging concerns are handled independently via `System.Logger` in lower modules; this
  module is metrics-only.
- Does not implement `Policy` or pattern logic — read-only observer of events produced elsewhere.
