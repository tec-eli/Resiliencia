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

---

## Configuration surface

| Property  | Required | Description                                                      |
|-----------|----------|------------------------------------------------------------------|
| `limit`   | yes      | Maximum calls allowed per period                                 |
| `period`  | yes      | How often the permit count resets                                |
| `maxWait` | no       | How long to wait for a permit before rejecting. Default: no wait |

---

## Events

- **Permitted** — a permit was granted, call will proceed. Carries: remaining permits in this period.
- **Rejected** — no permit available within timeout. Carries: estimated time until next permit.

---

## Failure

Throws `RateLimiterException` when no permit is available within `maxWait`. Fields: name, limit, period, max wait.
