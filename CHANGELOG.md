# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Five resilience patterns: Retry, Circuit Breaker, Timeout, Bulkhead, and Rate Limiter
- Fluent API for combining multiple patterns together
- Async execution support with cancellation via virtual threads
- Result-based error handling with `outcome()` method (no exceptions required)
- Pattern ordering validation to prevent common misconfigurations
- Test utilities for easier unit testing: `ManualClock`, `FakeResilient`, `ResilienciaAssertions`
- Multi-module architecture with framework integrations (Spring, Quarkus, Micrometer planned)

