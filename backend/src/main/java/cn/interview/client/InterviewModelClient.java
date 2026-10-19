package cn.interview.client;

import cn.interview.validation.InterviewOutputValidator;
import cn.interview.exception.ApiException;
import cn.interview.dto.ModelSettings;
import cn.interview.service.ModelConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;


/** 仅负责一次受限模型请求，不拥有任务队列、预算累计或会话状态。 */
@Component
public class InterviewModelClient implements InterviewModelGateway {
  private static final Map<String, Integer> OUTPUT_LIMITS =
      Map.of("ANALYZE", 2500, "PLAN", 6500, "FOLLOWUP", 800, "REVIEW", 10000);
  private final ObjectMapper json;
  private final OpenAiJsonClient transport;
  private final java.util.function.Supplier<ModelSettings> connections;

  @Autowired
  public InterviewModelClient(ObjectMapper json, ModelConfigurationService settings, OpenAiJsonClient transport) {
    this.json = json;
    this.transport = transport;
    this.connections = settings::connection;
  }

  public InterviewModelClient(ObjectMapper json, ModelConfigurationService settings) {
    this(json, settings, new OpenAiJsonClient(json));
  }

  public InterviewModelClient(
      ObjectMapper json,
      @Value("${app.llm.provider:openai}") String provider,
      @Value("${app.llm.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${app.llm.api-key:}") String apiKey,
      @Value("${app.llm.model:}") String model,
      @Value("${app.llm.reasoning-effort:}") String reasoningEffort,
      @Value("${INTERVIEW_FOLLOWUP_MODEL:${V3_FAST_MODEL:}}") String fastModel,
      @Value("${INTERVIEW_FOLLOWUP_REASONING_EFFORT:${V3_FAST_REASONING_EFFORT:low}}") String fastReasoningEffort) {
    this.json = json;
    this.transport = new OpenAiJsonClient(json);
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
    if (!OUTPUT_LIMITS.containsKey(stage)) throw ApiException.bad("不支持的综合面试模型阶段");
    if (input == null || input.toString().length() > 100000)
      throw ApiException.bad("模型输入为空或超过本次允许范围");
    if (timeoutSeconds < 1 || timeoutSeconds > 300) throw ApiException.bad("模型超时必须为 1～300 秒");
    ModelSettings settings = connections.get();
    String provider = settings.provider(), apiKey = settings.apiKey(), model = settings.model();
    if ("mock".equals(provider)) {
      ObjectNode result = new MockInterviewModel(json).call(stage, input);
      if (result.remove("_modelUsage") instanceof ObjectNode usage) reportUsage.accept(usage);
      return result;
    }
    if (!"openai".equals(provider) || apiKey.isBlank() || model.isBlank()) {
      throw new ApiException(503, "MODEL_NOT_CONFIGURED", "真实模型未配置；请检查本机模型配置，没有使用 Mock。");
    }
    try {
      String prompt = new ClassPathResource("prompts/interview/" + stage.toLowerCase(java.util.Locale.ROOT) + ".txt")
          .getContentAsString(StandardCharsets.UTF_8);
      var response = transport.exchange(settings, prompt, input, OUTPUT_LIMITS.get(stage), timeoutSeconds,
          stage.equals("FOLLOWUP"), reportUsage);
      if (!"stop".equalsIgnoreCase(response.finishReason()))
        throw new ApiException(502, "MODEL_INCOMPLETE_OUTPUT", "模型未完整结束输出，没有保存为有效结果。");
      JsonNode parsed = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.text());
      if (!(parsed instanceof ObjectNode result)) throw new ApiException(502, "MODEL_INVALID_OUTPUT", "模型输出不是有效 JSON 对象。");
      InterviewOutputValidator.validateModel(stage, input, result);
      result.remove("_modelUsage");
      return result;
    } catch (ApiException error) { throw error; }
    catch (Exception error) { throw new ApiException(502, "MODEL_INVALID_OUTPUT", "模型连接或结构化输出校验失败，没有使用替代结果。"); }
  }
}
