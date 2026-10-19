package cn.interview.controller.model;

import cn.interview.client.model.ModelConnectionProbe;
import cn.interview.service.model.ModelConfigurationService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.*;

/** 仅本机应用可写；浏览器始终接收不含密钥的公开视图。 */
@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {
    private final ModelConfigurationService settings;
    private final ModelConnectionProbe probe;
    public ModelConfigController(ModelConfigurationService settings, ModelConnectionProbe probe) { this.settings = settings; this.probe = probe; }
    @GetMapping public ObjectNode get() { return settings.view(); }
    @PutMapping public ObjectNode save(@RequestBody JsonNode body) { return settings.save(body); }
    @PostMapping("/reset") public ObjectNode reset(@RequestBody JsonNode body) { return settings.reset(body); }
    @PostMapping("/test") public ObjectNode test(@RequestBody JsonNode body) { return probe.test(settings.draft(body)); }
    @PostMapping("/models") public ObjectNode models(@RequestBody JsonNode body) { return probe.models(settings.draft(body, false)); }
}
