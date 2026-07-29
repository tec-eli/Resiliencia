package io.github.teceli.resiliencia.spring.aop;

import io.github.teceli.resiliencia.core.api.Resilient;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;

import java.io.Serial;
import java.util.Objects;

/**
 * Spring Proxy AOP interceptor that wraps an advised method invocation with a {@link Resilient}
 * bean (single pattern or {@code Policy}) resolved by name from the enclosing {@link BeanFactory}.
 *
 * The {@link BeanFactory} lookup is the only bean resolution this class performs — there is no
 * separate name-to-instance registry.
 */
public final class ResilientMethodInterceptor implements MethodInterceptor {

    private final BeanFactory beanFactory;
    private final String resilientBeanName;

    public ResilientMethodInterceptor(BeanFactory beanFactory, String resilientBeanName) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory must not be null");
        this.resilientBeanName = Objects.requireNonNull(resilientBeanName, "resilientBeanName must not be null");
    }

    /**
     * Resolves the {@link Resilient} bean by name and runs the advised invocation through it.
     *
     * <p>{@link BeanFactory#getBean(String, Class)} only accepts the raw {@code Resilient} class
     * token as a lookup key, so binding the result to {@code Resilient<Object>} relies on an
     * unchecked conversion; the actual type parameter is erased regardless, since this
     * interceptor only ever deals in {@code Object} return values.
     *
     * @return the advised method's result, or {@code null} if the advised method itself returns
     *         {@code null} (including {@code void} methods)
     */
    @Override
    @SuppressWarnings("unchecked")
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        Resilient<@Nullable Object> resilient = beanFactory.getBean(resilientBeanName, Resilient.class);
        try {
            return resilient.call(() -> proceedOrWrapChecked(invocation));
        } catch (CheckedProceedException wrapped) {
            throw wrapped.getCause();
        }
    }

    /**
     * Bridges {@link MethodInvocation#proceed()}'s {@code throws Throwable} into
     * {@link Resilient.Operation#execute()}'s unchecked-only signature by wrapping any checked
     * exception so it can cross the {@code Operation} lambda boundary. {@link #invoke} only
     * unwraps this again if the resolved {@code Resilient} propagates it untouched (e.g. a
     * pass-through pattern) — a pattern that translates the failure into its own exception type
     * (e.g. {@code RetryExhaustedException}) is left alone, since that translation is the
     * documented, intended behavior, not an artifact of this bridging.
     */
    private static @Nullable Object proceedOrWrapChecked(MethodInvocation invocation) {
        try {
            return invocation.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new CheckedProceedException(e);
        }
    }

    private static final class CheckedProceedException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        CheckedProceedException(Throwable cause) {
            super(cause);
        }
    }
}
