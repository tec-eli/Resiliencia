# CLAUDE.md — resiliencia: Java 21 Resilience Library

## Overview

**resiliencia** is a modular resilience patterns library for Java 21+. This file guides Claude Code when working in
this repository. Follow these conventions consistently. Do not refactor existing code or suggest architectural
changes unless explicitly requested.

**Source of truth for behavior and design decisions:** `docs/architecture/*.md`, grouped in a subfolder per module
(one file per concept — `docs/architecture/core/core.md`;
`docs/architecture/patterns/{retry,timeout,circuit-breaker,bulkhead,rate-limiter}.md`;
`docs/architecture/compose/policy.md`) and `docs/architecture/ARCHITECTURE.md` (cross-cutting decisions: virtual
threads, module system, jcstress, module strategy, no global registry). Both are living documents — if this file and
a spec ever disagree, the spec wins. The project is on a early stage so this can be changed if there is a better
approach.

### Handling spec gaps

If you hit a case a spec doesn't cover, **stop and ask** — don't guess or invent behavior. Ask a specific,
option-driven question (e.g. "Should `withMaxAttempts(0)` throw at construction or at first call?") rather than an
open-ended one. Do not proceed with an assumption on unresolved design questions.

---

## Language & Runtime

- **Java 21** (LTS minimum) — use features available in Java 21; do not assume preview features are enabled
- Use `var` where it improves readability without losing type clarity
- Use **records** for immutable data carriers and value objects
- Use **sealed classes/interfaces** to model closed hierarchies (e.g., `Outcome<T>`, `CircuitState`, event types)
- Use **pattern matching** (`instanceof`, `switch` expressions) where appropriate
- Use **text blocks** for multiline strings (SQL, JSON, templates)
- Avoid deprecated APIs; do not use raw types

---

## Build

- Build tool: **Maven** (multi-module project)
- Always verify code compiles before considering a task done:
  ```bash
  mvn compile
  ```
- Run tests to confirm nothing is broken:
  ```bash
  mvn test
  ```
- Run stress tests before releases (concurrency correctness):
  ```bash
  # In resiliencia-stress/
  mvn test
  ```
- **Do not introduce external dependencies** in `resiliencia-core`, `resiliencia-patterns`, or `resiliencia-compose`.
- Integration modules (Spring, Quarkus, Micrometer, OpenTelemetry) may have dependencies, but always get explicit
  approval before adding any.

---

## Architecture: Multi-Module Resilience Library

**resiliencia** is a multi-module Maven project organized by concerns, not layers. Each Maven module has its own
`module-info.java` enforcing encapsulation at compile time. Full rationale for all of the below lives in
`docs/architecture/ARCHITECTURE.md`.

### Module structure

```
resiliencia/
├── resiliencia-core/              ← Base types, SPI, sealed interfaces (no external deps)
├── resiliencia-patterns/          ← Retry, Timeout, CircuitBreaker, Bulkhead, RateLimiter (no external deps)
├── resiliencia-compose/           ← Policy: fluent pattern composition (no external deps)
├── resiliencia-metrics/           ← Metrics abstraction (no Micrometer or OTel dependency)
├── resiliencia-micrometer/        ← Micrometer integration
├── resiliencia-opentelemetry/     ← OpenTelemetry integration
├── resiliencia-spring/            ← Spring Boot starter (auto-config + AOP)
├── resiliencia-quarkus/           ← Quarkus extension
├── resiliencia-micronaut/         ← Micronaut integration
├── resiliencia-test/              ← Test helpers (Fake*, ManualClock, Assertions)
├── resiliencia-stress/            ← jcstress concurrency tests (not published)
└── resiliencia-examples/          ← Usage examples (not published)
```

### Design principles

**1. Virtual threads as the foundation (not optional)**

- **Timeouts** are real, via virtual thread interruption — not `ScheduledExecutorService` polling
- **Bulkhead** uses semaphores — blocking a virtual thread is cheap
- **Composition (`Policy`)** uses plain virtual-thread interruption for cancellation today, **not** structured
  concurrency (it was still preview in Java 21). Do not introduce structured concurrency into `resiliencia-core`,
  `resiliencia-patterns`, or `resiliencia-compose` — it's reserved for a future `resiliencia-java25` module once
  Java 23+ is the target there.
- No `ExecutorService` with platform threads in core modules

