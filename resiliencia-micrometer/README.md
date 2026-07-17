# resiliencia-micrometer

Micrometer backend implementation of the `resiliencia-metrics` neutral interfaces.

Depends on `resiliencia-metrics`.

## Scope

- Binds `resiliencia-metrics` snapshots/counters/aggregates to Micrometer `Meter` types
  (`Counter`, `Gauge`, `Timer`, `DistributionSummary` as appropriate per pattern).
- Naming/tagging conventions for exposed meters, documented per pattern.
- `MicrometerResilienceMetrics`: construct directly with your own `MeterRegistry` (framework
  autoconfiguration lives in the `resiliencia-spring`/`resiliencia-quarkus`/`resiliencia-micronaut`
  modules, not here — this module has no auto-binding helper of its own yet).

## Non-goals

- No metrics contract definitions — see `resiliencia-metrics`.
- No framework-specific autoconfiguration (Spring Boot starters, Quarkus extensions) — those modules
  depend on this one, not the other way around.
- No OpenTelemetry integration — see `resiliencia-opentelemetry`.
