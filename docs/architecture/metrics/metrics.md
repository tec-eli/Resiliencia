# Spec — Metrics

Backend-neutral translation of pattern and Policy events into counter/gauge/timer emissions.
`resiliencia-metrics` does not ship a metrics backend itself — it defines the contract
(`ResilienceMetrics`) and the event→metric translation (`ResilienceMetricsListener`) that
`resiliencia-micrometer` and `resiliencia-opentelemetry` implement against.

---

## Dependency

`resiliencia-metrics` depends on `resiliencia-core`, `resiliencia-patterns`, **and**
`resiliencia-compose` — not only on `patterns`.

`ResilienceMetricsListener` performs an exhaustive switch over every event type it can observe.
`PolicyValidationWarning` (see "Policy validation warnings" below) is defined in `compose`, not in
`patterns`, because that's where order-validation logic actually lives. Depending only on
`patterns` would leave the switch structurally unable to reference that type — the alternative
would be duplicating/relocating the event into `patterns` purely to satisfy a dependency
constraint, which breaks the existing separation of concerns between `patterns` and `compose`.

This is a legal dependency: `metrics` sits above `compose` in the module layering (`core` →
`patterns`/`compose` → `metrics` → `micrometer`/`opentelemetry`), and a higher-layer module
depending on a lower one doesn't introduce a cycle — `compose` still depends only on `core` (via
`Resilient<T>` and `ResilienceEvent`), never on `patterns` or `metrics`.

**Rejected alternative:** keep `metrics` dependent only on `patterns`/`core` and surface Policy's
validation warnings as a `patterns`-level event instead. Rejected — it relocates event ownership
away from where the event actually originates, purely to satisfy a dependency constraint.

---

## The `ResilienceMetrics` contract

```java
public interface ResilienceMetrics {
    void observe(Snapshot snapshot);
    void observe(Counters counters);
}
```

Two methods, not one per pattern and not a stringly-typed generic API. `Snapshot` and `Counters`
are sealed roots (see "Sealed metric types" below); each backend implementation performs its own
exhaustive `switch` over the sealed hierarchy inside its `observe(...)` method body.

Named `observe`, not `record` — `record` collides with the `record` keyword introduced in Java 16,
which is confusing directly adjacent to the `Snapshot`/`Counters` sealed hierarchies that are
themselves implemented as records.

**Options considered:**

- **Stringly-typed generic** (`incrementCounter(String name, Map<String,String> tags)`,
  `setGauge(String name, Map<String,String> tags, double value)`) — closest 1:1 mapping to
  Micrometer's own API, but throws away the compile-time exhaustiveness that sealed types +
  records are meant to provide throughout this codebase. Doesn't even fully eliminate adaptation
  work for OpenTelemetry, whose tagging model isn't identical to Micrometer's either. Rejected.
- **One method per concrete pattern** (`onCircuitBreakerSnapshot(...)`, `onBulkheadCounters(...)`,
  one pair per pattern) — maximal compile-time safety: adding a new pattern is a compiler error
  for every backend until updated. But the interface grows unboundedly as patterns are added, and
  every backend implements N method pairs instead of 2. Rejected.
- **Hybrid (chosen):** small, stable 2-method interface — the signature never changes when a new
  pattern is added — while still carrying full type information via the sealed `Snapshot`/
  `Counters` roots, so a backend's own `switch` gets exhaustiveness for free.

**Trade-off accepted knowingly:** with the hybrid shape, "every case must be handled" is enforced
inside each backend's own `switch`, not by the `ResilienceMetrics` interface itself — a backend
author could technically write a non-exhaustive `if/else` and silently drop a case. Accepted
because (a) an idiomatic `switch` over a sealed type without a `default` branch still gets
compiler errors for missing cases, and (b) no backend implementation exists yet, so there's no
legacy constraint forcing a different shape — revisit if this proves a real problem once
`resiliencia-micrometer`/`resiliencia-opentelemetry` exist.

**Implementation contract** (binding on every `ResilienceMetrics` implementation):

- **Non-blocking.** No I/O, no lock acquisition that could wait.
- **Allocation-light.** Avoid unnecessary object creation on the hot path.
- **Must not pin virtual threads in code resiliencia itself writes.** This applies strictly to the
  event→metric mapping code inside `resiliencia-metrics`, `resiliencia-micrometer`, and
  `resiliencia-opentelemetry` — it must not introduce `synchronized` blocks or other pinning-prone
  constructs. It does **not**, and cannot, extend as a guarantee over whatever concrete backend
  registry the consuming application wires in at runtime — see "Backend audit" below for why that
  boundary is drawn exactly there, not weakened globally.
- If a specific backend genuinely needs async dispatch (unusual — treat as an exception, not the
  norm), that's an internal concern of that backend module, not a knob exposed on
  `ResilienceMetrics` or the shared listener.

---

## Sealed metric types

```java
public sealed interface Snapshot permits
    CircuitBreakerSnapshot, BulkheadSnapshot, RateLimiterSnapshot {}

public sealed interface Counters permits
    RetryCounters, TimeoutCounters, CircuitBreakerCounters, BulkheadCounters,
    RateLimiterCounters, PolicyCounters {}
```

`Snapshot` only has three permitted implementations — Retry and Timeout have no live,
gauge-worthy state (no window, no permit count), so they never produce a `Snapshot`, only
`Counters`. `permits` does not require implementations to share a file or package with the sealed
root (see "Folder layout" below) — this keeps compiler-enforced exhaustiveness without collapsing
every pattern's records into one large file.

