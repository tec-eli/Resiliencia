# Spec — resiliencia-spring

Spring Framework / Spring Boot integration: auto-configuration and declarative annotation support for
`resiliencia-patterns` and `resiliencia-compose`.

Depends on `resiliencia-patterns` and `resiliencia-compose`. Optionally detects `resiliencia-micrometer` and
`resiliencia-opentelemetry` on the classpath (see "Auto-binding to observability backends" below).

---

## Behavior

### AOP mechanism

Declarative annotations (`@Retry`, `@CircuitBreaker`, `@Timeout`, `@Bulkhead`, `@RateLimiter`) are applied via
**Spring Proxy AOP** (JDK dynamic proxy / CGLIB) — the same mechanism `@Transactional`/`@Cacheable` use. This is not
configurable to AspectJ weaving.

Known limitations, inherited from the proxy model and not mitigated by this module:

- Self-invocation (`this.method()` from within the same class) is not intercepted.
- Only public methods can be advised.
- CGLIB proxies cannot advise `final` classes or `final` methods.

**Rejected:** AspectJ weaving (compile-time or load-time). Load-time weaving requires a `-javaagent` at runtime —
operational friction on the consumer that contradicts the project's existing stance on not imposing runtime setup
burden (see `ARCHITECTURE.md`'s virtual-threads section, which rejected `--enable-preview` for the same reason).
Not offered as an opt-in alternative either, to keep a single, well-understood AOP path.

### Composing multiple patterns on one method

A method is advised against a single named `Policy` bean, referenced by name:

```java
@Resilient("orderPolicy")
public Order placeOrder(OrderRequest request) { ... }
```

`orderPolicy` is a `Policy` bean the consumer constructs elsewhere with the fluent API (`Policy.compose(...).and(...)`
or `Policy.useOptimumOrder(...)`), going through `Policy`'s existing order validation
(`InvalidPolicyException` / `WARN` rules, see `compose/policy.md`) exactly as it would outside Spring.

**Rejected:** stacking single-pattern annotations directly on a method with an explicit order
(`@Retry(order=1) @CircuitBreaker(order=2)`). Execution order in that model is determined by Spring AOP advice
precedence, which never passes through `Policy.ORDERING_RULES` — it would silently reopen the exact ordering
footguns `Policy` was designed to reject or warn on (e.g. Retry wrapping CircuitBreaker), with no construction-time
guardrail and no flattening of nested `Policy` instances.

Single-pattern annotations (`@Retry`, `@CircuitBreaker`, etc., see "YAML-driven pattern beans" below) remain valid
for the single-pattern case — only *stacking multiple* pattern annotations on one method to express composition is
rejected.

### Bean registry

`resiliencia-spring` does not introduce its own name→instance registry. The Spring `ApplicationContext` is the
single source of truth for pattern and `Policy` beans; lookups go through Spring's own bean resolution
(`@Qualifier`, bean name, or type), consistent with `ARCHITECTURE.md`'s "no global registry" principle.

`ARCHITECTURE.md` explicitly permits integration modules to build their own registry concept. This module
deliberately does not exercise that permission: introducing a second name→instance store would duplicate what the
`ApplicationContext` already resolves, without solving any friction that doesn't already have a Spring-native
answer.

### YAML-driven pattern beans

`resiliencia-spring` auto-configures **individual pattern instances** — `Retry`, `CircuitBreaker`, `Timeout`,
`Bulkhead`, `RateLimiter` — from `application.yml`/`.properties`:

```yaml
resiliencia:
  retry:
    orderRetry:
      max-attempts: 3
```

produces a `Retry` bean named `orderRetry`, built internally via the same fluent API a consumer would use by hand.
Property binding maps to an internal configuration record per pattern (permitted by `ARCHITECTURE.md`'s fluent-API
section: "Configuration records passed to a factory... may still exist as an internal implementation detail") — the
record is never exposed as public API; it exists solely to translate bound properties into fluent-API calls.

**`Policy` composition is explicitly out of this mechanism.** There is no YAML shape for declaring pattern order or
building a `Policy`. Composition is Java-only, via the fluent API, as specified above. This boundary is intentional
and must not be blurred by future property additions — `Policy` order is a construction-time-validated concern, and
YAML has no path to that validation.

### Auto-binding to observability backends

If `resiliencia-micrometer` and/or `resiliencia-opentelemetry` are present on the classpath, and a corresponding
`MeterRegistry` (or OTel equivalent) bean exists in the context, `resiliencia-spring` attaches the matching
`ResilienceEvent.Listener` automatically — but **only to pattern beans this module itself creates from YAML**
(see above). Detection uses `@ConditionalOnClass` against the concrete listener class from each backend module, so
this compiles regardless of whether either backend module is present.

Pattern beans a consumer constructs by hand in Java (`@Bean Retry paymentRetry() { ... }`) do **not** receive this
listener automatically, regardless of classpath contents — the consumer attaches it explicitly via
`.withListener(...)`. This is the same YAML-vs-hand-built boundary drawn for configuration above, applied
consistently to observability wiring.

### Custom listener registration

Consumer-defined `ResilienceEvent.Listener` beans (e.g. a custom audit listener) are **not** auto-discovered or
auto-attached to any pattern bean, YAML-created or otherwise. The consumer wires them explicitly.

This is a deliberate asymmetry with the observability auto-binding above, not an inconsistency: the observability
case binds a single, known, project-owned listener class of narrow purpose (translating events to metrics). A
consumer's custom listener is of arbitrary, unknown purpose and scope — auto-attaching it to every YAML-created
pattern risks silent fan-out (a listener meant for one `CircuitBreaker` ending up wired to unrelated patterns too),
compounded by the fact that the event/metrics contract already documents **no dedup guard for multi-registration**.
Requiring explicit wiring avoids introducing that failure class by default.

### Async support

Methods returning `CompletableFuture<T>` are supported by the same annotations used for synchronous methods. The
advice inspects the method's return type (cached per method after first resolution) and routes to
`resiliencia-core`'s existing `callAsync` path rather than a blocking invocation, so the caller receives the
`CompletableFuture` immediately rather than after the resilience chain completes.

Failures on the async path resolve via `future.completeExceptionally(...)`; they are never thrown from the advice
itself on that path.

**Reactor types (`Mono`/`Flux`) are out of scope** and require explicit future approval — they would introduce a new
external dependency, which no decision here authorizes.

---

## Configuration surface

| Property | Required | Description |
|----------|----------|-------------|
| `resiliencia.<pattern-type>.<name>.*` | no | Per-instance configuration for a single pattern bean, bound to that pattern's fluent-API options. `<pattern-type>` is one of `retry`, `circuit-breaker`, `timeout`, `bulkhead`, `rate-limiter`. |

There is no property namespace for `Policy` composition or ordering.

---

## Non-goals

- No pattern or composition logic — see `resiliencia-patterns` / `resiliencia-compose`.
- No metrics contract or backend logic — see `resiliencia-metrics` and its backend modules.
- Not a replacement for the fluent API — annotations are additive, not the primary interface.
- No AspectJ weaving support, opt-in or otherwise.
- No standalone name→instance registry.
- No YAML-driven `Policy` composition.
- No auto-discovery of consumer-defined listeners.
- No Reactor (`Mono`/`Flux`) support without separate, explicit approval.
- Post-v1 module; not part of `v1.0.0-beta` scope.

---

## Design rationale

**Proxy AOP over AspectJ weaving.** The proxy model's limitations (no self-invocation, public methods only) are
already familiar to any Spring developer from `@Transactional`. AspectJ weaving's runtime cost (a `-javaagent`, or a
build-time weaving step) is exactly the category of consumer-facing operational friction the project has already
rejected elsewhere (`ARCHITECTURE.md`, virtual-threads section, rejecting `--enable-preview` for the same reason).

