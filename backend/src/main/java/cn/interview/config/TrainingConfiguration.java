package cn.interview.config;

import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingSchemaPreflight;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class TrainingConfiguration implements WebMvcConfigurer {
    private final Environment environment;
    public TrainingConfiguration(Environment environment) { this.environment = environment; }

    public static boolean enabled(Environment env) {
        String value = env.getProperty("app.training.enabled", env.getProperty("TRAINING_ENABLED", env.getProperty("app.v2.enabled", env.getProperty("V2_ENABLED", "false"))));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalStateException("V2_CONFIGURATION_INVALID：V2_ENABLED 只能为 true/false");
        return Boolean.parseBoolean(value);
    }

    @Bean
    static BeanFactoryPostProcessor trainingSchemaPreflight(Environment env) {
        return factory -> TrainingSchemaPreflight.check(
                Path.of(env.getProperty("app.data-dir", "./data")), enabled(env));
    }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!enabled(environment)) throw RecoveryException.disabled();
                return true;
            }
        }).addPathPatterns("/api/training/**", "/api/v2/**").excludePathPatterns("/api/training/status", "/api/v2/status");
    }
}
