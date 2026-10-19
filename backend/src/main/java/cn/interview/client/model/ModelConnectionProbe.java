package cn.interview.client.model;

import cn.interview.dto.ModelSettings;
import cn.interview.exception.ApiException;
import cn.interview.service.model.ModelConfigurationService;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

/** 用户主动触发的单次探测；仅发送合成内容，无自动重试与重定向。 */
@Component
public class ModelConnectionProbe {
    private final ObjectMapper json;
    public ModelConnectionProbe(ObjectMapper json) { this.json = json; }
    public ObjectNode models(ModelSettings s) {
        JsonNode response = send(s, s.modelsEndpoint(), null);
        if (!response.path("data").isArray()) throw failure("MODEL_LIST_ERROR", "模型列表格式不受支持，可继续手动填写模型 ID。");
        var result = json.createObjectNode(); var models = result.putArray("models");
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : response.path("data")) {
            String id = item.path("id").asText("");
            if (ModelConfigurationService.validModelId(id) && seen.add(id)) models.addObject().put("id", id);
            if (seen.size() >= 200) break;
        }
        return result;
    }
    public ObjectNode test(ModelSettings s) {
        long start = System.nanoTime();
        var payload = json.createObjectNode().put("model", s.model()).put("max_tokens", 32);
        payload.putObject("response_format").put("type", "json_object");
        payload.putArray("messages").addObject().put("role", "user").put("content", "仅返回 JSON 对象：{\"ok\":true}");
        if (s.preset().equals("DEEPSEEK")) payload.set("thinking", json.valueToTree(Map.of("type", "disabled")));
        JsonNode response = send(s, s.chatEndpoint(), payload);
        JsonNode choice = response.path("choices").path(0);
        try {
            if (!"stop".equals(choice.path("finish_reason").asText()) || !choice.path("message").path("content").isTextual()) throw new IllegalArgumentException();
            JsonNode parsed = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(choice.path("message").path("content").asText());
            if (parsed == null || !parsed.isObject() || !parsed.path("ok").isBoolean() || !parsed.path("ok").asBoolean()) throw new IllegalArgumentException();
        } catch (Exception error) { throw failure("MODEL_INVALID_OUTPUT", "连接已响应，但未通过本次 JSON 格式检测。请检查模型的结构化输出支持。"); }
        var result = json.createObjectNode().put("jsonSupported", true).put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        ObjectNode usage = result.putObject("usage");
        String[] wire = {"prompt_tokens", "completion_tokens", "total_tokens"};
        String[] names = {"promptTokens", "completionTokens", "totalTokens"};
        for (int i = 0; i < wire.length; i++) {
            JsonNode n = response.path("usage").path(wire[i]);
            if (n.isIntegralNumber() && n.canConvertToInt() && n.asInt() >= 0) usage.put(names[i], n.asInt());
        }
        return result;
    }
    private JsonNode send(ModelSettings s, URI uri, ObjectNode payload) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + s.apiKey()).header("Accept", "application/json");
            if (payload != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
            else builder.GET();
            pending = http.sendAsync(builder.build(), info -> new BoundedBody());
            var response = pending.get(15, TimeUnit.SECONDS);
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new ApiException(503, "MODEL_AUTH_ERROR", "模型认证失败，请检查 API Key 和访问权限。");
            if (status == 429) throw new ApiException(503, "MODEL_RATE_LIMITED", "模型服务限流，请稍后手动重试。");
            if (status < 200 || status >= 300) throw new ApiException(503, payload == null ? "MODEL_LIST_ERROR" : "MODEL_HTTP_ERROR", "模型服务请求失败（HTTP " + status + "）；未自动重试。");
            JsonNode parsed = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException();
            return parsed;
        } catch (ApiException e) { throw e; }
        catch (TimeoutException e) { throw new ApiException(504, "MODEL_TIMEOUT", "连接检测超时，未自动重试。"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure("MODEL_INTERRUPTED", "连接检测已中断。"); }
        catch (Exception e) {
            if (e instanceof ExecutionException && e.getCause() instanceof HttpTimeoutException) throw new ApiException(504, "MODEL_TIMEOUT", "连接检测超时，未自动重试。");
            throw failure(payload == null ? "MODEL_LIST_ERROR" : "MODEL_INVALID_OUTPUT", "连接或响应格式检测失败，请检查服务地址及协议；可手填模型 ID。");
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); http.shutdownNow(); }
    }
    private static ApiException failure(String code, String message) { return new ApiException(502, code, message); }
    /** 读取时即限制上游正文大小，避免等全部加载后才检查。 */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> target = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long count;
        private boolean ended;
        public CompletionStage<byte[]> getBody() { return target.getBody(); }
        public void onSubscribe(Flow.Subscription s) { subscription = s; target.onSubscribe(s); }
        public void onNext(List<ByteBuffer> buffers) {
            if (ended) return;
            for (var b : buffers) count += b.remaining();
            if (count > 2_000_000) { ended = true; subscription.cancel(); target.onError(new IllegalStateException("模型响应超过大小限制")); }
            else target.onNext(buffers);
        }
        public void onError(Throwable e) { if (!ended) { ended = true; target.onError(e); } }
        public void onComplete() { if (!ended) { ended = true; target.onComplete(); } }
    }
}
