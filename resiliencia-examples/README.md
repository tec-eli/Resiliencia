# resiliencia-examples

Runnable, minimal demos showing how to use resiliencia's public API — one scenario per pattern/feature,
not exhaustive coverage.

This module is **not published** to Maven Central (`maven.deploy.skip=true`). It exists to be read and run,
not depended on.

## Scope

- Depends on `resiliencia-patterns` and `resiliencia-compose` only.
- Uses SLF4J + a simple binding for console output — the only module in the repo allowed to pull in a
  logging backend, since output is the point of a demo.
- No test coverage of its own; correctness is enforced by the modules it demonstrates.
- One class per scenario (e.g. `RetryExample`), each with a `main` method and no shared framework —
  a reader should be able to open one file and understand it standalone.

## Running

```bash
cd resiliencia-examples
mvn compile exec:java -Dexec.mainClass="io.github.teceli.resiliencia.examples.RetryExample"
```

## Adding a new example

- One `public class` per pattern or composition scenario, package `io.github.teceli.resiliencia.examples`.
- `main` method, no CLI args, no external state — must run as-is.
- Prefer 2–4 short `static void exampleX()` methods over one long `main`, each isolating one behavior
  (success path, exhaustion/failure path, observability/events).
- Log via SLF4J at `info` for outcomes, `debug` for internal events — don't use `System.out`.