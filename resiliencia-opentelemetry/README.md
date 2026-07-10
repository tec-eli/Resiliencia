# resiliencia-opentelemetry

OpenTelemetry backend implementation of the `resiliencia-metrics` neutral interfaces.

Depends on `resiliencia-metrics`.

## Scope

- Binds `resiliencia-metrics` snapshots/counters/aggregates to OTel `Meter`/instrument types
  (counters, gauges, histograms as appropriate per pattern).
- Naming/attribute conventions for exported instruments, documented per pattern, kept consistent with the
  Micrometer module's naming where both exist.
- Optional manual-wiring helper(s) for an `OpenTelemetry` instance (framework auto-configuration lives in
  the integration modules, not here).

## Non-goals

- No metrics contract definitions — see `resiliencia-metrics`.
- No framework-specific auto-configuration — see `resiliencia-spring`/`resiliencia-quarkus`/
  `resiliencia-micronaut`.
- No Micrometer integration — see `resiliencia-micrometer`.
- No tracing/spans — this module covers metrics only; tracing is out of scope for v1.
