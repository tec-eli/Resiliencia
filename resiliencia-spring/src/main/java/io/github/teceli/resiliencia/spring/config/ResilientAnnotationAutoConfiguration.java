package io.github.teceli.resiliencia.spring.config;

import io.github.teceli.resiliencia.spring.annotation.Retry;
import io.github.teceli.resiliencia.spring.aop.AnnotationResilientAdvisor;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Role;

/**
 * Autoconfigures the {@link AnnotationResilientAdvisor} beans backing the single-pattern
 * annotations ({@code @Retry}, {@code @CircuitBreaker}, {@code @Timeout}, {@code @Bulkhead},
 * {@code @RateLimiter}) and, via {@link ResilientAnnotationAutoProxyRegistrar}, the Spring AOP
 * infrastructure that applies them. Picked up automatically on the classpath through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} — no
 * action required from the consumer.
 */
@Configuration(proxyBeanMethods = false)
@Import(ResilientAnnotationAutoProxyRegistrar.class)
public class ResilientAnnotationAutoConfiguration {

    /**
     * {@code ROLE_INFRASTRUCTURE} is required here, not just conventional: the auto-proxy-creator
     * {@link ResilientAnnotationAutoProxyRegistrar} registers only applies {@code Advisor} beans
     * carrying that role.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public AnnotationResilientAdvisor retryResilientAdvisor(BeanFactory beanFactory) {
        return new AnnotationResilientAdvisor(beanFactory, Retry.class, Retry::value);
    }
}
