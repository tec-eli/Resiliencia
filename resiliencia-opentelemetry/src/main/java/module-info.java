module io.github.teceli.resiliencia.opentelemetry {
    requires io.github.teceli.resiliencia.core;
    requires io.github.teceli.resiliencia.metrics;
    requires io.github.teceli.resiliencia.patterns;
    requires io.opentelemetry.api;
    // Only referenced by the test-scope Meter fake (Context-accepting instrument overloads).
    // static: compile-time only — production code never touches this type, and it's already a
    // transitive runtime dependency of opentelemetry-api, so a real `requires` here would overstate
    // this module's actual production dependency graph.
    requires static io.opentelemetry.context;

    exports io.github.teceli.resiliencia.opentelemetry;
}
