package io.github.teceli.resiliencia.spring.aop;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.BeanFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Function;

/**
 * Spring {@code Advisor} for a single-pattern annotation (e.g. {@code @Retry}): matches methods
 * carrying that annotation and, on each advised invocation, resolves the pattern bean named by
 * the annotation's {@code value()} and applies it via {@link ResilientMethodInterceptor}.
 *
 * <p>One instance covers one annotation type. Each of the five single-pattern annotations
 * ({@code @Retry}, {@code @CircuitBreaker}, {@code @Timeout}, {@code @Bulkhead},
 * {@code @RateLimiter}) is wired with its own {@code AnnotationResilientAdvisor} bean, since a
 * method carries at most one such annotation, resolving one named pattern bean.
 */
public final class AnnotationResilientAdvisor extends DefaultPointcutAdvisor {

    public <A extends Annotation> AnnotationResilientAdvisor(
            BeanFactory beanFactory, Class<A> annotationType, Function<A, String> beanNameExtractor) {
        super(AnnotationMatchingPointcut.forMethodAnnotation(annotationType),
                new DelegatingMethodInterceptor<>(beanFactory, annotationType, beanNameExtractor));
    }

    /**
     * Reads the annotation off the advised method on every invocation (rather than once at
     * construction) because one {@code Advisor} is shared across every method its pointcut
     * matches, and each of those methods can name a different pattern bean.
     */
    private record DelegatingMethodInterceptor<A extends Annotation>(
            BeanFactory beanFactory, Class<A> annotationType, Function<A, String> beanNameExtractor)
            implements MethodInterceptor {

        private DelegatingMethodInterceptor {
            Objects.requireNonNull(beanFactory, "beanFactory must not be null");
            Objects.requireNonNull(annotationType, "annotationType must not be null");
            Objects.requireNonNull(beanNameExtractor, "beanNameExtractor must not be null");
        }

        @Override
        public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass(invocation));
            A annotation = method.getAnnotation(annotationType);
            String beanName = beanNameExtractor.apply(annotation);
            return new ResilientMethodInterceptor(beanFactory, beanName).invoke(invocation);
        }

        private static @Nullable Class<?> targetClass(MethodInvocation invocation) {
            Object target = invocation.getThis();
            return target != null ? target.getClass() : null;
        }
    }
}
