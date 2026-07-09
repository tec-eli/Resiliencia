# Architecture — resiliencia

Cross-cutting decisions that don't belong to a single pattern spec. This is a living document — edited in place as
decisions change, not superseded by new files. Pattern-specific behavior and rationale live in `specs/`.

---

## Virtual threads as the implementation foundation

Virtual threads (Java 21+) are not an option — they are the central implementation mechanism.

- Timeouts are implemented via virtual thread interruption, not `ScheduledExecutorService`
- Bulkhead uses semaphores, not thread pool sizing — blocking a VT is cheap
- No `ExecutorService` with platform threads is created in core modules
- Pattern composition (`Policy`) currently uses **plain virtual-thread interruption**, not structured concurrency —
  structured concurrency was still preview in Java 21 (stable in 23+), and requiring `--enable-preview` is not
  acceptable to place on every consumer of a Java 21-targeted library. Real structured concurrency support may be
  added later in `resiliencia-java25`, a separate module targeting newer Java versions, once it's stable and doesn't
  force a minimum-version bump on everyone else.

**Rejected alternatives:** platform thread pools (adds nothing new, limits scale); a fully reactive API à la
Reactor/RxJava (accidental complexity, external deps, unnecessary once VT is available); making VT optional (adds
implementation/testing complexity for no benefit given the Java 21+ floor).

**Trade-off accepted:** Java 21 minimum, no support for earlier versions. Some frameworks aren't yet fully optimized
for VT — documented as a risk in the README for users integrating resiliencia in those contexts.

---

## Fluent API with reusable, immutable objects

Configuration is fluent (`Retry.<T>create().withMaxAttempts(3)...`), but the resulting object is immutable and
reusable — not a disposable builder. Each `withX` method returns a new instance (the "wither" convention, same as
`LocalDate.withYear(...)`), never mutates the receiver. Pattern objects are thread-safe by design and compatible with
dependency injection.

**Rejected alternatives:**
- Configuration records passed to a factory (`Retry.of(new RetryConfig(...))`) — requires users to know a separate
  config class as the primary entry point. May still exist as an internal implementation detail.
- Annotations (`@WithRetry(maxAttempts = 3)`) — requires a framework/AOP runtime, adds complexity. Could be added
  later as an optional layer over the fluent API, not a replacement for it.

---

## Record vs. class for pattern implementations

`Retry` and `Timeout` are records: pure immutable configuration, nothing else. `CircuitBreaker`, `Bulkhead`, and
`RateLimiter` are final classes instead, because each holds live, concurrently-mutated state on top of its
configuration — `CircuitBreaker`'s current `CircuitState` and sliding window, `Bulkhead`'s permits, `RateLimiter`'s
current window and used count.

A Java record's instance state is strictly limited to its canonical components — nothing else may be added besides
`static` fields. Embedding the live state as a component (e.g. a `Semaphore` or `AtomicReference<StateSlot>`) would
force a public accessor for it, letting external code reach in and manipulate internal state directly (e.g.
`bulkhead.permits().release()`), and would pollute the record's auto-generated `equals()`/`hashCode()`/`toString()`
with implementation details that aren't part of the pattern's identity.

All five patterns stay immutable in configuration and thread-safe by design regardless of record-vs-class: each
`withX` method always returns a new, independent instance. For the three stateful ones, "new instance" also means a
fresh copy of the live state (e.g. a new CircuitBreaker starts back in the Closed state with an empty window).

**Rejected alternatives:** forcing all five patterns to be records for API uniformity (would require exposing live
mutable state through a public accessor, defeating encapsulation); wrapping live state in a nested record component
without an accessor (not possible — record components are always accessible).

---

## Java Module System (JPMS) from day one

Every module has `module-info.java` from its first commit, since Java 21 makes JPMS always available. `internal/`
packages are never exported — enforced by the compiler, not by convention. Only `api/` and `spi/` are exported.

Tests may need `--add-opens` to reach package-private internals; production code must never do this. If a framework
integration needs `opens` for reflection, that goes in the integration module, not in core.

**Rejected alternatives:** skipping module-info now and adding it later (retrofitting JPMS after users depend on
internals is expensive — doing it from day one, even at extra setup cost, is the cheaper path); module-info only in
core (consistency across modules matters more than saving initial effort).

---

## jcstress for concurrency correctness

Resilience patterns have real shared, concurrently-modified state (`CircuitBreaker` state, `Bulkhead` semaphore,
`RateLimiter` counters). JUnit with `Thread`/`ExecutorService` cannot reliably surface race conditions — they're
non-deterministic and may not reproduce in CI.

`jcstress` lives in its own `resiliencia-stress` module:
- Not published to Maven Central
- Not run on every PR — run manually before each release (and on the release branch in CI)
- Complementary to JUnit, not a replacement