**2. Sealed interfaces for domain types**

Patterns use sealed interfaces to model closed hierarchies (`Outcome<T>` with Success/Failure/TimedOut,
`CircuitState` with Closed/Open/HalfOpen). Users cannot extend them; the library controls all valid subtypes,
enabling exhaustive pattern matching and preventing invalid states.

**3. Fluent API with reusable objects**

Patterns are configured fluently but produce immutable, reusable objects (not disposable builders). Once configured,
the same instance is used many times across multiple calls. Objects are thread-safe by design and injectable into
containers (Spring, Quarkus).

**4. Event-driven observability**

Patterns emit typed events (sealed event interfaces per pattern). Metrics and logging consume these events — core
never depends on Micrometer or OTel.

**5. Unchecked exceptions only**

All resiliencia exceptions extend `RuntimeException`. Users can catch specific exceptions (e.g.,
`CircuitBreakerOpenException`), catch all with `ResilientException`, or use `outcome()` to avoid exceptions
entirely.

**6. Policy order validation**

`Policy.and(pattern)` checks the new pattern's `PatternKind` against everything already in the chain (transitive,
not just adjacent):
- **Retry wrapping CircuitBreaker** → throws `InvalidPolicyException` at construction time — no legitimate use case.
- **Timeout wrapping Retry** → logs `WARN` via SLF4J, construction proceeds — valid for per-attempt timeout, only a
  footgun if the user wanted an overall retry-loop deadline (not modeled yet).

Use `Resilient<T>.patternKind()` for this kind of internal check, never `instanceof` and never the observability
`patternName(): String`. See `docs/architecture/compose/policy.md` for full rationale before touching this logic.

---

## Coding Conventions

### Naming

- Classes: `PascalCase`
- Methods, fields: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Packages: `lowercase.dot.separated`
- Avoid abbreviations unless universally understood (`id`, `url`, `dto` are fine)

### Immutability

- Prefer `final` fields; make classes immutable when possible
- Use **records** for DTOs and value objects
- Return defensive copies of mutable collections, or use `Collections.unmodifiableList` / `List.copyOf`

### Null handling

- Do not return `null` from public methods — use `Optional<T>` for optional return values
- Do not pass `null` as method arguments — use overloads instead
- Annotate parameters with `@NonNull` / `@Nullable` (Jakarta or JSpecify) where helpful

### Error handling

Hierarchy: `ResilientException` (base) extends `RuntimeException`, with `RetryExhaustedException`,
`RetryRejectedException`, `RetryInterruptedException`, `ResilientTimeoutException`, `CircuitBreakerOpenException`,
`BulkheadFullException`, `RateLimiterException`, `InvalidPolicyException` as subtypes.

- **Specific exceptions:** catch individual exceptions for fine-grained control
- **General catch:** use `ResilientException` to catch all library-related failures
- **No exceptions:** use `outcome()` instead of `call()` for functional/Result-oriented style — never throws
- **Event listening:** subscribe to pattern events for observability, even if you don't catch exceptions
- Never swallow exceptions silently; always handle explicitly, log, or rethrow

### Collections & Streams

- Prefer `List.of()`, `Map.of()`, `Set.of()` for immutable collections
- Use Streams for transformations; avoid complex nested Streams that hurt readability
- Terminate streams — never store or reuse a `Stream<T>` reference

### Logging

- Use **SLF4J** (`LoggerFactory.getLogger(Foo.class)`) — never `System.out` or `java.util.logging`
- Use parameterized messages: `log.debug("Order {} created", orderId)` — never string concatenation
- Log at appropriate levels: `DEBUG` internals, `INFO` business events, `WARN` recoverable issues, `ERROR` failures

---

## Unit Tests

Add unit tests whenever creating or modifying non-trivial logic. Tests live next to production code under
`src/test/java`, mirroring the same package structure.

### Framework

- **JUnit 5** (`@Test`, `@ParameterizedTest`, `@BeforeEach`)
- **Mockito** for mocking dependencies
- **AssertJ** for fluent assertions (preferred over plain JUnit assertions)

### Test naming

```java
@Test
void should_returnEmptyOptional_when_orderDoesNotExist() { ... }
// Pattern: should_<expected>_when_<condition>
```

### Structure (AAA)

