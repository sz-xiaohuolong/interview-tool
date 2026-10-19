package cn.interview.client;

import cn.interview.exception.ApiException;
import cn.interview.dto.ModelSettings;
import cn.interview.service.ModelConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class FocusedInterviewModelClient {
    final ObjectMapper json;
    private final java.util.function.Supplier<ModelSettings> connections;
    final int timeout;
    final OpenAiJsonClient transport;
    final MockFocusedInterviewModel mock;

    @Autowired
    public FocusedInterviewModelClient(ObjectMapper json, ModelConfigurationService settings, @Value("${app.llm.timeout-seconds}") int timeout, OpenAiJsonClient transport) {
        this(json, settings::connection, timeout, transport);
    }
    public FocusedInterviewModelClient(ObjectMapper json, String provider, String baseUrl, String key, String model, int timeout, String reasoningEffort) {
        this(json, () -> new ModelSettings(provider, "CUSTOM", baseUrl, key, model, "", reasoningEffort.strip(), ""), timeout, new OpenAiJsonClient(json));
    }
    private FocusedInterviewModelClient(ObjectMapper json, java.util.function.Supplier<ModelSettings> connections, int timeout, OpenAiJsonClient transport) {
        this.json = json;
        this.connections = connections;
        this.timeout = Math.max(1, Math.min(timeout, 300));
        this.transport = transport;
        mock = new MockFocusedInterviewModel(json);
    }
    public ModelSettings connection() { return connections.get(); }
    public boolean isMock() { return connection().isMock(); }
    public Map<String, Object> health() {
        var s = connection();
        return Map.of("status", "ok", "provider", s.provider(), "configured", s.configured(), "model", s.isMock() ? "明确标识的测试模型" : s.model());
    }

    public ObjectNode call(String task, ObjectNode input) {
        var settings = connection();
        String provider = settings.provider(), baseUrl = settings.baseUrl(), key = settings.apiKey(), model = settings.model(), reasoningEffort = settings.effort(task.equals("interviewer") && settings.preset().equals("DEEPSEEK"));
        if (settings.isMock()) return mock.generate(task, input);
        if (!"openai".equals(provider))
            throw new ApiException(503, "MODEL_NOT_CONFIGURED", "LLM_PROVIDER 必须为 openai 或显式测试用 mock");
        if (key.isBlank() || model.isBlank())
            throw new ApiException(503, "MODEL_NOT_CONFIGURED", "模型尚未配置，请点击右上角设置服务地址、API Key 和模型 ID");
        try {
            String prompt = new ClassPathResource("prompts/" + task + ".txt").getContentAsString(StandardCharsets.UTF_8);
            var response = transport.exchange(settings, prompt, input, task.equals("evaluator") ? 6500 : 4500,
                    timeout, task.equals("interviewer") && settings.preset().equals("DEEPSEEK"), usage -> {});
            if (response.text() == null) throw ApiException.model("模型未返回有效的文本 JSON，请检查服务的结构化输出支持");
            String text = response.text().strip();
            if (text.startsWith("```")) {
                text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            JsonNode parsed = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (parsed == null || !parsed.isObject()) throw ApiException.model("模型输出不是有效的 JSON 对象，请重试");
            return (ObjectNode) parsed;
        } catch (ApiException e) {
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.model("模型返回不合法 JSON，请重试；没有使用替代结果");
        } catch (Exception e) {
            throw ApiException.model("模型调用失败，请检查本地配置与网络后重试");
        }
    }
}
