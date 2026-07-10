# resiliencia-test

Test fakes and utilities for consumers writing tests against code that uses resiliencia — not the test
suite for resiliencia itself.

Depends on `resiliencia-core`.

## Scope

- `ManualClock` — a controllable time source consumers can inject to deterministically test time-dependent
  pattern behavior (deadlines, windows, backoff) without real sleeps.
- Fakes/stubs for `Resilient<T>` and related contracts, letting consumers isolate their own code from real
  pattern behavior in unit tests.
- Assertion helpers for common scenarios (e.g. asserting an operation was retried N times), where useful
  and genuinely reusable.

## Non-goals

- Not resiliencia's own internal test suite (unit tests live alongside each module; concurrency tests live
  in `resiliencia-stress`).
- No pattern or composition logic — this module only provides test doubles for them.
- Post-v1 module; not part of `v1.0.0-beta` scope.
