# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `resiliencia-patterns`: `CircuitBreaker` pattern — Closed/Open/HalfOpen state machine driven by a sliding window
  of failure and slow-call rates, with inspectable current state and a structured `CircuitBreakerOpenException`
  (name, open since, remaining wait).
- `resiliencia-patterns`: `Retry.withMaxDelay(...)` — hard cap on backoff growth — and `Retry.withJitter(...)` —
  uniform delay randomization to avoid thundering herds.
- `resiliencia-test`: test helpers for library users — `ManualClock` (deterministic `Clock`), `FakeResilient`
  (pass-through, pattern-impersonating fake), `ResilienciaAssertions` (AssertJ assertions for `Outcome`).
- `resiliencia-core`: default `Resilient.callAsync` — runs `call()` on a virtual thread per call; cancelling the
  returned future interrupts the thread, propagating cancellation through pattern and Policy chains.
- `resiliencia-patterns`: `Timeout` pattern — virtual-thread execution with real interruption on deadline.
- `resiliencia-patterns`: `Bulkhead` pattern — semaphore-based concurrency limit, fail-fast or bounded wait.
- `resiliencia-patterns`: `RateLimiter` pattern — fixed-window limit, fail-fast or bounded wait, Clock-driven.
- `resiliencia-compose`: `Policy` order validation — rejects Retry-wraps-CircuitBreaker with
  `InvalidPolicyException`, warns on Timeout-wraps-Retry; `Policy.useDefault(...)` composes patterns in the
  recommended order (RateLimiter → CircuitBreaker → Bulkhead → Retry → Timeout) regardless of input order.
- Multi-module Maven project scaffold for all planned modules (`core`, `patterns`, `compose`, `metrics`, `micrometer`, 
  `opentelemetry`, `spring`, `quarkus`, `micronaut`, `test`, `stress`, `examples`), each with its own `module-info.java` 
  enforcing JPMS encapsulation.
- `resiliencia-core`: the `Resilient` contract (`call`, `outcome`), sealed `Outcome<T>` result type, and the unchecked 
  exception hierarchy (`ResilienciaException`, `RetryExhaustedException`, `InvalidPolicyException`).
- `resiliencia-patterns`: `Retry` pattern with configurable max attempts, fixed/exponential backoff, conditional retry, 
  and event listeners.
- `resiliencia-compose`: `Policy` — fluent composition of multiple resilience patterns into a single execution chain 
    (`compose()`/`and()`), with defined outermost-to-innermost ordering and pattern-specific exception propagation.
    `resiliencia-examples`: usage example demonstrating the Retry pattern.

