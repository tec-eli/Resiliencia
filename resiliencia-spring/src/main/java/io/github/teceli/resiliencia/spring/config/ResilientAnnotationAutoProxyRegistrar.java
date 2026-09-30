package io.github.teceli.resiliencia.spring.config;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Registers Spring's shared auto-proxy-creator bean if the application context does not already
 * have one, via {@link AopConfigUtils#registerAutoProxyCreatorIfNecessary}. That method is the
 * same entry point {@code @EnableAspectJAutoProxy} uses internally: it upgrades an existing
 * auto-proxy-creator in place rather than registering a second, competing one, so this never
 * causes a bean to be proxied twice regardless of what other AOP infrastructure (e.g.
 * {@code @Transactional}, {@code @Async}) the consumer's application already has active.
 */
final class ResilientAnnotationAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
    }
}
