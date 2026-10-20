package cn.interview.client.transport;

import cn.interview.dto.ModelSettings;
import cn.interview.exception.ApiException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

/** 业务模型任务共用的OpenAI结构化请求传输；业务任务自行约束状态和输出语义。 */
@Component
public class OpenAiJsonClient {
  private static final int RESPONSE_LIMIT = 2_000_000;
  private final ObjectMapper json;
  public OpenAiJsonClient(ObjectMapper json) { this.json = json; }

  public record Reply(String text, String finishReason, String model, String fingerprint) {}

  public Reply exchange(ModelSettings settings, String prompt, ObjectNode input,
      int maxTokens, int timeoutSeconds, boolean fast, java.util.function.Consumer<ObjectNode> reportUsage) {
    try {
      ChatResponse response = chat(settings, List.of(new SystemMessage(prompt), new UserMessage(json.writeValueAsString(input))),
          null, maxTokens, timeoutSeconds, fast, reportUsage);
      return new Reply(response.getResult().getOutput().getText(), response.getResult().getMetadata().getFinishReason() == null ? null : response.getResult().getMetadata().getFinishReason().toLowerCase(java.util.Locale.ROOT),
          response.getMetadata().getModel(), response.getMetadata().get("system-fingerprint"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw failure("MODEL_INVALID_INPUT", "模型输入无法序列化");
    }
  }

  public ChatResponse exchangeTools(ModelSettings settings, List<org.springframework.ai.chat.messages.Message> messages,
      List<org.springframework.ai.tool.ToolCallback> callbacks, int maxTokens, int timeoutSeconds,
      java.util.function.Consumer<ObjectNode> reportUsage) {
    return chat(settings, messages, callbacks, maxTokens, timeoutSeconds, false, reportUsage);
  }

  private ChatResponse chat(ModelSettings settings, List<org.springframework.ai.chat.messages.Message> messages,
      List<org.springframework.ai.tool.ToolCallback> callbacks, int maxTokens, int timeoutSeconds, boolean fast,
      java.util.function.Consumer<ObjectNode> reportUsage) {
    String provider = settings.provider(), apiKey = settings.apiKey(), model = settings.model(), fastModel = settings.fastModel();
    if (!"openai".equals(provider) || apiKey.isBlank() || model.isBlank())
      throw new ApiException(503, "MODEL_NOT_CONFIGURED", "模型尚未配置，请检查本机模型配置，没有使用 Mock。");
    if (timeoutSeconds < 1 || timeoutSeconds > 300 || maxTokens < 1) throw ApiException.bad("模型请求限额无效");
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
      var options =
          OpenAiChatOptions.builder()
              .model(fast && !fastModel.isBlank() ? fastModel : model)
              .temperature(0.4)
              .maxTokens(maxTokens)
              .internalToolExecutionEnabled(false);
      if (callbacks == null) options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
      else options.toolCallbacks(callbacks);
      options.extraBody(settings.extraBody(fast));
      String effort = settings.effort(fast);
      if (!effort.isBlank()) options.reasoningEffort(effort.strip());
      OpenAiChatModel chat =
          OpenAiChatModel.builder()
              .openAiApi(api)
              .defaultOptions(options.build())
              .retryTemplate(RetryTemplate.builder().maxAttempts(1).noBackoff().build())
              .build();
      ChatResponse response =
          chat.call(
              new Prompt(messages));
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
      if (response == null || response.getResult() == null)
        throw failure("MODEL_INVALID_OUTPUT", "模型未返回有效的文本 JSON。");
      return response;
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
      throw new ApiException(503, "MODEL_NOT_CONFIGURED", "模型服务地址无效，请检查本机配置。");
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
      var headers = new HttpHeaders();
      headers.putAll(response.getHeaders());
      var type = headers.getContentType();
      // 部分兼容服务省略类型；正文仍需通过JSON和业务输出校验。
      if (type == null || type.equals(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM))
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
      return headers;
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
