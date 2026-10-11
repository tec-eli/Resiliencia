package io.github.teceli.resiliencia.spring.config;

import io.github.teceli.resiliencia.core.api.Outcome;
import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.spring.annotation.Retry;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ResilientAnnotationAutoConfigurationTest {

    interface Greeter {
        String greet();
    }

    static class GreeterImpl implements Greeter {
        @Retry("orderRetry")
        @Override
        public String greet() {
            return "hello";
        }
    }

    @Configuration
    @Import(ResilientAnnotationAutoConfiguration.class)
    static class TestConfig {

        @Bean
        Greeter greeter() {
            return new GreeterImpl();
        }

        @Bean
        Resilient<Object> orderRetry() {
            return new Resilient<>() {
                @Override
                public Object call(Operation<Object> operation) {
                    return operation.execute();
                }

                @Override
                public Outcome<Object> outcome(Operation<Object> operation) {
                    throw new UnsupportedOperationException("not used by this test");
                }
            };
        }
    }

    @Test
    void should_proxyAndApplyNamedRetryBean_when_contextIsAutoConfigured() {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            var greeter = context.getBean(Greeter.class);

            assertThat(AopUtils.isAopProxy(greeter)).isTrue();
            assertThat(greeter.greet()).isEqualTo("hello");
        }
    }
}
