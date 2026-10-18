package cn.interview.v3.model;

import cn.interview.exception.ApiException;
import cn.interview.dto.ModelSettings;
import cn.interview.service.ModelConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/** 仅负责一次受限模型请求，不拥有任务队列、预算累计或会话状态。 */
@Component
public class V3SpringAiModelGateway implements V3ModelGateway {
  private static final int RESPONSE_LIMIT = 2_000_000;
  private static final Map<String, Integer> OUTPUT_LIMITS =
      Map.of("ANALYZE", 2500, "PLAN", 6500, "FOLLOWUP", 800, "REVIEW", 6500);
  private final ObjectMapper json;
  private final java.util.function.Supplier<ModelSettings> connections;

  @Autowired
  public V3SpringAiModelGateway(ObjectMapper json, ModelConfigurationService settings) {
    this.json = json;
    this.connections = settings::connection;
  }

  public V3SpringAiModelGateway(
      ObjectMapper json,
      @Value("${app.llm.provider:openai}") String provider,
      @Value("${app.llm.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${app.llm.api-key:}") String apiKey,
      @Value("${app.llm.model:}") String model,
      @Value("${app.llm.reasoning-effort:}") String reasoningEffort,
      @Value("${V3_FAST_MODEL:}") String fastModel,
      @Value("${V3_FAST_REASONING_EFFORT:low}") String fastReasoningEffort) {
    this.json = json;
    this.connections = () -> new ModelSettings(provider, "CUSTOM", baseUrl, apiKey, model,
        fastModel, reasoningEffort, fastReasoningEffort);
  }

  @Override
  public ObjectNode call(String stage, ObjectNode input, int timeoutSeconds) {
    ObjectNode usage = json.createObjectNode();
    ObjectNode result = call(stage, input, timeoutSeconds, usage::setAll);
    if (!usage.isEmpty()) result.set("_modelUsage", usage);
    return result;
  }

