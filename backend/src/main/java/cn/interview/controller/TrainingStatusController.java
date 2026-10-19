package cn.interview.controller;

import cn.interview.config.TrainingConfiguration;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrainingStatusController {
    private final Environment environment;
    public TrainingStatusController(Environment environment) { this.environment = environment; }
    @GetMapping({"/api/training/status", "/api/v2/status"})
    Map<String, Boolean> status() { return Map.of("enabled", TrainingConfiguration.enabled(environment)); }
}
