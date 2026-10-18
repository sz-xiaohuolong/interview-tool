package cn.interview.client;

import cn.interview.exception.ApiException;
import cn.interview.dto.ModelSettings;
import cn.interview.service.ModelConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class LlmClient {
    final ObjectMapper json;
    private final java.util.function.Supplier<ModelSettings> connections;
    final int timeout;
    final HttpClient http;
    final MockLlmProvider mock;
    @Autowired
    public LlmClient(ObjectMapper json, ModelConfigurationService settings, @Value("${app.llm.timeout-seconds}") int timeout) {
        this(json, settings::connection, timeout);
    }
    public LlmClient(ObjectMapper json, String provider, String baseUrl, String key, String model, int timeout, String reasoningEffort) {
        this(json, () -> new ModelSettings(provider, "CUSTOM", baseUrl, key, model, "", reasoningEffort.strip(), ""), timeout);
    }
    private LlmClient(ObjectMapper json, java.util.function.Supplier<ModelSettings> connections, int timeout) {
        this.json = json;
        this.connections = connections;
        this.timeout = Math.max(1, Math.min(timeout, 300));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(this.timeout, 15))).followRedirects(HttpClient.Redirect.NEVER).build();
        mock = new MockLlmProvider(json);
    }
    public ModelSettings connection() { return connections.get(); }
    public boolean isMock() { return connection().isMock(); }
    public Map<String, Object> health() {
        var s = connection();
        return Map.of("status", "ok", "provider", s.provider(), "configured", s.configured(), "model", s.isMock() ? "明确标识的测试模型" : s.model());
    }

    public ObjectNode call(String task,ObjectNode input){
        var settings=connection();
        String provider=settings.provider(),baseUrl=settings.baseUrl(),key=settings.apiKey(),model=settings.model(),reasoningEffort=settings.effort(task.equals("interviewer") && settings.preset().equals("DEEPSEEK"));
        if(settings.isMock())return mock.generate(task,input);
        if(!"openai".equals(provider))throw new ApiException(503,"MODEL_NOT_CONFIGURED","LLM_PROVIDER 必须为 openai 或显式测试用 mock");
        if(key.isBlank()||model.isBlank())throw new ApiException(503,"MODEL_NOT_CONFIGURED","模型尚未配置，请点击右上角设置服务地址、API Key 和模型 ID");
        try{
            String prompt=new ClassPathResource("prompts/"+task+".txt").getContentAsString(StandardCharsets.UTF_8);
            String url=baseUrl.replaceAll("/+$","");if(!url.endsWith("/chat/completions"))url+="/chat/completions";
            URI uri=URI.create(url);if(!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new ApiException(503,"MODEL_NOT_CONFIGURED","LLM_BASE_URL 不是有效的 HTTP 服务地址");
            ObjectNode payload=json.valueToTree(Map.of("model",model,"temperature",0.4,"max_tokens",task.equals("evaluator")?6500:4500,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content",prompt),Map.of("role","user","content",json.writeValueAsString(input)))));
            if(!reasoningEffort.isBlank())payload.put("reasoning_effort",reasoningEffort);
            settings.extraBody(task.equals("interviewer")).forEach((name,value)->payload.set(name,json.valueToTree(value)));
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json").header("Authorization","Bearer "+key).POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            HttpResponse<byte[]> response=http.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray()).get(timeout,TimeUnit.SECONDS);
            int status=response.statusCode();
            if(status==429)throw new ApiException(503,"MODEL_RATE_LIMITED","模型服务限流，请稍后重试，已保存当前进度");
            if(status==401||status==403)throw new ApiException(503,"MODEL_AUTH_ERROR","模型服务拒绝认证，请检查 API Key 和模型访问权限");
            if(status<200||status>=300)throw ApiException.model("模型服务请求失败（HTTP "+status+"），请稍后重试");
            if(response.body().length>2_000_000)throw ApiException.model("模型响应过大，请重试");
            JsonNode envelope=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());JsonNode content=envelope.path("choices").path(0).path("message").path("content");
            if(!content.isTextual())throw ApiException.model("模型未返回有效的文本 JSON，请检查服务的结构化输出支持");
            String text=content.asText().strip();if(text.startsWith("```")){text=text.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");}
            JsonNode parsed=json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);if(parsed==null||!parsed.isObject())throw ApiException.model("模型输出不是有效的 JSON 对象，请重试");return (ObjectNode)parsed;
        }catch(ApiException e){throw e;}catch(java.util.concurrent.TimeoutException e){throw new ApiException(504,"MODEL_TIMEOUT","模型调用超时，请重试；当前输入已经保存");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw ApiException.model("模型调用被中断，请重试");}
        catch(java.util.concurrent.ExecutionException e){if(e.getCause() instanceof java.net.http.HttpTimeoutException)throw new ApiException(504,"MODEL_TIMEOUT","模型调用超时，请重试；当前输入已经保存");throw ApiException.model("无法连接模型服务，请检查 Base URL 和网络");}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw ApiException.model("模型返回不合法 JSON，请重试；没有使用替代结果");}
        catch(Exception e){throw ApiException.model("模型调用失败，请检查本地配置与网络后重试");}
    }
}
