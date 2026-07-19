# Spec — RateLimiter

Limits the frequency of calls over a time window. Callers wait for a permit; if none is available within the configured
timeout, the call is rejected.

---

## Behavior

Permits are issued up to `limit` per `period`. At the start of each period the full permit count is
restored. Unused permits do not carry over to the next period.

When a call arrives, it attempts to acquire a permit. If one is available, the call proceeds immediately. If not, the
caller waits up to `maxWait`. If no permit becomes available within that window, the call is rejected.

`maxWait` of zero means reject immediately if no permit is available.

### Extreme values

`Instant` arithmetic (`plus`) throws `ArithmeticException` on `long` overflow and `DateTimeException` when the
result falls outside `Instant`'s representable range — both reachable in practice: a huge `maxWait`, a period left
idle for centuries, or a custom `Clock` (a documented extension point) returning an `Instant` near `Instant.MAX`.
RateLimiter never lets either escape:

- Durations that would overflow `Duration.toMillis()` are clamped to `Long.MAX_VALUE` millis first.
- Any `Instant.plus` that would still overflow is either clamped to `Instant.MAX` (`safePlus`, used for deadlines —
  an unreachable deadline just means the wait is correctly never satisfied) or treated as "unreachable, reject
  immediately" (the next window's start, in `tryAcquire` — no `maxWait`, however generous, could wait that out) or
  falls back to resetting the window straight to the current instant (`advanceWindow`, for idle periods so long that
  even the clamped elapsed duration can't be multiplied back into a valid `Instant`).

This mirrors `CircuitBreaker.openDeadline()`, which handles the same `Instant`-near-`Instant.MAX` case the same way.

---

## Configuration surface

| Property  | Required | Description                                                                                                          |
|-----------|----------|------------------------------------------------------------------------------------------------------------------------|
| `name`    | yes      | Identifier used in events and exceptions (instance-specific). First positional argument of `of(name, limit, period)`, no wither |
| `limit`   | yes      | Maximum calls allowed per period                                                                                     |
| `period`  | yes      | How often the permit count resets                                                                                    |
| `maxWait` | no       | How long to wait for a permit before rejecting. Default: no wait                                                     |

---

## Events

Every `RateLimiterEvent` carries `name` (the identifier passed to `of(name, limit, period)`), in addition to the
fields below.

- **Permitted** — a permit was granted, call will proceed. Carries: remaining permits in this period.
- **Rejected** — no permit available within timeout. Carries: estimated time until next permit.

---

## Failure

Throws `RateLimiterException` when no permit is available within `maxWait`. Fields: name, limit, period, max wait.
