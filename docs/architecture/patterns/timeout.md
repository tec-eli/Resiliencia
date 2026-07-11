# Spec — Timeout

Cancels an operation if it does not complete within a configured duration.

---

## Behavior

The operation runs on a virtual thread. A timer starts when the call begins. If the operation completes before the timer
expires, the result is returned normally. If the timer expires first, the calling thread receives a
`ResilientTimeoutException` immediately.

Cancellation is real — the virtual thread running the operation is interrupted, not polled. Operations that do not
respond to interruption will continue running in the background but their result is discarded.

A `cancelOnTimeout` flag controls whether the virtual thread is interrupted on timeout. When set to false, the caller
still receives the exception immediately, but the operation thread is allowed to finish naturally. This is useful when
the operation holds resources that must be released cleanly.

### Caller interrupted while waiting

`outcome()` waits for the worker via `worker.join(timeout)`. If the *calling* thread itself is interrupted while
blocked in that `join()` — a different event from the deadline elapsing — the worker is interrupted (best-effort
cleanup, mirroring the `cancelOnTimeout` path) and the caller's own interrupt status is restored before returning.
This resolves as an `Outcome.Failure` wrapping a `ResilientException` ("Interrupted while waiting for operation to
complete"), never as `Outcome.TimedOut`: the deadline did not necessarily elapse, so reporting a timeout would be
misleading. No `TimeoutEvent` is emitted for this path — it is an interruption of the *caller*, not a pattern
outcome the worker itself observed.

### Error handling

`Error` is never wrapped into `Outcome` (see "Error handling" in `docs/architecture/ARCHITECTURE.md`). Because the
operation runs on a separate worker thread, `Timeout` must catch `Error` there to observe it at all; it stores the
`Error` and rethrows it unchanged on the caller's thread once `join()` confirms the worker finished — the caller
never sees an `Outcome` for that call, only the rethrown `Error` itself. A `TimeoutEvent.Failed` is still emitted
first, carrying the `Error` as its cause, the same event type used for an ordinary `Exception` failure. If the
deadline already passed before the worker's `Error` is observed, the caller already received `Outcome.TimedOut`
instead, and the `Error`'s eventual disposition is only reported best-effort via `TimeoutEvent.AbandonedWorkerFailed`
— same as any other abandoned-worker outcome (see above).

---

## Configuration surface

| Property           | Required | Description                                                     |
|---------------------|----------|-------------------------------------------------------------------|
| `timeout`           | yes      | Maximum time allowed for the operation                            |
| `cancelOnTimeout`   | no       | Whether to interrupt the thread on timeout. Default: true         |
| `withListener()`    | no       | Subscribe to timeout events (e.g. TimedOut, Succeeded, Failed)     |
| `withClock()`       | no       | Custom Clock for event timestamps (testing). Does not affect the deadline, which is enforced against real elapsed time |

---

## Events

- **TimedOut** — the operation exceeded the limit. Carries: configured limit.
- **Succeeded** — the operation completed within the limit. Carries: elapsed time.
- **Failed** — the operation threw before the timeout elapsed. Carries: the thrown exception.
- **AbandonedWorkerSucceeded** — a worker abandoned after `TimedOut` eventually completed successfully. Observability
  only; the caller already received `Outcome.TimedOut` and does not get this result.
- **AbandonedWorkerFailed** — a worker abandoned after `TimedOut` eventually threw. Carries: the thrown cause.
  Observability only, same as above.

`AbandonedWorkerSucceeded` / `AbandonedWorkerFailed` are best-effort and may be missed. Right at the deadline
boundary, the caller (deciding whether the worker already finished) and the worker (deciding whether the deadline
already passed) each resolve that boundary independently. If the caller concludes the deadline passed — taking the
`TimedOut` path, which never inspects the worker's result again — while the worker, an instant earlier, concluded it
was still within budget and skipped its own event on the assumption that the caller's normal-completion path would
emit `Succeeded`/`Failed` instead, then neither event is emitted for that execution. This never affects the caller:
it still reliably gets exactly one `Outcome`/exception. Only the abandoned worker's eventual disposition may go
unreported.

---

## Failure

Throws `ResilientTimeoutException`. Fields: configured limit.
