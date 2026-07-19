module io.github.teceli.resiliencia.micrometer {
    requires io.github.teceli.resiliencia.core;
    requires io.github.teceli.resiliencia.metrics;
    requires io.github.teceli.resiliencia.patterns;
    requires micrometer.core;
    requires org.slf4j;

    exports io.github.teceli.resiliencia.micrometer;
}
