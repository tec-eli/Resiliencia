# resiliencia-quarkus

Quarkus integration: extension and declarative annotation support for `resiliencia-patterns` and
`resiliencia-compose`.

Depends on `resiliencia-patterns` and `resiliencia-compose`.

## Scope

- Quarkus extension (build-time processing + runtime config) wiring `Policy`/pattern beans from
  `application.properties`.
- Declarative annotations for method-level application via CDI interceptors, as a convenience layer over
  the fluent builder API — the fluent API remains fully usable without this module.
- Optional auto-binding to `resiliencia-micrometer`/`resiliencia-opentelemetry` when those are on the
  classpath.

## Non-goals

- No pattern or composition logic — see `resiliencia-patterns` / `resiliencia-compose`.
- No metrics contract or backend logic — see `resiliencia-metrics` and its backend modules.
- Not a replacement for the fluent API — annotations are additive, not the primary interface.
- Post-v1 module; not part of `v1.0.0-beta` scope.