```java
@Test
void should_calculateTotal_when_discountApplied() {
    // Arrange
    var order = Order.of(List.of(item("BOOK", 50_00), item("PEN", 5_00)));
    var discount = Discount.percentage(10);

    // Act
    var total = order.applyDiscount(discount).total();

    // Assert
    assertThat(total).isEqualTo(Money.of(49_50, Currency.EUR));
}
```

### What to test

- Domain logic and value objects — always, no mocks needed
- Application services — mock ports/repositories
- Do **not** unit test infrastructure adapters (persistence, HTTP); those need integration tests

### Coverage guidance

- Domain layer: aim for high coverage (>80%)
- Application layer: cover main flows and error paths
- Skip boilerplate (getters, records, simple constructors)

### Stress tests

- Located in `resiliencia-stress/` (separate module), uses **jcstress**
- Run before each release — verify CircuitBreaker, Bulkhead, RateLimiter are thread-safe under contention
- Not published to Maven Central, not run on every PR

---

## Module Responsibilities

### resiliencia-core
Foundation: `Resilient` (call, callAsync, outcome), sealed `Outcome<T>`, exception hierarchy, `PatternKind`, SPI
(listeners, metrics collector), internal utilities. **Zero external dependencies. Never add any.**

### resiliencia-patterns
The 5 resilience patterns — Retry, Timeout, CircuitBreaker, Bulkhead, RateLimiter. Each implements `Resilient`, emits
sealed event types, thread-safe and reusable. **Zero external dependencies. Never add any.**

### resiliencia-compose
`Policy`: fluent pattern composition with explicit, validated order (see Design Principle 6 above).
**Zero external dependencies. Never add any.**

### resiliencia-metrics
Abstract `ResilienciaMetrics` interface (counter, gauge, timer) and `NoOpMetrics` default. **Zero external
dependencies.** Other modules depend on this.

### resiliencia-micrometer, resiliencia-opentelemetry
Implement `ResilienciaMetrics` over Micrometer `MeterRegistry` or OpenTelemetry SDK. May have external dependencies.

### resiliencia-spring, resiliencia-quarkus, resiliencia-micronaut
Framework integrations: auto-configuration, AOP annotations (`@WithRetry`, `@WithCircuitBreaker`), injectable beans.

### resiliencia-test
Test helpers for library users: `Fake*` implementations, `ManualClock`, `ResilienciaAssertions` (JUnit 5).

### resiliencia-stress
jcstress concurrency tests. Not published. Run before releases.

---

## What Claude Should and Should Not Do

### Do

- Follow existing code style and patterns in the file being edited
- Compile code mentally before writing — ensure imports are correct and types match
- Add unit tests when creating new core or pattern logic
- Use Java 21 language features where they make code clearer (var, records, sealed, pattern matching)
- Respect module boundaries enforced by `module-info.java` — never import `internal/` packages from other modules
- Use fluent, immutable "wither" methods for pattern configuration
- Use sealed interfaces for closed hierarchies and event types
- Use virtual threads for concurrency in core modules
- Emit events from patterns for observability
- Add high test coverage for domain logic (aim for >80%)
- Keep lines at most 120 characters where possible
- Stop and ask a specific, option-driven question when a spec doesn't cover the case at hand

### Do not

- **Create git commits without explicit user request.** Only commit when the user explicitly asks. Staging changes is OK, but commits require authorization.
- Refactor, rename, or restructure existing code unless explicitly asked
- Add external dependencies to `resiliencia-core`, `resiliencia-patterns`, or `resiliencia-compose` without explicit approval
- Add external dependencies to other modules without approval
- Generate placeholder or `TODO` implementations and present them as done
- Use `System.out.println` in production code
- Use `@SuppressWarnings("unchecked")` without a comment explaining why
- Invent APIs or methods that don't exist in the JDK or declared dependencies
- Import `internal/` packages — these are not exported by the module system
- Use checked exceptions — all exceptions must extend `RuntimeException`
- Use `ExecutorService` with platform threads in core modules
- Use structured concurrency in `resiliencia-core`, `resiliencia-patterns`, or `resiliencia-compose` — reserved for `resiliencia-java25`
- Depend on Micrometer or other observability libraries in core modules — use the `ResilienceEventListener` SPI instead
- Silently reorder a user-supplied `Policy` pattern chain

---

## Response Language

All responses and all generated code, comments, and documentation must be in **English**.
