package cn.interview;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class V2BootstrapConfiguration implements WebMvcConfigurer {
    private final Environment environment;
    public V2BootstrapConfiguration(Environment environment) { this.environment = environment; }

    static boolean enabled(Environment env) {
        String value = env.getProperty("app.v2.enabled", env.getProperty("V2_ENABLED", "false"));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalStateException("V2_CONFIGURATION_INVALID：V2_ENABLED 只能为 true/false");
        return Boolean.parseBoolean(value);
    }

    @Bean
    static BeanFactoryPostProcessor v2Preflight(Environment env) {
        return factory -> {
            V2SchemaPreflight.check(Path.of(env.getProperty("app.data-dir", "./data")), enabled(env));
            var definition = factory.getBeanDefinition("interviewService");
            if (!InterviewService.class.getName().equals(definition.getBeanClassName()))
                throw new IllegalStateException("V2_CONFIGURATION_INVALID：会话服务扩展点已改变");
            definition.setBeanClassName(TrainingLinkedInterviewService.class.getName());
        };
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!enabled(environment)) throw V2Exception.disabled();
                return true;
            }
        }).addPathPatterns("/api/v2/**").excludePathPatterns("/api/v2/status");
    }
}
