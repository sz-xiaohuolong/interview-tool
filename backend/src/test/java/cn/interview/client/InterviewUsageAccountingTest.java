package cn.interview.client;

import static org.assertj.core.api.Assertions.assertThat;

import cn.interview.Application;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 本机协议服务提供合成用量，真实运行模型适配器和面试编排，不访问外部模型。 */
@SpringBootTest(classes = Application.class, properties = {
    "app.llm.provider=mock",  "app.knowledge.network-enabled=false"})
@Import(InterviewUsageAccountingTest.Configuration.class)
class InterviewUsageAccountingTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final Path DATA;
  static final HttpServer SERVER;
  static volatile boolean invalidFollowup;
  static volatile boolean omitUsage;
  static {
    try {
      DATA = Files.createTempDirectory("v3-usage-");
      SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      SERVER.createContext("/v1/chat/completions", exchange -> {
        var request = JSON.readTree(exchange.getRequestBody());
        String system = request.path("messages").get(0).path("content").asText();
        String stage = system.contains("准备分析员") ? "ANALYZE" : system.contains("规划员") ? "PLAN" : "FOLLOWUP";
        var input = (ObjectNode) JSON.readTree(request.path("messages").get(1).path("content").asText());
        String content = invalidFollowup && stage.equals("FOLLOWUP") ? "{无效输出"
            : new MockInterviewModel(JSON).call(stage, input).toString();
        var response = JSON.createObjectNode().put("id", "synthetic-usage").put("model", "synthetic-model")
            .put("object", "chat.completion").put("created", 0);
        response.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop")
            .putObject("message").put("role", "assistant").put("content", content);
        if (!omitUsage) response.putObject("usage").put("prompt_tokens", 21).put("completion_tokens", 12).put("total_tokens", 33);
        byte[] bytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
      });
      SERVER.start();
    } catch (Exception error) { throw new IllegalStateException(error); }
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("app.data-dir", DATA::toString); }
  @Autowired InterviewService service;
  @Autowired InterviewSessionStore store;
  @AfterAll static void closeServer() { SERVER.stop(0); }
  @AfterEach void cleanup() {
    invalidFollowup = false; omitUsage = false;
    for (var s : store.list()) if (!List.of("CANCELLED", "REPORT_READY").contains(s.path("status").asText()))
      service.action(s.path("id").asText(), "cancel", action(service.get(s.path("id").asText())));
  }
  ObjectNode action(JsonNode s) { return JSON.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("expectedRevision", s.path("revision").asInt()); }
  ObjectNode prepared() throws Exception {
    return await(service.create(JSON.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("role", "Java 后端").put("level", "JUNIOR")), "READY");
  }
  ObjectNode await(JsonNode s, String state) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var value = service.get(s.path("id").asText());
      if (value.path("status").asText().equals(state)) return value;
      if (value.path("status").asText().equals("FAILED")) throw new AssertionError(value.path("error").asText());
      Thread.sleep(5);
    }
    throw new AssertionError("合成任务未按期结束");
  }
  @Test void 累计服务返回的输入输出Token而不是输出声明上限() throws Exception {
    var s = prepared();
    assertThat(s.path("usage").path("calls").asInt()).isEqualTo(2);
    assertThat(s.path("usage").path("promptTokens").asLong()).isEqualTo(42);
    assertThat(s.path("usage").path("completionTokens").asLong()).isEqualTo(24);
    assertThat(s.path("usage").path("totalTokens").asLong()).isEqualTo(66);
    assertThat(s.path("usage").path("usageIncomplete").asBoolean()).isFalse();
  }
  @Test void 模型已返回用量但正文无效时仍累计实际Token() throws Exception {
    var s = prepared(); String id = s.path("id").asText();
    s = service.action(id, "confirm", action(s)); invalidFollowup = true;
    service.action(id, "answers", action(s).put("expectedQuestionId", s.path("messages").get(0).path("questionId").asText()).put("content", "合成回答：先检查事务边界。"));
    s = await(s, "IN_PROGRESS");
    assertThat(s.path("metrics").path("lastFallback").asBoolean()).isTrue();
    assertThat(s.path("usage").path("promptTokens").asLong()).isEqualTo(63);
    assertThat(s.path("usage").path("completionTokens").asLong()).isEqualTo(36);
    assertThat(s.path("usage").path("totalTokens").asLong()).isEqualTo(99);
    assertThat(s.path("usage").path("usageIncomplete").asBoolean()).isFalse();
  }
  @Test void 服务未返回用量时明确标记缺失而不填入零() throws Exception {
    omitUsage = true; var s = prepared();
    assertThat(s.path("usage").path("usageIncomplete").asBoolean()).isTrue();
    assertThat(s.path("usage").hasNonNull("totalTokens")).isFalse();
    assertThat(s.path("usage").hasNonNull("promptTokens")).isFalse();
  }
  @TestConfiguration static class Configuration {
    @Bean @Primary InterviewModelGateway gateway(ObjectMapper json) {
      return new InterviewModelClient(json, "openai", "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/v1",
          "test-only-key", "synthetic-model", "", "", "");
    }
  }
}
