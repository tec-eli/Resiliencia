package io.github.teceli.resiliencia.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a named {@code Retry} pattern bean to the annotated method via Spring Proxy AOP.
 *
 * <p>{@link #value()} is resolved from the enclosing {@code ApplicationContext} by bean name,
 * consistent with the "no standalone registry" principle: the {@code ApplicationContext} is the
 * single source of truth for pattern beans, so lookups go through Spring's own bean resolution
 * rather than a separate name-to-instance store.
 *
 * <pre>{@code
 * @Retry("orderRetry")
 * public Order placeOrder(OrderRequest request) { ... }
 * }</pre>
 *
 * <p>Stacking this annotation together with another single-pattern annotation on the same method
 * is not supported — compose multiple patterns with a named {@code Policy} bean instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Retry {

    /**
     * The bean name of the {@code io.github.teceli.resiliencia.patterns.retry.Retry} instance to
     * apply, resolved from the {@code ApplicationContext}.
     */
    String value();
}
