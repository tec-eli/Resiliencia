# resiliencia-spring

Spring Framework / Spring Boot integration: auto-configuration and declarative annotation support for
`resiliencia-patterns` and `resiliencia-compose`.

Depends on `resiliencia-patterns` and `resiliencia-compose`.

## Scope

- Spring Boot auto-configuration wiring `Policy`/pattern beans from `application.yml`/`.properties`.
- Declarative annotations (e.g. `@Retry`, `@CircuitBreaker`) for method-level application via AOP, as a
  convenience layer over the fluent builder API — the fluent API remains fully usable without this module.
- Optional auto-binding to `resiliencia-micrometer`/`resiliencia-opentelemetry` when those are on the
  classpath.

## Non-goals

- No pattern or composition logic — see `resiliencia-patterns` / `resiliencia-compose`.
- No metrics contract or backend logic — see `resiliencia-metrics` and its backend modules.
- Not a replacement for the fluent API — annotations are additive, not the primary interface.
- Post-v1 module; not part of `v1.0.0-beta` scope.
