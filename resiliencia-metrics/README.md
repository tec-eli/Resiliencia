# resiliencia-metrics

Backend-neutral metrics interfaces derived from the pattern and Policy event system: state snapshots,
counters. This module defines the contract; it does not ship a metrics backend itself.

Full design: `docs/architecture/metrics/metrics.md`.

Depends on `resiliencia-core`, `resiliencia-patterns`, **and** `resiliencia-compose` — the last one
specifically so `ResilienceMetricsListener` can reference `PolicyValidationWarning`, which is defined in
`compose` (that's where Policy's order-validation logic actually lives).

## Scope

- `ResilienceMetrics`: a small backend interface (`observe(Snapshot)`, `observe(Counters)`) that
  `resiliencia-micrometer`/`resiliencia-opentelemetry` implement.
- `ResilienceMetricsListener`: translates typed events (`RetryEvent`, `TimeoutEvent`, `CircuitBreakerEvent`,
  `BulkheadEvent`, `RateLimiterEvent`, `PolicyValidationWarning`) into `Snapshot`/`Counters` emissions — no
  polling, no reflection into pattern internals; every value mirrors what the source event already carried.
- Serves as the foundation `resiliencia-micrometer` and `resiliencia-opentelemetry` implement against.

## Non-goals

- No concrete backend integration (Micrometer, OpenTelemetry, Prometheus, etc.) — those are separate
  modules that depend on this one.
- No independent aggregation/windowing state — gauges mirror the pattern's own already-computed values,
  never a second, independently-tracked window (see `metrics.md`'s "Windowing coherence").
- Does not implement `Policy` or pattern logic — read-only observer of events produced elsewhere.
- Logging is minimal and internal-failure-only: `ResilienceMetricsListener` logs at WARN via SLF4J when a
  backend's `observe(...)` call throws, so a broken backend never propagates into the protected call. This
  supersedes an earlier, informally-assumed direction toward `System.Logger` — see `ARCHITECTURE.md`'s
  "Logging" section for why SLF4J was chosen project-wide instead.
