module io.github.teceli.resiliencia.patterns {
    requires io.github.teceli.resiliencia.core;
    requires org.slf4j;
    exports io.github.teceli.resiliencia.patterns.bulkhead;
    exports io.github.teceli.resiliencia.patterns.circuitbreaker;
    exports io.github.teceli.resiliencia.patterns.ratelimiter;
    exports io.github.teceli.resiliencia.patterns.retry;
    exports io.github.teceli.resiliencia.patterns.timeout;
}