Each per-pattern type is itself a small sealed interface (same pattern the rest of the codebase
already uses for `RetryEvent`, `CircuitBreakerEvent`, etc.), so each variant carries exactly the
fields its source event actually has — no variant is ever constructed from partial information or
requires the listener to remember a previous event's fields. This is what keeps "mirror, never
recompute" (see below) literally true at the type level, not just as a documented intention.

```java
// circuitbreaker/CircuitBreakerSnapshot.java
public sealed interface CircuitBreakerSnapshot extends Snapshot {
    enum Phase { CLOSED, OPEN, HALF_OPEN }

    record State(String name, Phase phase) implements CircuitBreakerSnapshot {}
    record FailureRate(String name, double rate) implements CircuitBreakerSnapshot {}
}

// circuitbreaker/CircuitBreakerCounters.java
public sealed interface CircuitBreakerCounters extends Counters {
    record Transition(String name, CircuitBreakerSnapshot.Phase to,
                      CircuitBreakerEvent.Reason reason) implements CircuitBreakerCounters {}

    /**
     * Emitted alongside a Transition(to=CLOSED): every Closed transition originates from
     * HalfOpen in the current state machine, and successfulTestCalls only has meaning for that
     * specific case. Kept as its own variant rather than a field on Transition, whose fields are
     * meaningful for every (to, reason) pair it represents — folding a HalfOpen-only value into
     * it would mean constructing Transition from partial information depending on which
     * transition fired, which no variant in this module does (see above).
     */
    record ClosedFromHalfOpen(String name, int successfulTestCalls) implements CircuitBreakerCounters {}

    record CallRecorded(String name, boolean successful, Duration elapsed)
        implements CircuitBreakerCounters {}

    /**
     * A call rejected without executing, because the circuit was Open or HalfOpen with no
     * permits left. Reuses CircuitBreakerEvent.RejectingPhase directly — the enum the source
     * event carries — rather than a metrics-local duplicate, the same convention Transition
     * already follows by reusing CircuitBreakerEvent.Reason as-is.
     */
    record Rejected(String name, CircuitBreakerEvent.RejectingPhase phase)
        implements CircuitBreakerCounters {}
}
```

`Phase` is deliberately a small, stable enum, not a reuse of `patterns`' own `CircuitState` sealed
type. `CircuitState.Open` carries `openedAt`/`remainingWait` — useful to the pattern, irrelevant
and unstable as gauge content. `Transition.reason` is `null` for `Closed`/`HalfOpened` transitions
(`CircuitBreakerEvent.Reason` is only populated on `Opened`, per `circuit-breaker.md`).

```java
// bulkhead/BulkheadSnapshot.java
public sealed interface BulkheadSnapshot extends Snapshot {
    record ActiveCalls(String name, int count) implements BulkheadSnapshot {}
}

// bulkhead/BulkheadCounters.java
public sealed interface BulkheadCounters extends Counters {
    enum Outcome { PERMITTED, REJECTED }
    record Call(String name, Outcome outcome) implements BulkheadCounters {}
}
```

```java
// ratelimiter/RateLimiterSnapshot.java
public sealed interface RateLimiterSnapshot extends Snapshot {
    record RemainingPermits(String name, int remaining) implements RateLimiterSnapshot {}
}

// ratelimiter/RateLimiterCounters.java
public sealed interface RateLimiterCounters extends Counters {
    enum Outcome { PERMITTED, REJECTED }
    record Call(String name, Outcome outcome) implements RateLimiterCounters {}
}
```

```java
// retry/RetryCounters.java
public sealed interface RetryCounters extends Counters {
    /**
     * One failed attempt, sourced from RetryEvent.AttemptFailed, which fires once per failed
     * attempt within a call.
     */
    record AttemptFailed(String name, String cause) implements RetryCounters {}

    /**
     * The call succeeded — sourced from RetryEvent.Success, emitted exactly once per call, when
     * the retry loop as a whole succeeds. Distinct from AttemptFailed rather than a shared
     * Outcome enum on one record: the two represent different units — one call vs. one attempt —
     * and Success has no per-attempt equivalent to pair with. totalAttempts is carried on the
     * record for backends that want to build a distribution of attempts-per-call; the default
     * listener does not tag by it (see "Cardinality / tagging contract").
     */
    record Success(String name, int totalAttempts) implements RetryCounters {}

    record Exhausted(String name, String cause) implements RetryCounters {}
    record Rejected(String name, String cause) implements RetryCounters {}
    record Interrupted(String name, String cause) implements RetryCounters {}
}
```

```java
// timeout/TimeoutCounters.java
public sealed interface TimeoutCounters extends Counters {
    enum AbandonedOutcome { SUCCEEDED, FAILED }
    record Succeeded(String name, Duration elapsed) implements TimeoutCounters {}
    record Failed(String name, String cause) implements TimeoutCounters {}
    record TimedOut(String name) implements TimeoutCounters {}
    record Abandoned(String name, AbandonedOutcome outcome) implements TimeoutCounters {}
}
```

```java
// policy/PolicyCounters.java
public sealed interface PolicyCounters extends Counters {
    record ValidationWarning(PatternKind outer, PatternKind inner) implements PolicyCounters {}
}
```

`cause` fields are `String`, already resolved to a bounded value by `ResilienceMetricsListener`
before construction (see "Cardinality/tagging contract") — never a raw `Throwable`, never
`getMessage()`. `null`/absent means cause tagging is disabled (empty allowlist, the default).

---

## `ResilienceMetricsListener`

