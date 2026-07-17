# resiliencia-opentelemetry

OpenTelemetry backend implementation of the `resiliencia-metrics` neutral interfaces.

Depends on `resiliencia-metrics`.

## Scope

- Binds `resiliencia-metrics` snapshots/counters/aggregates to OTel `Meter`/instrument types
  (counters, gauges, histograms as appropriate per pattern).
- Naming/attribute conventions for exported instruments, documented per pattern, kept consistent with the
  Micrometer module's naming where both exist.
- `OpenTelemetryResilienceMetrics`: construct directly with a `Meter` obtained from your own
  `OpenTelemetry`/`SdkMeterProvider` instance (framework autoconfiguration lives in the integration
  modules, not here — this module has no wiring helper of its own yet).
- `DurationInstrumentationMode`: controls how duration metrics (`resilience.timeout.duration`,
  `resilience.circuitbreaker.calls`) are recorded — `SAFE` (default) or `DETAILED` (opt-in). Pass it
  as the second constructor argument; omitting it uses `SAFE`.
  - `SAFE`: a lock-free counter pair — `<name>.count` and `<name>.sum` — instead of the base metric
    name. No percentile/distribution data; mean is derivable as `sum / count` at query time. Zero
    per-call pinning risk by construction.
  - `DETAILED`: a `DoubleHistogram` under the base metric name, giving full percentile/distribution
    data. Accepts a known risk: the OpenTelemetry SDK's default histogram aggregation synchronizes
    on every recorded value, which can pin a virtual thread. Only opt in if you also control the
    `MeterProvider`'s `View` configuration for that instrument. See `DurationInstrumentationMode`'s
    Javadoc and `docs/architecture/metrics/metrics.md`'s "Backend audit" section for the full
    rationale.

## Non-goals

- No metrics contract definitions — see `resiliencia-metrics`.
- No framework-specific auto-configuration — see `resiliencia-spring`/`resiliencia-quarkus`/
  `resiliencia-micronaut`.
- No Micrometer integration — see `resiliencia-micrometer`.
- No tracing/spans — this module covers metrics only; tracing is out of scope for v1.
