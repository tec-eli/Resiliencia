# Resiliencia

![resiliencia](docs/assets/resiliencia-banner.png)

> Engineered to endure.

[![License](https://img.shields.io/badge/license-Apache_2.0-blue?style=flat-square)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.tec-eli/resiliencia-core.svg)](https://central.sonatype.com/artifact/io.github.tec-eli/resiliencia-core)
[![Build & Test](https://github.com/tec-eli/resiliencia/actions/workflows/build.yml/badge.svg)](https://github.com/tec-eli/resiliencia/actions/workflows/build.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=tec-eli_Resiliencia&metric=alert_status&token=18f4fe092678f4960cd01cfd2b44f5abc314b620)](https://sonarcloud.io/summary/new_code?id=tec-eli_Resiliencia)
[![Trivy Vulnerability Scan](https://github.com/tec-eli/resiliencia/actions/workflows/security-scan.yml/badge.svg)](https://github.com/tec-eli/resiliencia/actions/workflows/security-scan.yml)
[![OSS Index Dependency Scan](https://github.com/tec-eli/resiliencia/actions/workflows/ossindex.yml/badge.svg)](https://github.com/tec-eli/resiliencia/actions/workflows/ossindex.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/tec-eli/Resiliencia/badge)](https://securityscorecards.dev/viewer/?uri=github.com/tec-eli/Resiliencia)
[![CodeQL](https://github.com/tec-eli/resiliencia/actions/workflows/codeql.yml/badge.svg)](https://github.com/tec-eli/resiliencia/actions/workflows/codeql.yml)
[![Static Analysis](https://github.com/tec-eli/resiliencia/actions/workflows/quality.yml/badge.svg)](https://github.com/tec-eli/resiliencia/actions/workflows/quality.yml)


[![Quality gate](https://sonarcloud.io/api/project_badges/quality_gate?project=tec-eli_Resiliencia&token=18f4fe092678f4960cd01cfd2b44f5abc314b620)](https://sonarcloud.io/summary/new_code?id=tec-eli_Resiliencia)

**[Website & API docs →](https://tec-eli.github.io/resiliencia/)**

**Resiliencia** brings Retry, Timeout, CircuitBreaker, Bulkhead, and RateLimiter to Java 21, built on virtual threads
instead of thread pools and scheduler tricks — a timeout is a real interrupt, not a poll loop. Patterns compose into
an explicit `Policy` chain, and that chain is checked when you build it: wire a `Retry` around an already-open
`CircuitBreaker` and construction throws `InvalidPolicyException` instead of quietly burning your retry budget in
production. The Java Module System is enforced from the first commit, and the API leans on sealed interfaces,
records, and pattern matching throughout.

---

## Quick start

```xml
<dependency>
    <groupId>io.github.tec-eli</groupId>
    <artifactId>resiliencia-compose</artifactId>
    <version>1.0.0-beta.1</version>
</dependency>
```

### Single pattern

```java
var retry = Retry.<String>create("api-fetch-retry")
    .withMaxAttempts(3)
    .withInitialDelay(500)
    .withShouldRetry(IOException.class::isInstance);

String result = retry.call(() -> api.fetch());
```

### Composed policy

```java
var policy = Policy.compose(circuitBreaker)
                   .and(retry)
                   .and(timeout);

// Blocking
String result = policy.call(() -> api.fetch());

// Async
CompletableFuture<String> future = policy.callAsync(() -> api.fetch());

// No exceptions — returns a typed result
Outcome<String> outcome = policy.outcome(() -> api.fetch());

switch (outcome) {
    case Success<String> s  -> System.out.println(s.value());
    case Failure<String> f  -> System.out.println("Error: " + f.cause().getMessage());
    case TimedOut<String> t -> System.out.println("Timed out");
}
```

---

## Patterns

| Pattern        | Description                                       |
|----------------|----------------------------------------------------|
| Retry          | Retries with configurable backoff                 |
| Timeout        | Real cancellation via virtual thread interruption |
| CircuitBreaker | Closed / Open / HalfOpen state machine            |
| Bulkhead       | Concurrency limiter via semaphore                 |
| RateLimiter    | Call frequency limiter                            |

All five live in `resiliencia-patterns`, implement the same `Resilient` interface, and work standalone — no need to
compose them to use one.

---

## Composition

`Policy` (in `resiliencia-compose`) chains multiple patterns into a single execution path, in an order you declare
explicitly — outermost to innermost, wrapping the call at the center. It isn't a sixth pattern; it's how the other
five combine.

That order is meaningful, so `Policy` validates it as the chain is built. Some orderings have no legitimate use case
— a `Retry` wrapping a `CircuitBreaker` would keep burning retry budget against a circuit that's already open — and
are rejected at construction with `InvalidPolicyException`. Others are valid but non-default, and only logged as a
`WARN`.

---

## Metrics

Patterns and `Policy` emit sealed events (`Snapshot`, `Counters`) for every state change — `resiliencia-metrics`
turns those into counter/gauge/timer calls through one small, backend-neutral contract (`ResilienceMetrics`), without
depending on any specific metrics library itself. `resiliencia-micrometer` and `resiliencia-opentelemetry` implement
that contract against Micrometer's `MeterRegistry` and the OpenTelemetry SDK, respectively — pick a backend, or stay
on the built-in `NoOpMetrics` and consume the events yourself.

---

## Micrometer

`resiliencia-micrometer` implements `ResilienceMetrics` on top of a Micrometer `MeterRegistry` — every event becomes
a counter, gauge, or timer tagged with the pattern instance's `name`. Timers never opt into percentile histograms or
SLO buckets, and the gauge cache is bounded, so a misbehaving caller can't grow metrics cardinality without limit.
Drop the module on the classpath and wire the resulting `ResilienceMetrics` into your patterns; `resiliencia-opentelemetry`
covers the same contract for the OpenTelemetry SDK.

---

## Design principles

- **Java 21 is the baseline.** No compatibility shims, no legacy paths.
- **Virtual threads are not optional.** All blocking operations run on virtual threads.
- **Immutable configuration.** Pattern objects are created once and reused safely across threads.
- **No global registry.** No hidden singletons. You own your instances.
- **Unchecked exceptions only.** No checked exceptions in the public API.
- **Zero external dependencies** in `resiliencia-core` and `resiliencia-patterns`.

---

## Requirements

- Java 21 or higher
- Maven 3.9+

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
