package cn.interview.service.model;

import cn.interview.dto.ModelSettings;
import cn.interview.exception.ApiException;
import cn.interview.mapper.ModelConfigStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 保存与读取共享配置；网络探测不持有配置锁。 */
@Service
public class ModelConfigurationService {
    private record State(long revision, String source, ModelSettings settings) {}
    private final ModelSettings startup;
    private final ModelConfigStore store;
    private final ObjectMapper json;
    private volatile State current;

    public ModelConfigurationService(Environment env, ModelConfigStore store, ObjectMapper json) {
        this.store = store; this.json = json;
        String url = env.getProperty("app.llm.base-url", "https://api.openai.com/v1").strip().replaceAll("/+$", "");
        url = normalizeUrl(url);
        String preset = url.matches("https://api\\.deepseek\\.com(?:/.*)?") ? "DEEPSEEK" : url.matches("https://api\\.openai\\.com(?:/.*)?") ? "OPENAI" : "CUSTOM";
        startup = new ModelSettings(env.getProperty("app.llm.provider", "openai"), preset, url,
            env.getProperty("app.llm.api-key", ""), env.getProperty("app.llm.model", ""),
            env.getProperty("INTERVIEW_FOLLOWUP_MODEL", env.getProperty("V3_FAST_MODEL", "")), env.getProperty("app.llm.reasoning-effort", ""), env.getProperty("INTERVIEW_FOLLOWUP_REASONING_EFFORT", env.getProperty("V3_FAST_REASONING_EFFORT", "low")));
        current = new State(0, "STARTUP", startup);
        JsonNode saved = store.read();
        if (saved != null) {
            try {
                if (!saved.isObject() || !saved.path("revision").isIntegralNumber() || !saved.path("revision").canConvertToLong() || saved.path("revision").asLong() < 0) throw new IllegalStateException();
                String source = saved.path("source").asText();
                ModelSettings settings;
                if ("STARTUP".equals(source)) settings = startup;
                else if ("CUSTOM".equals(source)) settings = parse(saved, null, true);
                else throw new IllegalStateException();
                current = new State(saved.path("revision").asLong(), source, settings);
            } catch (Exception error) { throw new IllegalStateException("本机模型配置内容无效，请检查 data/model-config.json；没有退回其他模型。"); }
        }
    }
    public ModelSettings connection() { return current.settings(); }
    public ObjectNode view() { return view(current); }
    private ObjectNode view(State state) {
        var s = state.settings();
        return json.createObjectNode().put("revision", state.revision()).put("source", state.source())
            .put("provider", s.provider()).put("preset", s.preset()).put("baseUrl", s.baseUrl()).put("model", s.model())
            .put("fastModel", s.fastModel()).put("reasoningEffort", s.reasoningEffort()).put("fastReasoningEffort", s.fastReasoningEffort())
            .put("apiKeyConfigured", !s.apiKey().isBlank()).put("configured", s.configured());
    }
    private void checkRevision(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("expectedRevision").isIntegralNumber() || !body.path("expectedRevision").canConvertToLong()) throw ApiException.bad("缺少有效的配置版本，请重新打开配置弹窗。");
        if (body.path("expectedRevision").asLong() != current.revision()) throw new ApiException(409, "MODEL_CONFIG_CHANGED", "配置已被更新，请重新打开弹窗后再操作。");
    }
    public synchronized ModelSettings draft(JsonNode body) { return draft(body, true); }
    public synchronized ModelSettings draft(JsonNode body, boolean requireModel) { checkRevision(body); return parse(body, current.settings(), requireModel); }
    public synchronized ObjectNode save(JsonNode body) {
        checkRevision(body);
        ModelSettings settings = parse(body, current.settings(), true);
        State next = new State(Math.addExact(current.revision(), 1), "CUSTOM", settings);
        ObjectNode saved = view(next); saved.remove(Set.of("configured", "apiKeyConfigured")); saved.put("apiKey", settings.apiKey());
        store.write(saved); current = next; return view(next);
    }
    public synchronized ObjectNode reset(JsonNode body) {
        checkRevision(body);
        State next = new State(Math.addExact(current.revision(), 1), "STARTUP", startup);
        store.write(json.createObjectNode().put("revision", next.revision()).put("source", "STARTUP"));
        current = next; return view(next);
    }
    private ModelSettings parse(JsonNode body, ModelSettings previous, boolean requireModel) {
        if (body.toString().length() > 16_384) throw ApiException.bad("模型配置内容过长。");
        String preset = text(body, "preset", 20);
        if (!Set.of("OPENAI", "DEEPSEEK", "CUSTOM").contains(preset)) throw ApiException.bad("请选择支持的模型服务商。");
        String url = normalizeUrl(text(body, "baseUrl", 2048));
        String key = text(body, "apiKey", 4096);
        if (key.isBlank() && previous != null && preset.equals(previous.preset()) && url.equals(previous.baseUrl())) key = previous.apiKey();
        if (key.isBlank() || key.chars().anyMatch(c -> c < 33 || c > 126)) throw ApiException.bad("请填写当前连接的有效 API Key；更换服务地址或预设后不能沿用旧密钥。");
        String model = modelId(text(body, "model", 200), requireModel);
        String fast = modelId(text(body, "fastModel", 200), false);
        return new ModelSettings("openai", preset, url, key, model, fast, effort(body,"reasoningEffort"), effort(body,"fastReasoningEffort"));
    }
    private static String text(JsonNode body, String field, int limit) {
        JsonNode n = body.path(field);
        if (n.isMissingNode() || n.isNull()) return "";
        if (!n.isTextual() || n.asText().length() > limit) throw ApiException.bad("模型配置字段格式或长度不合法：" + field);
        return n.asText().strip();
    }
    private static String modelId(String s, boolean required) {
        if (s.isEmpty() && !required) return s;
        if (!validModelId(s)) throw ApiException.bad("模型 ID 不能为空，且只能包含字母、数字及常用模型路径符号。");
        return s;
    }
    public static boolean validModelId(String s) { return s.length() <= 200 && s.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]*"); }
    private static String effort(JsonNode body, String name) {
        String value = text(body, name, 20);
        if (!Set.of("", "none", "minimal", "low", "medium", "high", "xhigh", "max").contains(value)) throw ApiException.bad("不支持的思考强度。");
        return value;
    }
    public static String normalizeUrl(String raw) {
        try {
            URI u = URI.create(raw);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(u.getHost() == null ? "" : u.getHost());
            if (u.getHost() == null || u.getUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                || !("https".equals(u.getScheme()) || "http".equals(u.getScheme()) && loopback)
                || u.getPort() < -1 || u.getPort() == 0 || u.getPort() > 65535 || raw.chars().anyMatch(c -> c <= 32)) throw new IllegalArgumentException();
            return raw.replaceAll("/+$", "");
        } catch (IllegalArgumentException e) { throw ApiException.bad("服务地址须为 HTTPS，或本机回环 HTTP 地址；不得包含账号、查询参数或片段。"); }
    }
}