```java
public final class ResilienceMetricsListener implements ResilienceEvent.Listener {
    public ResilienceMetricsListener(ResilienceMetrics metrics) { ... }
    public ResilienceMetricsListener(ResilienceMetrics metrics,
                                      Set<Class<? extends Throwable>> causeAllowlist) { ... }

    @Override
    public void onEvent(ResilienceEvent event) {
        switch (event) {
            case RetryEvent e -> handleRetry(e);
            case TimeoutEvent e -> handleTimeout(e);
            case CircuitBreakerEvent e -> handleCircuitBreaker(e);
            case BulkheadEvent e -> handleBulkhead(e);
            case RateLimiterEvent e -> handleRateLimiter(e);
            case PolicyValidationWarning e -> handlePolicy(e);
            default -> { } // CUSTOM patterns / unknown ResilienceEvent implementations — ignored
        }
    }
}
```

One shared listener, registered via each pattern's existing `.withListener(...)` (and via
`Policy.withListener(...)`, see "Policy validation warnings" below) — not one listener type per
pattern. `ResilienceEvent` itself is a plain interface, not sealed (a `CUSTOM`-kind user pattern
could implement it), so the `default` branch is required for exhaustiveness and is the correct,
intentional way to ignore events this listener doesn't understand.

### One event can feed more than one metric

A single event may drive more than one `observe(...)` call in the same `onEvent()` invocation, when
the event already carries the data for both. Concrete example — `CircuitBreakerEvent`:

```java
private void handleCircuitBreaker(CircuitBreakerEvent event) {
    switch (event) {
        case CircuitBreakerEvent.CallRecorded e -> {
            safeObserve(new CircuitBreakerCounters.CallRecorded(e.name(), e.isSuccessful(), e.elapsedTime()));
            safeObserve(new CircuitBreakerSnapshot.FailureRate(e.name(), e.currentFailureRate()));
        }
        case CircuitBreakerEvent.Opened e -> {
            safeObserve(new CircuitBreakerCounters.Transition(e.name(), CircuitBreakerSnapshot.Phase.OPEN, e.reason()));
            safeObserve(new CircuitBreakerSnapshot.State(e.name(), CircuitBreakerSnapshot.Phase.OPEN));
        }
        case CircuitBreakerEvent.HalfOpened e -> {
            safeObserve(new CircuitBreakerCounters.Transition(e.name(), CircuitBreakerSnapshot.Phase.HALF_OPEN, null));
            safeObserve(new CircuitBreakerSnapshot.State(e.name(), CircuitBreakerSnapshot.Phase.HALF_OPEN));
        }
        case CircuitBreakerEvent.Closed e -> {
            safeObserve(new CircuitBreakerCounters.Transition(e.name(), CircuitBreakerSnapshot.Phase.CLOSED, null));
            safeObserve(new CircuitBreakerCounters.ClosedFromHalfOpen(e.name(), e.numberOfSuccessfulTestCalls()));
            safeObserve(new CircuitBreakerSnapshot.State(e.name(), CircuitBreakerSnapshot.Phase.CLOSED));
        }
        case CircuitBreakerEvent.Rejected e ->
            safeObserve(new CircuitBreakerCounters.Rejected(e.name(), e.phase()));
    }
}
```

**Rejected alternative — strict 1:1 mapping** (each event feeds exactly one metric type; a gauge
update would require its own dedicated event). Rejected because it directly contradicts "mirror,
never recompute" below — if an event already carries the data for a second metric (e.g.
`CallRecorded`'s `currentFailureRate`, `Closed`'s `numberOfSuccessfulTestCalls`), forcing a second
metric to wait for a separate event means either inventing a new event type purely to satisfy an
arbitrary rule, or not using data that's already there. Neither is justified.

### Execution model — synchronous, on the calling thread

The listener runs synchronously, on the virtual thread executing the protected call. This is not
in tension with virtual threads: the pinning concern applies to **blocking I/O** inside a
`synchronized` block — waiting on a network response, a lock, a sleep — because that's what
prevents a virtual thread from being unmounted from its carrier. Incrementing a counter or setting
a gauge is CPU-bound, O(1), in-memory work, not a wait. Actual export to a backend (Prometheus
scrape, OTel collector push) happens on threads owned by those libraries, already decoupled from
the call that recorded the measurement.

Three reasons synchronous was chosen over dispatching to a separate executor:

1. **Contract consistency.** `ResilienceEvent.Listener` is already a plain synchronous
   `void onEvent(...)` contract, defined once in `core`. A different dispatch model for the
   metrics listener specifically would be an undocumented inconsistency other listeners don't have.
2. **Ordering correctness.** Gauges (`activeCalls`, `remainingPermits`, circuit state) are
   last-writer-wins. Dispatching to a separate executor risks out-of-order delivery, which can
   silently and intermittently flip a gauge to a stale value — a subtle bug class synchronous
   dispatch avoids by construction.
3. **No throughput to gain.** Metric recording is meant to be O(1) and non-blocking in both
   Micrometer and OTel; the expensive part (export, batching, network I/O) is already decoupled
   inside those libraries. An executor hop here buys nothing.

The actual virtual-thread-pinning risk is a different axis entirely — `synchronized` blocks inside
a *backend's* concrete implementation, not resiliencia's synchronous dispatch decision. See
"Backend audit" below.

### Exception isolation — the listener protects itself

`onEvent()` wraps **each** delegation to `ResilienceMetrics.observe(...)` in its own try/catch — not
one try/catch around the whole method, so that when an event drives two `observe(...)` calls (see
above), one backend failure doesn't suppress the other. This is deliberately **not** based on an
assumption that `core` guarantees listener exception isolation generally — that guarantee isn't
confirmed to exist, and `resiliencia-metrics` shouldn't depend on a contract it doesn't own.
Principle: a failure in observability must never cause a failure in the thing being observed.

