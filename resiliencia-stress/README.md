# resiliencia-stress

Concurrency correctness tests for the resilience patterns, using [jcstress](https://github.com/openjdk/jcstress).

Unlike unit tests, these don't assert a single expected outcome — they run an operation many times across many JVM
forks, deliberately reordering and interleaving instructions (via JIT stress flags), and check that only the
outcomes listed as `@Outcome(expect = Expect.ACCEPTABLE)` are ever observed. Anything else fails the run.

This module is **not published** to Maven Central (`maven.deploy.skip=true`) and its tests are **not run on every
PR** — each jcstress fork takes real wall-clock time (seconds to minutes), and results depend on the CPU/JIT
behavior of the machine running them. They are release-gated: run manually before cutting a release, and
automatically on every version tag (see `.github/workflows/stress-tests.yml`).

## Running

Build the shaded jar (bundles the jcstress harness so it can run standalone with `java -jar`):

```bash
cd resiliencia-stress
mvn package -DskipTests
```

Run every stress test:

```bash
java -jar target/resiliencia-stress-1.0-SNAPSHOT-shaded.jar
```

Run a subset by class name regex, e.g. only CircuitBreaker tests:

```bash
java -jar target/resiliencia-stress-1.0-SNAPSHOT-shaded.jar -t ".*circuitbreaker.*"
```

Useful flags during development (fewer iterations run much faster; drop them for a full pre-release run):

```bash
java -jar target/resiliencia-stress-1.0-SNAPSHOT-shaded.jar -t ".*circuitbreaker.*" -iters 5
```

An HTML report is written to `results/index.html` after each run.

## Writing a new test

Each test is a `@JCStressTest`-annotated class under
`src/main/java/io/github/teceli/resiliencia/stress/<pattern>/`, with:

- A `@State`-annotated instance holding the pattern under test (usually configured with a small window/threshold so
  the race condition is reachable in a handful of calls) plus `AtomicInteger` counters fed by a listener.
- One `@Actor` method per concurrent caller.
- One `@Arbiter` method that reads the counters into a jcstress result object (`I_Result`, `II_Result`, ...).
- `@Outcome` annotations declaring every acceptable result; everything else must be `Expect.FORBIDDEN`.

For scenarios that need to control time (e.g. "the wait duration has already elapsed" or "the circuit is already
Open"), use `support.ManualClock`: advance it single-threaded in the `@State` constructor, before the `@Actor`
methods run concurrently — never rely on real-time sleeps, which makes runs slow and non-deterministic.

See `circuitbreaker/ClosedToOpenTransitionTest.java` for a minimal example, or the other tests in the same package
for scenarios involving `ManualClock`.
