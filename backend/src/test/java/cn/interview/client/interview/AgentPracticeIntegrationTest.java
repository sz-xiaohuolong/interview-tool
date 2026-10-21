package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.mock.MockInterviewModel;
import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实服务与磁盘快照，外部模型使用明确的合成替身。 */
@SpringBootTest(classes = Application.class, properties = {
    "app.llm.provider=mock", "app.knowledge.network-enabled=false"})
@AutoConfigureMockMvc
@Import(AgentPracticeIntegrationTest.Configuration.class)
class AgentPracticeIntegrationTest {
  static final Path DATA;
  static {
    try { DATA = Files.createTempDirectory("agent-practice-integration-"); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }
  @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
    r.add("app.data-dir", DATA::toString);
  }
  @Autowired InterviewService service;
  @Autowired InterviewSessionStore store;
  @Autowired ObjectMapper json;
  @Autowired CapturingModel model;
  @Autowired MockMvc http;

  @AfterEach void cleanup() {
    model.tamperPoint = false;
    for (var s : store.list()) service.delete(s.path("id").asText());
  }

  ObjectNode body() {
    return json.createObjectNode().put("requestId", UUID.randomUUID().toString())
        .put("sessionType", "QUICK").put("role", "大模型应用 / Agent 开发工程师")
        .put("level", "JUNIOR").put("plannedQuestionCount", 3);
  }
  ObjectNode action(JsonNode s) {
    return json.createObjectNode().put("requestId", UUID.randomUUID().toString())
        .put("expectedRevision", s.path("revision").asInt());
  }
  ObjectNode await(JsonNode s, String... states) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (System.nanoTime() < deadline) {
      var value = service.get(s.path("id").asText());
      if (List.of(states).contains(value.path("status").asText())) return value;
      if (value.path("status").asText().equals("FAILED")) throw new AssertionError(value);
      Thread.sleep(10);
    }
    throw new AssertionError("等待考点链路状态超时");
  }

  @Test void 单主题服务选取绑定考点且问答不泄露评判提示() throws Exception {
    var request = body(); request.putArray("agentTopics").add("AGENT_CONTEXT_MEMORY");
    var s = await(service.create(request), "IN_PROGRESS");
    var selected = model.planInput.path("agentPractice").path("points");
    assertThat(selected).hasSize(3);
    assertThat(selected.findValuesAsText("theme")).containsOnly("AGENT_CONTEXT_MEMORY");
    assertThat(java.util.stream.StreamSupport.stream(selected.spliterator(), false).map(point -> point.path("id").asText()).toList()).doesNotHaveDuplicates();
    var internal = store.get(s.path("id").asText());
    assertThat(internal.path("questions").findValuesAsText("pointId"))
        .containsExactlyElementsOf(java.util.stream.StreamSupport.stream(selected.spliterator(), false).map(point -> point.path("id").asText()).toList());
    assertThat(internal.path("messages").get(0).path("pointId").asText()).isNotBlank();
    assertThat(s.has("agentPractice")).isFalse();
    assertThat(s.findValues("pointId")).isEmpty();
    assertThat(s.findValues("evaluationBoundary")).isEmpty();
  }

  @Test void 独立题单绑定到主问追问与复盘并且答题时隐藏() throws Exception {
    var request = body(); request.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    var s = await(service.create(request), "IN_PROGRESS");
    String id = s.path("id").asText();
    var first = store.get(id).path("messages").get(0);
    assertThat(first.path("seedQuestionId").asText()).startsWith("jb-");
    assertThat(s.findValues("seedQuestionId")).isEmpty();
    var answer = action(s).put("expectedQuestionId", first.path("questionId").asText())
        .put("content", "我会先记录输入输出和失败轨迹，再用合成样例重复验证边界。");
    s = await(service.action(id, "answers", answer), "IN_PROGRESS");
    assertThat(store.get(id).path("messages").get(2).path("seedQuestionId")).isEqualTo(first.path("seedQuestionId"));
    s = await(service.action(id, "finish", action(s)), "REPORT_READY");
    var item = s.path("report").path("topicCoverage").path("items").get(0);
    assertThat(item.path("pointId")).isEqualTo(first.path("pointId"));
    assertThat(item.path("seedQuestionId")).isEqualTo(first.path("seedQuestionId"));
    assertThat(item.path("seedQuestionUrl").asText()).startsWith("https://javabetter.cn/ai/video/");
    assertThat(new InterviewSessionStore(json, DATA.toString()).get(id).path("messages").get(0).path("seedQuestionId"))
        .isEqualTo(first.path("seedQuestionId"));
  }

  @Test void 没有复习历史时预检与创建都阻止调用模型() throws Exception {
    int before = model.calls.get();
    var request = body().put("practicePreference", "REVIEW");
    var previewRequest = json.createObjectNode().put("role", "Agent应用工程师")
        .put("level", "JUNIOR").put("plannedQuestionCount", 3);
    previewRequest.putArray("agentTopics");
    http.perform(post("/api/interviews/agent-practice-options")
            .contentType("application/json").content(previewRequest.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.canStart").value(false))
        .andExpect(jsonPath("$.reviewAvailable").value(0));
    assertThatThrownBy(() -> service.create(request)).isInstanceOf(ApiException.class);
    assertThat(model.calls).hasValue(before);
    assertThat(service.list()).isEmpty();
  }

  @Test void 复盘覆盖只计已展示未答且保留原题定位() throws Exception {
    var s = await(service.create(body()), "IN_PROGRESS");
    String questionId = s.path("messages").get(0).path("questionId").asText();
    s = await(service.action(s.path("id").asText(), "finish", action(s)), "REPORT_READY");
    var coverage = s.path("report").path("topicCoverage");
    assertThat(coverage.path("askedCount").asInt()).isEqualTo(1);
    assertThat(coverage.path("answeredCount").asInt()).isZero();
    assertThat(coverage.path("items")).hasSize(1);
    assertThat(coverage.path("items").get(0).path("questionId").asText()).isEqualTo(questionId);
    assertThat(coverage.path("items").get(0).path("answered").asBoolean()).isFalse();
    assertThat(s.has("agentPractice")).isFalse();
    assertThat(s.findValues("evaluationBoundary")).isEmpty();
    assertThat(s.path("usage").path("calls").asInt()).isEqualTo(1);
  }

  @Test void 追问沿用当前考点且回答后的回顾有作答状态() throws Exception {
    var s = await(service.create(body()), "IN_PROGRESS");
    String id = s.path("id").asText(), questionId = s.path("messages").get(0).path("questionId").asText();
    var answer = action(s).put("expectedQuestionId", questionId)
        .put("content", "我会先记录输入输出和失败轨迹，再用合成样例重复验证边界。");
    s = await(service.action(id, "answers", answer), "IN_PROGRESS");
    assertThat(model.followInput.path("assessmentPoint").path("id").asText()).isNotBlank();
    var internal = store.get(id);
    assertThat(internal.path("messages").get(2).path("pointId"))
        .isEqualTo(internal.path("messages").get(0).path("pointId"));
    s = await(service.action(id, "finish", action(s)), "REPORT_READY");
    assertThat(s.path("report").path("topicCoverage").path("askedCount").asInt()).isEqualTo(1);
    assertThat(s.path("report").path("topicCoverage").path("answeredCount").asInt()).isEqualTo(1);
  }

  @Test void 模型不能将未选中的考点冒充所选题目() throws Exception {
    model.tamperPoint = true;
    var s = await(service.create(body()), "FAILED");
    assertThat(s.path("messages")).isEmpty();
    assertThat(s.path("error").asText()).contains("考点");
    assertThat(s.path("knowledgeRetrieval")).isNotEmpty();
    assertThat(s.path("knowledgeRetrieval").get(0).path("status").asText()).isEqualTo("NETWORK_DISABLED");
  }

  @Test void 综合保留四阶段只将技术题关联所选考点() throws Exception {
    var request = body().put("sessionType", "COMPREHENSIVE").put("plannedQuestionCount", 8);
    var s = await(service.create(request), "READY");
    var questions = store.get(s.path("id").asText()).path("questions");
    assertThat(questions.findValuesAsText("stage")).contains("INTRO", "PROJECT", "TECHNICAL", "QUESTIONS");
    for (var q : questions) {
      if (q.path("stage").asText().equals("TECHNICAL")) assertThat(q.path("pointId").asText()).isNotBlank();
      else assertThat(q.has("pointId")).isFalse();
    }
    assertThat(s.path("messages")).isEmpty();
  }

  @TestConfiguration static class Configuration {
    @Bean @Primary CapturingModel model(ObjectMapper json) { return new CapturingModel(json); }
  }
  static class CapturingModel implements InterviewModelGateway {
    final ObjectMapper json;
    final AtomicInteger calls = new AtomicInteger();
    volatile ObjectNode planInput, followInput;
    volatile boolean tamperPoint;
    CapturingModel(ObjectMapper json) { this.json = json; }
    public ObjectNode call(String stage, ObjectNode input, int timeout) {
      calls.incrementAndGet();
      if (stage.equals("PLAN")) planInput = input.deepCopy();
      if (stage.equals("FOLLOWUP")) followInput = input.deepCopy();
      var out = new MockInterviewModel(json).call(stage, input);
      if (stage.equals("PLAN") && tamperPoint)
        ((ObjectNode) out.path("questions").get(0)).put("pointId", "not-a-selected-point");
      return out;
    }
  }
}
