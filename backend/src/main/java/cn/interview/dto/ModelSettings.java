package cn.interview.dto;

import java.net.URI;
import java.util.Map;

/** 单次请求使用的不可变连接快照；禁止作为公开响应返回。 */
public record ModelSettings(String provider, String preset, String baseUrl, String apiKey,
    String model, String fastModel, String reasoningEffort, String fastReasoningEffort) {
    public boolean isMock() { return "mock".equals(provider); }
    public boolean configured() { return isMock() || ("openai".equals(provider) && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank()); }
    public URI chatEndpoint() {
        String address = baseUrl.replaceAll("/+$", "");
        return URI.create(address.endsWith("/chat/completions") ? address : address + "/chat/completions");
    }
    public URI modelsEndpoint() {
        return URI.create(baseUrl.replaceAll("/+$", "").replaceFirst("/chat/completions$", "") + "/models");
    }
    public Map<String, Object> extraBody(boolean fast) {
        return "DEEPSEEK".equals(preset) && fast ? Map.of("thinking", Map.of("type", "disabled")) : Map.of();
    }
    public String effort(boolean fast) {
        return "DEEPSEEK".equals(preset) && fast ? "" : fast ? fastReasoningEffort : reasoningEffort;
    }
    @Override public String toString() { return "ModelSettings[provider=" + provider + ", preset=" + preset + ", apiKey=已隐藏]"; }
}
