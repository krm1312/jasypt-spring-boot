package com.ulisesbocchio.jasyptspringboot;

import com.ulisesbocchio.jasyptspringboot.aop.EncryptableMutablePropertySourcesInterceptor;
import com.ulisesbocchio.jasyptspringboot.aop.EncryptablePropertySourceMethodInterceptor;
import com.ulisesbocchio.jasyptspringboot.wrapper.EncryptableEnumerablePropertySourceWrapper;
import com.ulisesbocchio.jasyptspringboot.wrapper.EncryptableMapPropertySourceWrapper;
import com.ulisesbocchio.jasyptspringboot.wrapper.EncryptablePropertySourceWrapper;
import com.ulisesbocchio.jasyptspringboot.wrapper.EncryptableSystemEnvironmentPropertySourceWrapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.env.*;

import java.lang.reflect.Modifier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toList;

/**
 * @author Ulises Bocchio
 */
@Slf4j
public class EncryptablePropertySourceConverter {

    public static void convertPropertySources(InterceptionMode interceptionMode, EncryptablePropertyResolver propertyResolver, EncryptablePropertyFilter propertyFilter, MutablePropertySources propSources) {
        StreamSupport.stream(propSources.spliterator(), false)
                .filter(ps -> !(ps instanceof EncryptablePropertySource))
                .map(ps -> makeEncryptable(interceptionMode, propertyResolver, propertyFilter, ps))
                .collect(toList())
                .forEach(ps -> propSources.replace(ps.getName(), ps));
    }

    @SuppressWarnings("unchecked")
    public static <T> PropertySource<T> makeEncryptable(InterceptionMode interceptionMode, EncryptablePropertyResolver propertyResolver, EncryptablePropertyFilter propertyFilter, PropertySource<T> propertySource) {
        if (propertySource instanceof EncryptablePropertySource) {
            return propertySource;
        }
        PropertySource<T> encryptablePropertySource = convertPropertySource(interceptionMode, propertyResolver, propertyFilter, propertySource);
        log.info("Converting PropertySource {} [{}] to {}", propertySource.getName(), propertySource.getClass().getName(),
                AopUtils.isAopProxy(encryptablePropertySource) ? "AOP Proxy" : encryptablePropertySource.getClass().getSimpleName());
        return encryptablePropertySource;
    }

    private static <T> PropertySource<T> convertPropertySource(InterceptionMode interceptionMode, EncryptablePropertyResolver propertyResolver, EncryptablePropertyFilter propertyFilter, PropertySource<T> propertySource) {
        return interceptionMode == InterceptionMode.PROXY
                ? proxyPropertySource(propertySource, propertyResolver, propertyFilter) : instantiatePropertySource(propertySource, propertyResolver, propertyFilter);
    }

    public static MutablePropertySources proxyPropertySources(InterceptionMode interceptionMode, EncryptablePropertyResolver propertyResolver, EncryptablePropertyFilter propertyFilter, MutablePropertySources propertySources) {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(MutablePropertySources.class);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addInterface(PropertySources.class);
        proxyFactory.setTarget(propertySources);
        proxyFactory.addAdvice(new EncryptableMutablePropertySourcesInterceptor(interceptionMode, propertyResolver, propertyFilter));
        return (MutablePropertySources) proxyFactory.getProxy();
    }

    @SuppressWarnings("unchecked")
    public static <T> PropertySource<T> proxyPropertySource(PropertySource<T> propertySource, EncryptablePropertyResolver resolver, EncryptablePropertyFilter propertyFilter) {
        //Silly Chris Beams for making CommandLinePropertySource getProperty and containsProperty methods final. Those methods
        //can't be proxied with CGLib because of it. So fallback to wrapper for Command Line Arguments only.
        if (CommandLinePropertySource.class.isAssignableFrom(propertySource.getClass())
            // Other PropertySource classes like org.springframework.boot.env.OriginTrackedMapPropertySource
            // are final classes as well
            || Modifier.isFinal(propertySource.getClass().getModifiers())) {
            return instantiatePropertySource(propertySource, resolver, propertyFilter);
        }            
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetClass(propertySource.getClass());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addInterface(EncryptablePropertySource.class);
        proxyFactory.setTarget(propertySource);
        proxyFactory.addAdvice(new EncryptablePropertySourceMethodInterceptor<>(propertySource, resolver, propertyFilter));
        return (PropertySource<T>) proxyFactory.getProxy();
    }

    @SuppressWarnings("unchecked")
    public static <T> PropertySource<T> instantiatePropertySource(PropertySource<T> propertySource, EncryptablePropertyResolver resolver, EncryptablePropertyFilter propertyFilter) {
        PropertySource<T> encryptablePropertySource;
        if (needsProxyAnyway(propertySource)) {
            encryptablePropertySource = proxyPropertySource(propertySource, resolver, propertyFilter);
        } else if (propertySource instanceof  SystemEnvironmentPropertySource) {
            encryptablePropertySource = (PropertySource<T>) new EncryptableSystemEnvironmentPropertySourceWrapper((SystemEnvironmentPropertySource) propertySource, resolver, propertyFilter);
        } else if (propertySource instanceof MapPropertySource) {
            encryptablePropertySource = (PropertySource<T>) new EncryptableMapPropertySourceWrapper((MapPropertySource) propertySource, resolver, propertyFilter);
        } else if (propertySource instanceof EnumerablePropertySource) {
            encryptablePropertySource = new EncryptableEnumerablePropertySourceWrapper<>((EnumerablePropertySource) propertySource, resolver, propertyFilter);
        } else {
            encryptablePropertySource = new EncryptablePropertySourceWrapper<>(propertySource, resolver, propertyFilter);
        }
        return encryptablePropertySource;
    }

    @SuppressWarnings("unchecked")
    private static boolean needsProxyAnyway(PropertySource<?> ps) {
        return needsProxyAnyway((Class<? extends PropertySource<?>>) ps.getClass());
    }

    private static boolean needsProxyAnyway(Class<? extends PropertySource<?>> psClass) {
        return needsProxyAnyway(psClass.getName());
    }

    /**
     *  Some Spring Boot code actually casts property sources to this specific type so must be proxied.
     */
    private static boolean needsProxyAnyway(String className) {
        return Stream.of(
                "org.springframework.boot.context.config.ConfigFileApplicationListener$ConfigurationPropertySources",
                "org.springframework.boot.context.properties.source.ConfigurationPropertySourcesPropertySource"
                ).anyMatch(className::equals);
    }
}
