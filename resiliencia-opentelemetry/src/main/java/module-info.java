module io.github.teceli.resiliencia.opentelemetry {
    requires io.github.teceli.resiliencia.core;
    requires io.github.teceli.resiliencia.metrics;
    requires io.github.teceli.resiliencia.patterns;
    requires io.opentelemetry.api;
    // Only referenced by the test-scope Meter fake (Context-accepting instrument overloads)
    // already a transitive dependency of opentelemetry-api.
    requires io.opentelemetry.context;

    exports io.github.teceli.resiliencia.opentelemetry;
}
