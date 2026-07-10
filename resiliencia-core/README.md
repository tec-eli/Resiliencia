# resiliencia-core

Foundational contracts shared by every other module: `Resilient<T>`, `Outcome<T>`, `ResilientException`, and the
`ResilienceEvent` SPI.

This module has **no dependencies** beyond the JDK (virtual threads baseline, Java 21+) and defines no
resilience behavior itself — no Retry, no CircuitBreaker, nothing pattern-specific lives here.

## Scope

- `Resilient<T>` — the functional contract every pattern and `Policy` implement to expose `call()`,
  `callAsync()`, `outcome()`.
- `Outcome<T>` — sealed result type with three variants: `Success`, `Failure`, and `TimedOut`.
- `ResilientException` — root unchecked exception; every pattern-specific exception extends it.
- `ResilienceEvent` — the SPI (marker interface + `Listener`) that patterns implement their typed event
  hierarchies against. No logging or metrics backend lives here — only the contract.
- `module-info.java` — no dependencies exported beyond `java.base`.

## Non-goals

- No pattern implementations (Retry, Timeout, etc.) — see `resiliencia-patterns`.
- No composition logic (`Policy`, order validation) — see `resiliencia-compose`.
- No metrics backend or logging implementation — see `resiliencia-metrics` and the framework integration modules.
