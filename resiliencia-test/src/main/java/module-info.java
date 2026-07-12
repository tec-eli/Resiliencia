module io.github.teceli.resiliencia.test {
    requires transitive io.github.teceli.resiliencia.core;
    requires io.github.teceli.resiliencia.metrics;
    requires org.junit.jupiter.api;
    requires transitive org.assertj.core;

    exports io.github.teceli.resiliencia.test;
}
