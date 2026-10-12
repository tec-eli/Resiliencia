package io.github.teceli.resiliencia.spring.aop;

import io.github.teceli.resiliencia.core.api.Resilient;
import io.github.teceli.resiliencia.spring.annotation.Retry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnotationResilientAdvisorTest {

    private static final String BEAN_NAME = "orderRetry";

    @Mock
    private BeanFactory beanFactory;

    @Mock
    private Resilient<Object> resilient;

    interface Greeter {
        String greet();

        String plainGreet();
    }

    static class GreeterImpl implements Greeter {
        @Retry(BEAN_NAME)
        @Override
        public String greet() {
            return "hello";
        }

        @Override
        public String plainGreet() {
            return "hi";
        }
    }

    private Greeter proxiedGreeter() {
        var advisor = new AnnotationResilientAdvisor(beanFactory, Retry.class, Retry::value);
        var factory = new ProxyFactory(new GreeterImpl());
        factory.addAdvisor(advisor);
        return (Greeter) factory.getProxy();
    }

    @Test
    void should_applyResilientBean_when_methodCarriesRetryAnnotation() {
        when(beanFactory.getBean(BEAN_NAME, Resilient.class)).thenReturn(resilient);
        when(resilient.call(any())).thenAnswer(inv -> {
            Resilient.Operation<Object> operation = inv.getArgument(0);
            return operation.execute();
        });

        var result = proxiedGreeter().greet();

        assertThat(result).isEqualTo("hello");
        verify(beanFactory).getBean(BEAN_NAME, Resilient.class);
    }

    @Test
    void should_skipAdvice_when_methodHasNoRetryAnnotation() {
        var result = proxiedGreeter().plainGreet();

        assertThat(result).isEqualTo("hi");
    }
}
