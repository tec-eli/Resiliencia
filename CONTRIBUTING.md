# Contributing — resiliencia

Thank you for your interest in contributing. This document explains how the project is organized and how to participate effectively.

---

## Before you start

Requirements:
- Java 21 or higher
- Maven 3.9+
- Basic familiarity with virtual threads and Java concurrency

If this is your first time contributing to a Java library, the ADRs in `docs/adr/` are the best entry point. They explain the *why* behind every major structural decision.

---

## Project structure

```
resiliencia/
├── resiliencia-core/           ← Interfaces, base types, SPI
├── resiliencia-patterns/       ← The 5 resilience patterns
├── resiliencia-compose/        ← Policy and composition
├── resiliencia-metrics/        ← Observability (Micrometer / OTel) — post-v1
├── resiliencia-examples/       ← Usage examples (not published)
├── docs/
│   ├── adr/                    ← Architecture Decision Records
│   ├── ARCHITECTURE.md
│   ├── ROADMAP.md
│   └── SPEC.md
├── README.md
└── CONTRIBUTING.md
```

---

## Non-negotiable design rules

Read these before writing code. They are not preferences — they are constraints.

### 1. The public API is a contract
Everything in `api/` packages is public and stable. A breaking change there affects every user. If you need to change something in `api/`, open an issue first and discuss it before writing code.

### 2. `internal/` is package-private
Classes in `internal/` must not be accessible from outside the module. The Java Module System enforces this. If you need to expose something, move it to `api/` or `spi/` deliberately.

### 3. Zero external dependencies in core and patterns
`resiliencia-core` and `resiliencia-patterns` have no external dependencies. If something requires a third-party library, it goes in a separate optional module.

### 4. Virtual threads, not platform thread pools
Implementations use virtual threads. Do not create `ExecutorService` instances backed by platform threads to implement patterns.

### 5. Modern Java idioms
Prefer records, sealed classes, and pattern matching where they express the domain more clearly than traditional classes. Avoid anonymous classes and raw types.

---

## Workflow

### Reporting a bug

1. Check that there is no open issue for the same problem
2. Open an issue with:
   - Java version and OS
   - Minimal code that reproduces the problem
   - Expected behavior vs actual behavior

### Proposing a feature

1. Open an issue describing the use case — not the implementation
2. Wait for feedback before starting to code
3. If it is a significant design decision, it may result in a new ADR

### Submitting a PR

1. Fork the repository
2. Create a descriptive branch: `feature/retry-jitter`, `fix/circuit-breaker-race`
3. Write tests alongside or before the implementation
4. Verify everything passes: `mvn verify`
5. Update documentation if the public API changes
6. Open the PR with a clear description of what changes and why

---

## Running tests

```bash
# Run all tests
mvn verify

# Run tests for a specific module
mvn verify -pl resiliencia-patterns

# Run a specific test class
mvn test -pl resiliencia-patterns -Dtest=RetryTest
```

### What to test

- Happy path — the pattern does not interfere with a successful call
- Failure path — the pattern activates correctly
- Concurrency — patterns must be thread-safe under concurrent load
- Configuration edge cases — zero attempts, zero duration, etc.

Internal packages may require `--add-opens` in the Surefire configuration. This is already set up in the parent POM. Do not expose internal packages to work around this.

---

## Code style

- 4-space indentation
- Maximum line length: 120 characters
- Javadoc on all public API (`api/` and `spi/`)
- Code comments in English
- Descriptive names over abbreviations — clarity over brevity

---

## ADR — Architecture Decision Records

When a contribution involves a significant design decision, document it as a new ADR in `docs/adr/`. Look at the existing ADRs for format reference. Include the ADR in the same PR as the code.

---

## Code of conduct

Be respectful. Technical disagreements are expected and healthy — focus on the ideas, not the people.