**Rejected alternatives:** JUnit-only concurrency tests (insufficient — can pass in CI, fail under real load); Thread
Weaver (discontinued; jcstress is the active Oracle/OpenJDK successor); no explicit concurrency testing (unacceptable
given thread-safety is part of the library's value proposition).

---

## No global registry

Users create and own their pattern instances directly. There is no `CircuitBreakerRegistry.ofDefaults()`, no static
`CircuitBreaker.getInstance("name")`. This avoids resilience4j's concrete problems: hidden singleton state that's
hard to replace in tests, name collisions between unrelated components in the same JVM, multi-tenant workarounds, and
framework integrations fighting over registry ownership.

Framework integrations (Spring, Quarkus, etc.) may build their own registry concept — but that lives in the
integration module, not in `resiliencia-core` or `resiliencia-patterns`.

**Rejected alternatives:** global registry with opt-out (opt-out defaults get ignored in practice — the antipattern
ships and gets used regardless).

---

## Exception classification: Transient vs. Permanent failures

Patterns that can retry or recover from failures (Retry, CircuitBreaker) classify exceptions to avoid wasted
retry attempts on permanent failures:

- **Transient failures** (IO exceptions): network timeouts, connection resets, DNS failures, temporary service unavailability.
  These failures *may* succeed on retry — retrying makes sense.
- **Permanent failures** (logic errors): invalid arguments, null pointer dereferences, programming mistakes.
  Retrying a permanent failure has no chance of success — it wastes time and delays failure reporting.

By default, Retry only retries `IOException` and subclasses (the best proxy for transient infrastructure faults).
Other exception types (e.g., `RuntimeException`, `NullPointerException`, `IllegalArgumentException`) are not retried
unless explicitly configured via `withShouldRetry()`. This reduces noise and improves latency in failure paths:
when an operation fails permanently, fail fast instead of burning retries.

This narrow default does not widen itself based on composition: composing Retry outermost of Timeout, Bulkhead, or
RateLimiter (`resiliencia-compose`'s recommended order) does not automatically retry those patterns' own exceptions
either — `shouldRetry` must be extended explicitly to cover them (see `docs/patterns/retry.md`).

---

## Maven module strategy

Multiple Maven modules with clear responsibilities, unified versioning (all share one release cycle and version
number):

```
Core (no external deps):        resiliencia-core, resiliencia-patterns, resiliencia-compose
Observability (optional):       resiliencia-metrics, resiliencia-micrometer, resiliencia-opentelemetry
Framework integrations (opt.):  resiliencia-spring, resiliencia-quarkus, resiliencia-micronaut
Developer modules (not the lib):resiliencia-test, resiliencia-stress, resiliencia-examples, resiliencia-java25
```

`resiliencia-stress` and `resiliencia-examples` live in the repo but are explicitly excluded from deploy — not
published to Maven Central. `resiliencia-compose` is the recommended dependency for most users, since it pulls in
core and patterns transitively.

**Rejected alternatives:** a single `resiliencia` artifact (would force every user to pull in Spring, Micrometer,
OTel even if unused — classpath bloat and version-conflict risk); independently-versioned modules (adds release
complexity with no clear benefit at this stage; can be revisited if integration modules end up needing a different
lifecycle than core).

---

## Error handling: `Error` is never treated as a business outcome

Patterns catch `Exception`, never `Error`. A `java.lang.Error` (`OutOfMemoryError`, `StackOverflowError`, etc.)
signals a condition the JVM itself may not recover from — it is not a result a caller should receive back through
`Outcome.Failure` or retry/filter logic, and library code must not pretend otherwise. This matches resilience4j's
behavior and is deliberate, not an oversight.

Concretely, across `resiliencia-patterns`:

- `Retry`, `Bulkhead`, `RateLimiter`, `CircuitBreaker` only catch `Exception` in their `outcome()` implementation;
  an `Error` thrown by the operation propagates uncaught, on the calling thread.
- `Timeout` runs the operation on a virtual thread, so it must catch `Error` there to observe it at all — but still
  never wraps it into `Outcome`; it stores the `Error` and rethrows it unchanged on the caller's thread once the
  worker finishes (see `Timeout.outcome()`). It still emits `TimeoutEvent.Failed` for observability before
  rethrowing — same rationale as `CircuitBreaker`'s permit bookkeeping below: this is event bookkeeping, not
  turning the `Error` into a business outcome.
- `CircuitBreaker` additionally must resolve any HalfOpen test-call permit an `Error` consumed (a one-shot budget
  counter, not a releasable semaphore) — it catches `Error` solely to call `recordOutcome(true, ...)` before
  rethrowing, so the circuit can still transition out of HalfOpen. This is bookkeeping, not error swallowing: the
  `Error` is never turned into an `Outcome.Failure`, and no other pattern needs an equivalent because none of them
  hold state that an unresolved call could leak.

**Rejected alternatives:** wrapping `Error` into `Outcome.Failure` like any other `Throwable` (makes `outcome()`
falsely "never throw" for conditions the JVM itself is signaling as unrecoverable, and invites callers to retry or
filter on `Error` types the same way as ordinary exceptions).
