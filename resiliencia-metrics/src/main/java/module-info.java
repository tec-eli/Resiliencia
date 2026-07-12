module io.github.teceli.resiliencia.metrics {
    requires io.github.teceli.resiliencia.core;
    requires io.github.teceli.resiliencia.patterns;
    requires io.github.teceli.resiliencia.compose;
    requires org.slf4j;

    exports io.github.teceli.resiliencia.metrics;
    exports io.github.teceli.resiliencia.metrics.circuitbreaker;
    exports io.github.teceli.resiliencia.metrics.bulkhead;
    exports io.github.teceli.resiliencia.metrics.ratelimiter;
    exports io.github.teceli.resiliencia.metrics.retry;
    exports io.github.teceli.resiliencia.metrics.timeout;
    exports io.github.teceli.resiliencia.metrics.policy;
}
