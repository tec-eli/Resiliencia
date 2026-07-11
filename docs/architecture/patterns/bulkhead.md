# Spec — Bulkhead

Limits the number of concurrent calls to a resource. Protects the downstream service from being overwhelmed. Implemented
with a semaphore — compatible with virtual threads.

---

## Behavior

A semaphore with a fixed number of permits controls access. Each call acquires a permit before executing and releases it
when done, whether the operation succeeds or fails.

If no permit is available, the caller waits up to `maxWait` for one to become free. If the wait elapses without
a permit, the call is rejected. If `maxWait` is zero, calls are rejected immediately when the limit is reached.

---

## Configuration surface

| Property             | Required | Description                                                                                                                            |
|----------------------|----------|------------------------------------------------------------------------------------------------------------------------------------------|
| `name`               | yes      | Identifier used in events and exceptions (instance-specific). First positional argument of `of(name, maxConcurrentCalls)`, no wither   |
| `maxConcurrentCalls` | yes      | Maximum number of concurrent executions                                                                                                |
| `maxWait`            | no       | How long to wait for a permit before rejecting. Default: no wait                                                                       |

---

## Events

Every `BulkheadEvent` carries `name` (the identifier passed to `of(name, maxConcurrentCalls)`), in addition to the
fields below.

- **Permitted** — a permit was acquired, call will proceed. Carries: current active call count.
- **Rejected** — no permit available within wait duration. Carries: max concurrent calls limit, configured max wait.
- **Finished** — a call completed and its permit was released. Carries: current active call count.

---

## Failure

Throws `BulkheadFullException` when the concurrency limit is reached and `maxWait` elapses. Fields: name, max
concurrent calls, max wait.
