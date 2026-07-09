# Spec — Timeout

Cancels an operation if it does not complete within a configured duration.

---

## Behavior

The operation runs on a virtual thread. A timer starts when the call begins. If the operation completes before the timer
expires, the result is returned normally. If the timer expires first, the calling thread receives a
`ResilienciaTimeoutException` immediately.

Cancellation is real — the virtual thread running the operation is interrupted, not polled. Operations that do not
respond to interruption will continue running in the background but their result is discarded.

A `cancelOnTimeout` flag controls whether the virtual thread is interrupted on timeout. When set to false, the caller
still receives the exception immediately, but the operation thread is allowed to finish naturally. This is useful when
the operation holds resources that must be released cleanly.

---

## Configuration surface

| Property          | Required | Description                                               |
|-------------------|----------|-----------------------------------------------------------|
| `timeout`         | yes      | Maximum time allowed for the operation                    |
| `cancelOnTimeout` | no       | Whether to interrupt the thread on timeout. Default: true |

---

## Events

- **TimedOut** — the operation exceeded the limit. Carries: configured limit.
- **Succeeded** — the operation completed within the limit. Carries: elapsed time.
- **Failed** — the operation threw before the timeout elapsed. Carries: the thrown exception.
- **AbandonedWorkerSucceeded** — a worker abandoned after `TimedOut` eventually completed successfully. Observability
  only; the caller already received `Outcome.TimedOut` and does not get this result.
- **AbandonedWorkerFailed** — a worker abandoned after `TimedOut` eventually threw. Carries: the thrown cause.
  Observability only, same as above.

---

## Failure

Throws `ResilienciaTimeoutException`. Fields: configured limit.