On a caught exception: log at **WARN**, via SLF4J, with **no throttling or rate-limiting**.

**Options considered for the logging behavior:**

- **(A) WARN every time, no throttling (chosen).** Simple, no new state. Floods logs during a
  full backend outage, but that failure mode is rare and already self-evident through other
  signals (empty dashboards, missing data, absence-of-metrics alerting) — doesn't justify adding
  throttling complexity to a module meant to stay minimal. Revisit only if this proves a real
  operational pain point in practice.
- **(B) Log-once-per-exception-type with a counted summary.** Bounds the flood, but requires new
  internal state (e.g. a `Set<Class<?>>`) in a module that should stay lightweight. Rejected for
  now.
- **(C) SLF4J-native rate limiting.** Doesn't exist natively in SLF4J; would need an extra
  dependency (e.g. a Logback `TurboFilter`) or custom throttling code — contradicts the goal of
  keeping this module minimal. Rejected.

---

## Policy validation warnings

`compose/Policy.java` only logs ordering diagnostics via `log.warn(...)` at construction time (see
`policy.md`'s "Order validation" section) — nothing is retained on the instance by default. To make
this observable by `ResilienceMetricsListener`, `Policy` carries:

- A `List<ResilienceEvent.Listener>` field, propagated through `.and(...)` the same way `patterns`
  is already propagated.
- A `Policy<T> withListener(ResilienceEvent.Listener listener)` wither, mirroring every pattern's
  own `withListener(...)`.
- `PolicyValidationWarning(Instant timestamp, PatternKind outer, PatternKind inner, String problem,
  String suggestedFix) implements ResilienceEvent` (`patternName()` returns `"policy"`), emitted to
  the currently-registered listeners **in addition to**, not instead of, the SLF4J `WARN` log,
  whenever a WARN-severity `OrderingRule` fires and isn't suppressed.

**`InvalidPolicyException` (ERROR-severity rules) can never produce this event.** Construction
fails before a `Policy` instance exists to attach a listener to — there is nothing to emit to.
This is not a gap to close; it's the direct consequence of ERROR-severity rules rejecting
construction outright rather than producing an instance. Only WARN-severity rules, where
construction proceeds, have an instance capable of holding listeners.

**Known limitation, documented rather than solved:** a listener only observes warnings raised by
`.and()` calls made *after* it was attached via `.withListener(...)`. `Policy.useOptimumOrder(...)`
builds its entire chain in a single static call with no opportunity to attach a listener mid-build,
so any warning it triggers internally is never observed as an event — only via the SLF4J log,
which is unaffected and always fires. This is the same category of best-effort observability
already accepted elsewhere in this project (see `timeout.md`'s `AbandonedWorkerSucceeded`/
`AbandonedWorkerFailed`, "best-effort and may be missed").

`listeners` is copied via `List.copyOf(...)` at construction — immutable, so no shared-mutable-list
hazard across `Policy` instances derived from a common `.and()` chain. The SLF4J log and the
`PolicyValidationWarning` emission always fire from the same branch, never one without the other.
`outer`/`inner` are resolved as `PatternKind`, never as the concrete pattern instance.

---

## Naming convention

`resilience.<pattern>.<metric>` — dot-namespaced, no `Resiliencia`/`resiliencia` stutter in the
metric name string itself (see "Naming" below for the equivalent in-code convention). This is the
separator both Micrometer and OTel already use natively.

**Options considered:**

- **Dot-namespace, OTel-style (chosen).** Zero-friction mapping into either backend; both already
  use `.` as their hierarchical separator internally.
- **Prometheus-native snake_case with prefix** (`resilience_circuitbreaker_calls_total`).
  Rejected — Micrometer and OTel already perform the dots-to-underscores translation automatically
  when exporting *to* Prometheus specifically; doing that conversion at the point of origin
  duplicates a step the backend already owns, and actively works against OTel-style consumption if
  a different backend is used.
- **Custom non-hierarchical short names** (`resilience-cb-calls`). Rejected — no benefit, moves
  away from an existing convention, harder for consumers to bring dashboard/query knowledge from
  other instrumented libraries (e.g. Resilience4j's own metric naming).

### Mapping table

Canonical name each backend should map the corresponding `Snapshot`/`Counters` variant to:

| Pattern | Record variant | Metric name | Type | Notes |
|---|---|---|---|---|
| Retry | `RetryCounters.AttemptFailed` | `resilience.retry.attempts` | counter | opt-in `cause`; per-attempt, failed attempts only |
| Retry | `RetryCounters.Success` | `resilience.retry.success` | counter | per-call, not per-attempt; `totalAttempts` carried but not tagged by default |
| Retry | `RetryCounters.Exhausted` | `resilience.retry.exhausted` | counter | opt-in `cause` |
| Retry | `RetryCounters.Rejected` | `resilience.retry.rejected` | counter | opt-in `cause` |
| Retry | `RetryCounters.Interrupted` | `resilience.retry.interrupted` | counter | opt-in `cause` |
| Timeout | `TimeoutCounters.Succeeded` | `resilience.timeout.duration` | timer | `elapsed` |
| Timeout | `TimeoutCounters.Failed` | `resilience.timeout.failed` | counter | opt-in `cause` |
| Timeout | `TimeoutCounters.TimedOut` | `resilience.timeout.timed_out` | counter | — |
| Timeout | `TimeoutCounters.Abandoned` | `resilience.timeout.abandoned` | counter | tag: `outcome` |
| CircuitBreaker | `CircuitBreakerSnapshot.State` | `resilience.circuitbreaker.state` | gauge | `phase` as 0/1/2 |
| CircuitBreaker | `CircuitBreakerCounters.Transition` | `resilience.circuitbreaker.transitions` | counter | tags: `to`, `reason` (null on Closed/HalfOpened) |
| CircuitBreaker | `CircuitBreakerCounters.ClosedFromHalfOpen` | `resilience.circuitbreaker.closed_test_calls` | counter/summary | `successfulTestCalls`; emitted alongside `Transition(to=CLOSED)` |
| CircuitBreaker | `CircuitBreakerSnapshot.FailureRate` | `resilience.circuitbreaker.failure_rate` | gauge | — |
| CircuitBreaker | `CircuitBreakerCounters.CallRecorded` | `resilience.circuitbreaker.calls` | timer | tag: `successful` |
| CircuitBreaker | `CircuitBreakerCounters.Rejected` | `resilience.circuitbreaker.rejected` | counter | tag: `phase` |
| Bulkhead | `BulkheadSnapshot.ActiveCalls` | `resilience.bulkhead.active_calls` | gauge | emitted from `Permitted` and `Finished` |
| Bulkhead | `BulkheadCounters.Call` | `resilience.bulkhead.calls` | counter | tag: `outcome` |
| RateLimiter | `RateLimiterSnapshot.RemainingPermits` | `resilience.ratelimiter.remaining_permits` | gauge | — |
| RateLimiter | `RateLimiterCounters.Call` | `resilience.ratelimiter.calls` | counter | tag: `outcome` |
| Policy | `PolicyCounters.ValidationWarning` | `resilience.policy.validation_warnings` | counter | tags: `outer`, `inner` |

**A note on `resilience.retry.attempts` vs `resilience.retry.success`:** the two metrics count
different units — `attempts` counts individual failed attempts (many possible per call), `success`
counts calls (exactly one per successful call). They are not directly comparable as a ratio
without accounting for that difference; a consumer wanting "success rate per attempt" needs
`totalAttempts` from `Success` records aggregated against the attempt counts, not a naive
`success / (success + attempts)` division.

**A note on the two `timer`-typed rows (`resilience.timeout.duration`,
`resilience.circuitbreaker.calls`):** the base name in the table above is only what's emitted
under `DurationInstrumentationMode.DETAILED` (a `DoubleHistogram`/`DistributionSummary`). Under the
**default** `SAFE` mode, the same row is instead exposed as a name-suffixed counter pair —
`<name>.count` (call count) and `<name>.sum` (accumulated duration) — to stay lock-free and avoid
the histogram's per-record pinning risk documented below. A consumer building a dashboard against
the base name alone will find nothing under the default mode; see "Backend audit" below for the
full SAFE/DETAILED rationale.

---

## Identity (`name`)

Every pattern — including `Retry` and `Timeout` — carries a required `name`, matching the
already-established convention: `CircuitBreaker.of(String name)`, `Bulkhead.of(String name, ...)`,
`RateLimiter.of(String name, ...)` all take `name` as the first positional factory argument,
required, with no wither (identity, fixed at construction, not runtime-changeable configuration).
See `retry.md`/`timeout.md` for the corresponding change on those two patterns — this closes the
prior asymmetry: `ResilienceMetricsListener` never special-cases Retry/Timeout for lack of a `name`
tag, no fallback bucket, no conditional logic based on which pattern emitted the event.

**Uniqueness is a documentation convention, not an enforced constraint.** There is no global
registry to check `name` against — deliberately, per `ARCHITECTURE.md`'s "No global registry"
principle. Two `CircuitBreaker`s (or any two same-kind patterns) named identically will silently
produce colliding, indistinguishable time series. This isn't `resiliencia-metrics`'s problem to
solve, but the failure mode becomes externally visible exactly here — a confusing dashboard, not a
compile error or exception — so it's restated explicitly at this point, not left implicit.

---

## Windowing coherence — mirror, never recompute

`resiliencia-metrics` never maintains an independent window or rate calculation. This isn't a
style preference — it's structurally forced: `CircuitBreaker`'s sliding window and `RateLimiter`'s
permit bucket are internal live state with no public accessor, by design (see `ARCHITECTURE.md`'s
record-vs-class rationale, which exists specifically to prevent external code reaching into a
pattern's live state). The only numbers available to `resiliencia-metrics` are whatever the
pattern already computed and chose to put on the event.

Concretely: `currentFailureRate` arrives already computed on `CallRecorded` → the gauge is a pure
mirror of that value, nothing derived, no averaging or recalculation across events.
`remainingPermits` on `RateLimiter`'s `Permitted` → same treatment. `numberOfSuccessfulTestCalls`
on `Closed` follows the same rule — mirrored as-is into `ClosedFromHalfOpen`, never recomputed by
counting `HalfOpened`→`CallRecorded` events across time inside the listener.

If a consumer wants a *different* time window than the pattern's own — "failure rate over the last
5 minutes" instead of the CircuitBreaker's own N-call window — that's ordinary Micrometer/OTel
aggregation performed by the backend over the counters/timers `resiliencia-metrics` feeds it. No
different from consuming any other instrumented library.

**Hard rule:** no independent aggregation state inside `resiliencia-metrics`, ever — this is a
constraint on future contributors, not just a description of current behavior. It exists to
prevent someone later adding a rolling average inside this module because it seemed convenient.

---

## Cardinality / tagging contract

Prometheus-style backends degrade badly — real memory and cost blowup — when a tag/label turns out
to be unbounded. This module is opinionated specifically to prevent that by construction, not by
relying on consumer discipline.

**Tier 1 — bounded, safe by default, no opt-in required:** `name` (bounded by construction
discipline — Design Principle #3 in `ARCHITECTURE.md` already mandates patterns be constructed
once and reused, not per-request, so the set of distinct `name` values is bounded by how many
pattern instances exist, not by request volume); `outcome`/`to`/`from` (fixed 2-3 value enums);
`reason` (`CircuitBreakerEvent.Reason`, closed enum, 2 values today); `phase`
(`CircuitBreakerEvent.RejectingPhase`, closed enum, 2 values — `OPEN`/`HALF_OPEN` — same tier as
`reason` for the same reason: fixed, small, closed).

**Tier 2 — bounded in practice, not by the type system; opt-in, not default-on:** the failure
`cause` field — `Throwable.getClass().getSimpleName()` only, never `getMessage()`. A given call
site usually throws from a small closed set of exception types, but `Throwable` itself is an
unbounded hierarchy — nothing stops a new type appearing (a dependency bump, an unanticipated code
path) and silently creating a new time series.

**Sealed decision — explicit allowlist, not a boolean flag.** `ResilienceMetricsListener` takes a
`Set<Class<? extends Throwable>> causeAllowlist` (empty by default — cause tagging off). Any
thrown exception whose runtime type is not in the allowlist maps to a fixed `"other"` bucket
instead of creating a new tag value. This bounds cardinality *by construction*: the maximum number
of distinct `cause` values is `allowlist.size() + 1`, guaranteed by the API shape, not by hoping
the exception space stays small.

- **Rejected — boolean flag** (`.withCauseTagging(true)`), off by default. Only makes the
  *decision to accept the risk* opt-in; doesn't bound the risk itself. Once enabled, still fully
  exposed to unbounded cardinality the moment an unanticipated exception type appears.
- **Rejected — `Function<Throwable, String>` mapper.** Most dangerous option: nothing stops a
  consumer from putting `getMessage()` or other high-entropy content inside that function,
  delegating all responsibility for boundedness to the consumer with no safety net. Contradicts
  this module's opinionated stance of not trusting consumer discipline by default.

**Tier 3 — never, full stop, no exceptions:** exception messages, request IDs, user IDs, raw URLs,
stack traces, or any other free-text/high-entropy value. Stated here as an explicit prohibition,
not left as an implication derived from the tiers above.

---

## Multi-registration

No dedup guard anywhere — neither in `core`'s listener registration nor in
`resiliencia-metrics`. Documented as a usage contract instead.

All `withListener()` implementations across `patterns` and `Policy` share the identical shape — a
plain, append-only `List`, no `equals()`-based check:

```java
public Bulkhead<T> withListener(ResilienceEvent.Listener listener) {
    var newListeners = new ArrayList<>(listeners);
    newListeners.add(listener);
    return new Bulkhead<>(name, maxConcurrentCalls, maxWait, newListeners, clock);
}
```

Registering the same listener instance twice means `emit()`'s for-loop calls it twice per event —
silent duplication of every increment, for **any** listener type, not just metrics. `core` does
not own or attempt to prevent this, and neither does `resiliencia-metrics`.

**Why a `Set`-based dedup was rejected:** it would only catch identity-duplicates — the narrow,
less realistic case — while actively breaking a legitimate one: two distinct listener instances of
the same class, differently configured, both intentionally attached. It gives a false sense of
safety without catching the failure mode that actually matters — the same shared metrics listener
attached via two different code paths.

This is a direct extension of "No global registry" (`ARCHITECTURE.md`): the same philosophy that
already avoids centrally tracking pattern instances extends to not tracking which listeners are
attached where.

**Consequence differs by metric kind** — state this precisely, not as one blanket warning:
gauges are harmless under double-delivery (`.set(x)` twice is a no-op in effect, last-write-wins).
Counters and timers are not — `increment()` twice is a real, silent `+2`.

**Usage contract:** attach the metrics listener to a given pattern instance (or `Policy`) exactly
once. The library does not detect or prevent duplicate registration. No code-level guard is
planned, in either `core` or `resiliencia-metrics`.

---

## Reset / lifecycle

No reset operation is exposed anywhere in the `ResilienceMetrics` contract. A counter/gauge's
lifetime is tied 1:1 to the lifetime of the pattern instance it's derived from. To reset, destroy
the pattern instance and construct a new one (with the name-collision caveat from "Identity"
above if reusing the same `name`).

**Options considered:**

- **No reset (chosen).** Coincides with "no global registry" and with patterns being constructed
  once and reused (Design Principle #3) — introduces the least new state, consistent with how the
  rest of the library treats pattern instances as long-lived and immutable-in-identity.
- **Explicit reset method** (`resetMetrics()`). Rejected — adds a mutable operation to a module
  that's otherwise purely reactive to events, and opens the door to "reset triggered by accident
  mid-production" bugs, for a need not confirmed to exist.
- **Reset delegated to the backend, outside resiliencia's contract.** Effectively what "no reset"
  already implies in practice — if a backend (e.g. Micrometer's `MeterRegistry`) has native
  reset/clear capability, a consumer uses that directly, bypassing `resiliencia-metrics` entirely.
  No new surface needed.

**`resiliencia-test` is not the answer here.** That module exists to test *pattern behavior* —
fakes and `ManualClock` for controlling time in Retry/RateLimiter/etc. — not to test
metrics-backend behavior; these are genuinely different concerns with no overlap. Testing
`resiliencia-metrics` itself means testing against a fake `ResilienceMetrics` implementation of
one's own, reset however that fake chooses to allow.

---

## Folder layout (module-internal)

```
metrics/
  ResilienceMetricsListener.java
  ResilienceMetrics.java          (backend interface)
  Snapshot.java                   (sealed interface, permits clause)
  Counters.java                   (sealed interface, permits clause)
  circuitbreaker/
    CircuitBreakerSnapshot.java
    CircuitBreakerCounters.java
  bulkhead/
    BulkheadSnapshot.java
    BulkheadCounters.java
  ratelimiter/
    RateLimiterSnapshot.java
    RateLimiterCounters.java
  retry/
    RetryCounters.java
  timeout/
    TimeoutCounters.java
  policy/
    PolicyCounters.java
    PolicyValidationWarning.java
```

**How this shape was reached** (kept for context, not to be re-litigated):

- **By responsibility** (top-level `snapshot/`, `counter/` folders) — scales if counters/gauges
  grow real logic of their own, but adding a pattern means touching multiple folders.
- **By pattern** (mirrors `resiliencia-patterns/`) — coherent with existing organization; adding a
  pattern is one self-contained folder. Weakness: cross-pattern aggregates don't naturally belong
  inside any single pattern's folder.
- **Flat, single-file sealed hierarchy** — maximizes compiler-enforced exhaustiveness and avoids
  folder navigation, but risks producing very large single files as patterns are added.

**Resolution:** sealed hierarchy, not collapsed into one file. `sealed` only requires a `permits`
clause — not that implementations share a file or package — so this keeps the exhaustive-switch
guarantee (the actual value of the flat option) while keeping per-pattern folders (avoiding
oversized files).

**Naming inside the module:** no `Metric`-prefixed stutter (`MetricSnapshot`, `MetricCounters`) —
the enclosing `metrics` package already provides that context. `Snapshot.java`/`Counters.java`
unprefixed at the top level is more idiomatic than repeating the package's own name.

---

## Backend audit — virtual-thread pinning scope

**Structural finding (confirmed, independent of exact library version):** neither
`resiliencia-micrometer` nor `resiliencia-opentelemetry` controls the concrete metrics
implementation at runtime. `resiliencia-opentelemetry` depends only on `opentelemetry-api`
(interfaces) — the actual aggregator/lock behavior is decided by whichever concrete
`MeterProvider` the consuming application wires in, not by resiliencia's code.
`resiliencia-micrometer` similarly calls `MeterRegistry.counter(...).increment()` against whatever
concrete `MeterRegistry` the app supplies (`SimpleMeterRegistry`, `PrometheusMeterRegistry`, etc.)
— resiliencia doesn't choose that either.

**Consequence:** a blanket guarantee that "`ResilienceMetrics` implementations must not pin
virtual threads" is not achievable end-to-end for OTel- or Micrometer-backed consumers, because
pinning can originate inside a concrete backend implementation resiliencia does not control. For
example, the OpenTelemetry Java SDK's `Histogram` aggregation implementations synchronize on every
recorded measurement, by design (see "Verified findings" below for the exact class) — if an
application wires in a `MeterProvider` backed by the SDK's default aggregation, pinning risk exists
regardless of resiliencia's own code.

**Decision — scope the contract precisely, don't weaken it globally.** "Must not pin virtual
threads" applies strictly to code resiliencia itself writes (see "Implementation contract" above).
It does not, and cannot, extend as a guarantee over the consuming application's chosen concrete
backend — documented as a known, non-eliminable limitation, not silently assumed away.

**What IS fully within resiliencia's control, from the same audit:** `resiliencia-micrometer` must
**not** enable `.publishPercentileHistogram()` or `.serviceLevelObjectives(...)` by default on any
`Timer` it creates. The histogram machinery backing those Micrometer features uses `synchronized`
internally (see "Verified findings" below) — a real, *avoidable* pinning risk, since resiliencia's
own code decides whether to opt a `Timer` into those features, unlike the structural backend-choice
issue above. **Note the asymmetry with OTel:** for Micrometer this is avoidable because
`publishPercentileHistogram()`/`serviceLevelObjectives()` are opt-in calls resiliencia's own
`Timer`-creation code simply never makes. For OTel, `resiliencia-opentelemetry` records durations
via `DoubleHistogram.record(...)` (see the mapping table above — `resilience.timeout.duration`,
`resilience.circuitbreaker.calls`), and the OTel SDK's **default** aggregation for any `Histogram`
instrument is the explicit-bucket histogram, which synchronizes on every record (see below). Unlike
the Micrometer case, resiliencia's own code has no equivalent "don't opt in" lever here — the
aggregation strategy is chosen by the consuming application via SDK `View` configuration, not by
anything the `Histogram.record()` API surface exposes to the caller. This is a second, narrower
instance of the same structural finding above, not a new, independently-avoidable risk.

**Verified findings** (direct source read against the pinned versions, GitHub tags
`v1.17.0` of `micrometer-metrics/micrometer` and `v1.63.0` of `open-telemetry/opentelemetry-java`,
July 2026):

*Micrometer 1.17.0 (`micrometer-core`):*

- `io.micrometer.core.instrument.cumulative.CumulativeCounter` — the plain `Counter` implementation
  backing `increment()` for cumulative-mode registries (e.g. `SimpleMeterRegistry`,
  `PrometheusMeterRegistry`) — holds a single `private final DoubleAdder value` field;
  `increment(double)` is `value.add(amount)`. No `synchronized`, no explicit locking. Confirmed
  lock-free as expected.
- `io.micrometer.core.instrument.cumulative.CumulativeTimer` — backs `Timer.record(...)` for the
  same registries — holds `LongAdder count`, `LongAdder total`, and a `TimeWindowMax max`. The
  record path adds to the two `LongAdder`s; no `synchronized` anywhere in the class. Confirmed
  lock-free as expected.
- `io.micrometer.core.instrument.distribution.AbstractTimeWindowHistogram` (the shared parent class
  actually backing `TimeWindowPercentileHistogram`, used when `.publishPercentileHistogram()` or
  `.serviceLevelObjectives(...)` is enabled on a `Timer`) genuinely contains two `synchronized (this)`
  blocks: one inside `rotate()` (bucket-ring rotation) and one inside `takeSnapshot()`
  (percentile/bucket-count snapshot assembly, invoked on scrape). This confirms the existing
  mitigation guidance. One nuance worth recording precisely: `rotate()` is called on *every*
  `recordLong`/`recordDouble`, but it first does a cheap non-synchronized timestamp check and
  returns immediately unless the configured rotation interval has actually elapsed, then guards
  entry into the synchronized section with a `compareAndSet` on an `AtomicIntegerFieldUpdater`. So
  the pinning window is periodic (once per rotation interval, e.g. once per ring-buffer bucket
  width), not incurred on every single recorded value — still a genuine, avoidable risk once
  percentile/SLO histograms are enabled, just less frequent in practice than "every call."

*OpenTelemetry 1.63.0 (`opentelemetry-sdk-metrics`, the module an application wires in behind the
`opentelemetry-api` interfaces `resiliencia-opentelemetry` depends on):*

- The shared base class across all aggregations is
  `io.opentelemetry.sdk.metrics.internal.aggregator.AggregatorHandle`, and it contains **zero**
  `synchronized` code itself; `recordLong`/`recordDouble` delegate to an abstract
  `doRecordLong`/`doRecordDouble` implemented per concrete aggregation.
- **Counter-equivalent** (`LongSumAggregator.Handle`, `DoubleSumAggregator.Handle`, backing
  `LongCounter`/`DoubleCounter`): `doRecordLong`/`doRecordDouble` is `current.add(value)` against a
  `LongAdder`/`DoubleAdder` field. No `synchronized`. Lock-free, confirmed.
- **Gauge-equivalent** (`DoubleLastValueAggregator.Handle`, backing `DoubleGauge`/observable
  gauges): backed by `AtomicReference`/`AtomicLong`. No `synchronized`. Lock-free, confirmed.
- **Histogram** (`DoubleExplicitBucketHistogramAggregator.Handle` — the SDK's **default**
  aggregation for any `Histogram` instrument absent an explicit `View` override — backing
  `DoubleHistogram`/`LongHistogram`, which is what `resiliencia-opentelemetry` uses for duration
  metrics): `doRecordDouble` synchronizes on a private `Object lock` field on **every** recorded
  value (`synchronized (lock) { this.sum += value; ...; this.counts[bucketIndex]++; }`) —
  unconditionally, not periodically like Micrometer's rotation case above.
  `DoubleBase2ExponentialHistogramAggregator.Handle` (the alternative exponential-histogram
  aggregation, selectable via a `View`) also declares `doRecordDouble` and
  `doAggregateThenMaybeResetDoubles` as `synchronized` methods.
- `io.opentelemetry.api.metrics.DefaultMeter` (the no-op implementation used when no SDK
  `MeterProvider` is configured, i.e. plain `opentelemetry-api` with nothing wired in) has trivial
  empty-body `add(...)` methods — confirms that with the API dependency alone (no SDK), there is no
  pinning risk at all; the risk only materializes once a consuming application adds the SDK and
  records durations through a `Histogram`-kind instrument.

**Confidence:** high for all bullets above — each is a direct read of the exact source file at the
exact pinned tag (`v1.17.0` / `v1.63.0`), not inference from general recollection or a different
version.

### `resiliencia-opentelemetry` instrumentation modes (forward-looking — module not yet implemented)

Because of the OTel histogram asymmetry above — unlike Micrometer's percentile histograms, there is
no "don't opt in" lever available to resiliencia's own code — `resiliencia-opentelemetry` will
expose two named, sealed instrumentation modes for duration metrics (`resilience.timeout.duration`,
`resilience.circuitbreaker.calls` in the mapping table above), not a single fixed mapping:

- **`SAFE` (default).** Duration recorded as a lock-free counter pair — a count and a summed
  duration, both confirmed lock-free above (`LongSumAggregator`/`DoubleSumAggregator`). Mean is
  derivable (`sum / count`) by the backend at query time; no percentile/distribution data; zero
  per-call pinning risk by construction.
- **`DETAILED` (explicit opt-in).** Duration recorded via `DoubleHistogram.record(...)`, yielding
  full percentile/distribution data, with the documented per-call `synchronized` pinning risk from
  the "Verified findings" above — same warning treatment already given to Micrometer's
  percentile-histogram opt-in.

**Sealed enumeration, not a consumer-supplied strategy.** A pluggable
`Function`/strategy-object mapping was considered and rejected for the same reason the cause-tagging
allowlist (see "Cardinality / tagging contract") rejects a `Function<Throwable, String>` mapper:
resiliencia doesn't delegate safety-by-default to consumer discipline. A closed, named set of two
modes keeps the safe default enforced by the API shape itself.

**Status:** recorded here so the decision isn't lost before the module exists; no implementation
impact today, and no change to the `ResilienceMetrics` contract, the `Snapshot`/`Counters` sealed
shapes, or anything else already specified above — scoped entirely to the not-yet-built
`resiliencia-opentelemetry` backend.

---

## Failure handling

`resiliencia-metrics` never throws. A broken backend degrades observability — logged at WARN, see
"Exception isolation" above — never the business call being observed. There is no
`ResilienceMetrics`-specific exception type; this is a deliberate asymmetry from every other module
in this library, which all define an exception hierarchy for *business*-facing failures (rejected
permits, exhausted retries, open circuits). Metrics failures are not business failures and must
never surface as one.