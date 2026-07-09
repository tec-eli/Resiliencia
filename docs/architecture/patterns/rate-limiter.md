# Spec — RateLimiter

Limits the frequency of calls over a time window. Callers wait for a permit; if none is available within the configured 
timeout, the call is rejected.

---

## Behavior

Permits are issued up to `limitForPeriod` per `limitRefreshPeriod`. At the start of each period the full permit count is 
restored. Unused permits do not carry over to the next period.

When a call arrives, it attempts to acquire a permit. If one is available, the call proceeds immediately. If not, the 
caller waits up to `timeoutDuration`. If no permit becomes available within that window, the call is rejected.

`timeoutDuration` of zero means reject immediately if no permit is available.

---

## Configuration surface

| Property | Required | Description |
|---|---|---|
| `limitForPeriod` | yes | Maximum calls allowed per refresh period |
| `limitRefreshPeriod` | yes | How often the permit count resets |
| `timeoutDuration` | no | How long to wait for a permit before rejecting. Default: no wait |

---

## Events

- **PermitAcquired** — a permit was granted, call will proceed. Carries: remaining permits in this period.
- **PermitRejected** — no permit available within timeout. Carries: estimated time until next permit.

---

## Failure

Throws `RateLimiterException` when no permit is available within `timeoutDuration`. Fields: name, retry after.
