# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [1.0.0-beta.1] - 2026-08-23

### Added
- Five resilience patterns: Retry, Circuit Breaker, Timeout, Bulkhead, and Rate Limiter, built on virtual threads
- `Policy`: fluent composition of multiple patterns into a single, explicitly ordered execution chain
- Pattern ordering validation, rejecting unsafe compositions (e.g. Retry wrapping CircuitBreaker) at construction time
- Async execution support with cancellation via virtual thread interruption
- Result-based error handling with `outcome()` method (no exceptions required)
- Vendor-neutral metrics abstraction (`resiliencia-metrics`) with Micrometer and OpenTelemetry backends
- Test utilities for consumers: `ManualClock`, `FakeResilient`, `ResilienciaAssertions`
- Multi-module architecture with `module-info.java` boundaries on every module