  @Override
  public ObjectNode call(String stage, ObjectNode input, int timeoutSeconds,
      java.util.function.Consumer<ObjectNode> reportUsage) {
    if (!OUTPUT_LIMITS.containsKey(stage)) throw ApiException.bad("不支持的 V3 模型阶段");
    if (input == null || input.toString().length() > 100000)
      throw ApiException.bad("模型输入为空或超过本次允许范围");
    if (timeoutSeconds < 1 || timeoutSeconds > 300) throw ApiException.bad("模型超时必须为 1～300 秒");
    ModelSettings settings = connections.get();
    String provider = settings.provider(), apiKey = settings.apiKey(), model = settings.model(), fastModel = settings.fastModel();
    if ("mock".equals(provider)) {
      ObjectNode result = new V3MockModel(json).call(stage, input);
      if (result.remove("_modelUsage") instanceof ObjectNode usage) reportUsage.accept(usage);
      return result;
    }
    if (!"openai".equals(provider) || apiKey.isBlank() || model.isBlank()) {
      throw new ApiException(503, "MODEL_NOT_CONFIGURED", "V3 真实模型未配置；请检查本机模型配置，没有使用 Mock。");
    }
    HttpClient http = null;
    try {
      URI endpoint = endpoint(settings.baseUrl());
      http =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 15)))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      var factory = new JdkClientHttpRequestFactory(http);
      factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
      var rest =
          RestClient.builder()
              .requestFactory(factory)
              .requestInterceptor(
                  (request, body, execution) ->
                      new BoundedResponse(execution.execute(request, body)));
      OpenAiApi api =
          OpenAiApi.builder()
              .baseUrl(endpoint.getScheme() + "://" + endpoint.getRawAuthority())
              .completionsPath(endpoint.getRawPath())
              .apiKey(apiKey)
              .restClientBuilder(rest)
              .responseErrorHandler(new SafeErrorHandler())
              .build();
      boolean fast = stage.equals("FOLLOWUP");
      var options =
          OpenAiChatOptions.builder()
              .model(fast && !fastModel.isBlank() ? fastModel : model)
              .temperature(0.4)
              .maxTokens(OUTPUT_LIMITS.get(stage))
              .internalToolExecutionEnabled(false)
              .responseFormat(
                  ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
      options.extraBody(settings.extraBody(fast));
      String effort = settings.effort(fast);
      if (!effort.isBlank()) options.reasoningEffort(effort.strip());
      OpenAiChatModel chat =
          OpenAiChatModel.builder()
              .openAiApi(api)
              .defaultOptions(options.build())
              .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
              .build();
      String prompt =
          new ClassPathResource("prompts/v3/" + stage.toLowerCase(java.util.Locale.ROOT) + ".txt")
              .getContentAsString(StandardCharsets.UTF_8);
      ChatResponse response =
          chat.call(
              new Prompt(
                  List.of(
                      new SystemMessage(prompt), new UserMessage(json.writeValueAsString(input)))));
      if (response != null) {
        ObjectNode usage = json.createObjectNode();
        var actual = response.getMetadata().getUsage();
        if (actual != null && actual.getNativeUsage() instanceof OpenAiApi.Usage nativeUsage) {
          if (nativeUsage.promptTokens() != null && nativeUsage.promptTokens() >= 0)
            usage.put("promptTokens", nativeUsage.promptTokens());
          if (nativeUsage.completionTokens() != null && nativeUsage.completionTokens() >= 0)
            usage.put("completionTokens", nativeUsage.completionTokens());
        }
        usage.put("model", response.getMetadata().getModel());
        reportUsage.accept(usage);
      }
      if (response == null
          || response.getResult() == null
          || !"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
        throw failure("MODEL_INCOMPLETE_OUTPUT", "模型未完整结束输出，没有保存为有效结果。");
      }
      String text = response.getResult().getOutput().getText();
      JsonNode parsed =
          json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
      if (!(parsed instanceof ObjectNode result))
        throw failure("MODEL_INVALID_OUTPUT", "模型输出不是有效 JSON 对象。");
      V3OutputChecks.validate(stage, input, result);
      result.remove("_modelUsage");
      return result;
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      if (Thread.currentThread().isInterrupted())
        throw failure("MODEL_INTERRUPTED", "模型调用被中断，已提交内容由会话保留。");
      for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof HttpTimeoutException
            || cause instanceof java.net.SocketTimeoutException) {
          throw new ApiException(504, "MODEL_TIMEOUT", "模型调用超时，未自动重试；已提交内容由会话保留。");
        }
      }
      throw failure("MODEL_INVALID_OUTPUT", "模型连接或结构化输出校验失败，没有使用替代结果。");
    } finally {
      if (http != null) http.shutdownNow();
    }
  }

  private URI endpoint(String baseUrl) {
    try {
      String address = baseUrl.strip().replaceAll("/+$", "");
      if (!address.endsWith("/chat/completions")) address += "/chat/completions";
      URI endpoint = URI.create(address);
      if (!List.of("http", "https").contains(endpoint.getScheme())
          || endpoint.getHost() == null
          || endpoint.getUserInfo() != null
          || endpoint.getRawQuery() != null
          || endpoint.getRawFragment() != null) {
        throw new IllegalArgumentException();
      }
      return endpoint;
    } catch (IllegalArgumentException error) {
      throw new ApiException(503, "MODEL_NOT_CONFIGURED", "V3 模型服务地址无效，请检查本机配置。");
    }
  }

  private static ApiException failure(String code, String message) {
    return new ApiException(502, code, message);
  }

  /** 不把上游错误正文、地址或凭证带入业务异常。 */
  private static final class SafeErrorHandler implements ResponseErrorHandler {
    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
      return !response.getStatusCode().is2xxSuccessful();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
        throws IOException {
      int code = response.getStatusCode().value();
      if (code == 401 || code == 403)
        throw new ApiException(503, "MODEL_AUTH_ERROR", "模型认证失败，请检查本机密钥与访问权限。");
      if (code == 429) throw new ApiException(503, "MODEL_RATE_LIMITED", "模型服务限流，未自动重试，请稍后显式重试。");
      throw failure("MODEL_HTTP_ERROR", "模型服务请求失败，未自动重试；HTTP " + code + "。");
    }
  }

  private static final class BoundedResponse implements ClientHttpResponse {
    private final ClientHttpResponse response;
    private final InputStream body;

    BoundedResponse(ClientHttpResponse response) throws IOException {
      this.response = response;
      this.body =
          new FilterInputStream(response.getBody()) {
            private int count;

            private void add(int size) throws IOException {
              if (size > 0 && (count += size) > RESPONSE_LIMIT) throw new IOException("模型响应超过允许大小");
            }

            @Override
            public int read() throws IOException {
              int value = in.read();
              add(value < 0 ? 0 : 1);
              return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              int size = in.read(bytes, offset, length);
              add(size);
              return size;
            }
          };
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
      return response.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
      return response.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
      return response.getHeaders();
    }

    @Override
    public InputStream getBody() {
      return body;
    }

    @Override
    public void close() {
      response.close();
    }
  }
}
