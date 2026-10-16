package cn.interview;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class V2StatusController {
    private final Environment environment;
    public V2StatusController(Environment environment) { this.environment = environment; }
    @GetMapping("/api/v2/status")
    Map<String, Boolean> status() { return Map.of("enabled", V2BootstrapConfiguration.enabled(environment)); }
}
