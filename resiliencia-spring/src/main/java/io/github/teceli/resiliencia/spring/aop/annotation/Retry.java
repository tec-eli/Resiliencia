package io.github.teceli.resiliencia.spring.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Advises the annotated method with the {@code Retry} bean named by {@link #value()}, resolved from the
 * Spring {@code ApplicationContext} at invocation time.
 *
 * <pre>{@code
 * @Retry("orderRetry")
 * public Order placeOrder(OrderRequest request) { ... }
 * }</pre>
 *
 * Applied via Spring Proxy AOP: self-invocation is not intercepted, only public methods are advised, and
 * {@code final} classes/methods are not advised under CGLIB.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {

    /**
     * The name of the {@code Retry} bean to resolve from the {@code ApplicationContext}.
     */
    String value();
}
