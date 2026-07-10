# resiliencia-core

Foundational contracts shared by every other module: `Resilient<T>`, `Outcome<T>`, and `ResilienciaException`.

This module has **no dependencies** beyond the JDK (virtual threads baseline, Java 21+) and defines no
resilience behavior itself — no Retry, no CircuitBreaker, nothing pattern-specific lives here.

## Scope

- `Resilient<T>` — the functional contract every pattern and `Policy` implement to expose `call()`,
  `callAsync()`, `outcome()`.
- `Outcome<T>` — sealed result type (success/failure) returned by non-throwing execution paths.
- `ResilienciaException` — root unchecked exception; every pattern-specific exception extends it.
- `module-info.java` — no dependencies exported beyond `java.base`.
- No logging, no events, no metrics — those are layered on top in higher modules.

## Non-goals

- No pattern implementations (Retry, Timeout, etc.) — see `resiliencia-patterns`.
- No composition logic (`Policy`, order validation) — see `resiliencia-compose`.
- No observability (events, metrics) — see `resiliencia-metrics`.
