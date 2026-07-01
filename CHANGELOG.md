# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
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

