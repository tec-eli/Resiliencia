package io.github.teceli.resiliencia.spring.aop;

import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.core.api.ResilientException;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.BeanFactory;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientMethodInterceptorTest {

    private static final String BEAN_NAME = "orderRetry";

    @Mock
    private BeanFactory beanFactory;

    @Mock
    private MethodInvocation invocation;

    @Mock
    private Resilient<Object> resilient;

    private ResilientMethodInterceptor interceptor;

    private void givenResilientBean() {
        when(beanFactory.getBean(BEAN_NAME, Resilient.class)).thenReturn(resilient);
        interceptor = new ResilientMethodInterceptor(beanFactory, BEAN_NAME);
    }

    @Test
    void should_returnProceedResult_when_resilientPassesThrough() throws Throwable {
        givenResilientBean();
        when(invocation.proceed()).thenReturn("result");
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            return operation.execute();
        });

        var result = interceptor.invoke(invocation);

        assertThat(result).isEqualTo("result");
    }

    @Test
    void should_propagateResilientException_when_resilientTranslatesFailure() throws Throwable {
        givenResilientBean();
        var translated = new ResilientException("exhausted", new RuntimeException("boom"));
        when(invocation.proceed()).thenThrow(new RuntimeException("boom"));
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            try {
                operation.execute();
                throw new AssertionError("operation must throw");
            } catch (RuntimeException e) {
                throw translated;
            }
        });

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isSameAs(translated);
    }

    @Test
    void should_propagateOriginalCheckedException_when_resilientPropagatesItUntouched() throws Throwable {
        givenResilientBean();
        var checked = new IOException("disk full");
        when(invocation.proceed()).thenThrow(checked);
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            return operation.execute();
        });

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isSameAs(checked);
    }

    @Test
    void should_propagateOriginalRuntimeException_when_resilientPropagatesItUntouched() throws Throwable {
        givenResilientBean();
        var unchecked = new IllegalStateException("bad state");
        when(invocation.proceed()).thenThrow(unchecked);
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            return operation.execute();
        });

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isSameAs(unchecked);
    }

    @Test
    void should_propagateError_when_resilientPropagatesItUntouched() throws Throwable {
        givenResilientBean();
        var error = new StackOverflowError("boom");
        when(invocation.proceed()).thenThrow(error);
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            return operation.execute();
        });

        assertThatThrownBy(() -> interceptor.invoke(invocation))
                .isSameAs(error);
    }

    @Test
    void should_resolveResilientBeanByName_from_beanFactory() throws Throwable {
        givenResilientBean();
        when(invocation.proceed()).thenReturn("result");
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            return operation.execute();
        });

        interceptor.invoke(invocation);

        verify(beanFactory).getBean(BEAN_NAME, Resilient.class);
    }
}
