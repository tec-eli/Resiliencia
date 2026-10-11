module io.github.teceli.resiliencia.spring {
    requires io.github.teceli.resiliencia.core;
    requires io.github.teceli.resiliencia.patterns;
    requires io.github.teceli.resiliencia.compose;
    requires spring.context;
    requires spring.aop;
    requires spring.beans;
    requires spring.boot.autoconfigure;
    requires spring.core;
    requires org.jspecify;

    exports io.github.teceli.resiliencia.spring.annotation;
    exports io.github.teceli.resiliencia.spring.aop;
    exports io.github.teceli.resiliencia.spring.config;

    // Spring reflectively invokes advised target methods and instantiates @Configuration /
    // ImportBeanDefinitionRegistrar classes via spring-core's ReflectionUtils; under the module
    // system that requires an explicit opens, not just exports, to that module.
    opens io.github.teceli.resiliencia.spring.aop to spring.core;
    opens io.github.teceli.resiliencia.spring.config to spring.core;
}