**Single-`Policy`-bean composition over stacked annotations.** Stacked annotations with an `@Order`-style attribute
would determine execution order via Spring AOP advice precedence — a mechanism with no relationship to
`Policy.ORDERING_RULES`. Adopting it would silently reopen the ordering footguns `Policy` exists to catch
(`InvalidPolicyException` for pairs with no legitimate use, `WARN` for risky-but-valid pairs), including the
transitive and nested-`Policy`-flattening checks specified in `compose/policy.md`. Referencing a single, already-
validated `Policy` bean preserves those guarantees intact.

**No standalone registry.** Though `ARCHITECTURE.md` permits integration modules to build one, doing so here would
create a second name→instance mapping duplicating what `@Qualifier`/bean-name resolution already provides, once
`Policy` beans are the unit of composition (previous decision) — solving a friction that Spring's own container
already resolves.

**YAML boundary drawn at individual patterns, not `Policy`.** `ARCHITECTURE.md` explicitly permits an internal
configuration record behind the fluent API ("may still exist as an internal implementation detail"), which is what
property binding is here — not a new public entry point. Composition validation is a construction-time concern with
no natural YAML representation, so it stays Java-only. The same YAML-vs-hand-built line then governs observability
auto-binding by extension: what the module builds from YAML, the module can also wire; what the consumer builds by
hand, the consumer also wires.

**Custom listeners require explicit wiring, deliberately inconsistent with backend auto-binding.** Auto-binding
Micrometer/OTel listeners is safe because the module knows exactly which class it's attaching and why. Consumer
listeners are unknown in scope and purpose; auto-attaching them by type risks unintended fan-out across unrelated
patterns, with no dedup guard in the underlying event contract to catch double-registration if a consumer also
wires the same listener elsewhere.

**`CompletableFuture` supported at implementation cost, not architectural cost.** `resiliencia-core`'s `callAsync`
already solves running each pattern correctly under async execution; what the AOP layer adds is return-type
detection and invocation routing. The real cost is doubled test surface per pattern (sync and async paths) and
care around exception delivery (`completeExceptionally` vs. throw), not a new design problem.