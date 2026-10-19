package cn.interview.service.interview;

import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.knowledge.AgentTopicCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class AgentPracticePlannerTest {
  final ObjectMapper json = new ObjectMapper();
  @TempDir Path data;
  InterviewSessionStore store;
  AgentTopicCatalog catalog;
  AgentPracticePlanner planner;

  @BeforeEach void setup() {
    store = new InterviewSessionStore(json, data.toString());
    var value = json.createObjectNode().put("schemaVersion", 1);
    var themes = value.putArray("themes");
    var points = value.putArray("points");
    String[] ids = {"AGENT_ARCHITECTURE", "AGENT_CONTEXT_MEMORY", "AGENT_HARNESS_TOOL", "AGENT_RAG", "AGENT_EVALUATION", "AGENT_FRAMEWORK"};
    String[] prefixes = {"arc", "ctx", "tool", "rag", "eval", "framework"};
    value.putArray("references").addObject().put("id", "official").put("title", "合成参考")
        .put("url", "https://example.test/docs").put("kind", "OFFICIAL").put("scope", "仅合成说明");
    for (int index = 0; index < ids.length; index++) {
      themes.addObject().put("id", ids[index]).put("label", "合成主题" + index);
      for (int number = 1; number <= 3; number++) {
        var point = points.addObject().put("id", prefixes[index] + "-" + number).put("theme", ids[index])
            .put("title", "合成考点" + prefixes[index] + number).put("capability", "说明决策")
            .put("scenario", "说明合成场景").put("evaluationBoundary", "无真实经历推断");
        point.putArray("followupDirections").add("核对边界");
        point.putArray("referenceIds").add("official");
        point.putArray("discoveryReferences");
      }
    }
    catalog = new AgentTopicCatalog(json, value);
    planner = new AgentPracticePlanner(json, store, catalog);
  }

  ObjectNode request(int count) {
    return json.createObjectNode().put("role", "大模型应用 / Agent 开发工程师").put("level", "JUNIOR")
        .put("sessionType", "QUICK").put("plannedQuestionCount", count);
  }

  ObjectNode session(ObjectNode body) {
    var session = body.deepCopy().put("id", UUID.randomUUID().toString());
    var input = json.createObjectNode().put("totalQuestions", body.path("plannedQuestionCount").asInt());
    planner.configure(session, body, input);
    session.set("input", input);
    return session;
  }

  ObjectNode history(String date, String type, String pointId) {
    var value = request(3).put("id", UUID.randomUUID().toString()).put("createdAt", date).put("sessionType", type);
    value.putArray("messages").addObject().put("role", "ASSISTANT").put("id", "message-" + date)
        .put("questionId", "q-" + date).put("rootQuestionId", "q-" + date).put("pointId", pointId)
        .put("pointTheme", catalog.point(pointId).path("theme").asText()).put("pointTitle", "历史标题");
    store.save(value);
    return value;
  }

  ObjectNode historyAt(String createdAt, String pointId, String askedAt) {
    var value = history(createdAt, "QUICK", pointId);
    ((ObjectNode) value.path("messages").get(0)).put("createdAt", askedAt);
    store.save(value);
    return value;
  }

  List<String> pointIds(JsonNode plan) {
    return StreamSupport.stream(plan.path("points").spliterator(), false).map(item -> item.path("id").asText()).toList();
  }

  @Test void 默认新题在主题之间轮换且一次不重复考点() {
    var value = session(request(5));
    assertThat(pointIds(planner.plan(value, json.createObjectNode())))
        .containsExactly("arc-1", "ctx-1", "tool-1", "rag-1", "eval-1");
    assertThat(value.path("practicePreference").asText()).isEqualTo("NEW_FIRST");
    assertThat(value.path("input").path("agentTopics")).isEmpty();
  }

  @Test void 下一场优先轮换到上一场没有练过的主题() {
    var first = history("2026-02-01T00:00:00Z", "QUICK", "arc-1");
    var messages = first.withArray("messages");
    messages.addObject().put("role", "ASSISTANT").put("questionId", "q-context").put("pointId", "ctx-1");
    messages.addObject().put("role", "ASSISTANT").put("questionId", "q-tool").put("pointId", "tool-1");
    store.save(first);
    assertThat(pointIds(planner.plan(session(request(3)), json.createObjectNode())))
        .containsExactly("rag-1", "eval-1", "framework-1");
  }

  @Test void 手选单主题严格限制且重复主题规范去重() {
    var body = request(3);
    body.putArray("agentTopics").add("AGENT_RAG").add("AGENT_RAG");
    var value = session(body);
    assertThat(value.path("agentTopics")).hasSize(1);
    assertThat(pointIds(planner.plan(value, json.createObjectNode())))
        .containsExactly("rag-1", "rag-2", "rag-3");
  }

  @Test void 预检省略会话类型时按快速练习解释并默认五题() {
    var body = request(3); body.remove("sessionType");
    assertThat(planner.preview(body).path("requestedCount").asInt()).isEqualTo(3);
    body.remove("plannedQuestionCount");
    assertThat(planner.preview(body).path("requestedCount").asInt()).isEqualTo(5);
  }

  @Test void 近期未练已用完时从最久已练补齐并给出提示() {
    history("2026-01-01T00:00:00Z", "QUICK", "arc-2");
    history("2026-01-02T00:00:00Z", "QUICK", "arc-3");
    history("2026-01-03T00:00:00Z", "QUICK", "arc-1");
    var body = request(3); body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    var plan = planner.plan(session(body), json.createObjectNode());
    assertThat(pointIds(plan)).containsExactly("arc-2", "arc-3", "arc-1");
    assertThat(plan.path("warnings").toString()).contains("最久未练");
    assertThat(plan.path("newCount").asInt()).isZero();
    assertThat(plan.path("reviewCount").asInt()).isEqualTo(3);
  }

  @Test void 最近十场有效会话决定新题优先且全部历史可复习() {
    history("2020-01-01T00:00:00Z", "QUICK", "arc-1");
    for (int index = 1; index <= 10; index++) history(String.format("2026-01-%02dT00:00:00Z", index), "COMPREHENSIVE", "arc-2");
    var body = request(3); body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode()))).containsExactly("arc-1", "arc-3", "arc-2");
    body.put("practicePreference", "REVIEW").put("acceptReviewFill", true).put("expectedNewSupplementCount", 1);
    var preview = planner.preview(body);
    assertThat(preview.path("reviewAvailable").asInt()).isEqualTo(2);
    assertThat(preview.path("newSupplementCount").asInt()).isEqualTo(1);
    var plan = planner.plan(session(body), json.createObjectNode());
    assertThat(pointIds(plan)).containsExactly("arc-1", "arc-2", "arc-3");
    assertThat(plan.path("reviewCount").asInt()).isEqualTo(2);
  }

  @Test void 很早创建的会话刚继续展示考点时进入最近十场() {
    var continued = history("2020-01-01T00:00:00Z", "QUICK", "arc-1");
    ((ObjectNode) continued.path("messages").get(0)).put("createdAt", "2026-10-18T12:00:00Z");
    store.save(continued);
    for (int index = 1; index <= 10; index++)
      history(String.format("2026-01-%02dT00:00:00Z", index), "COMPREHENSIVE", "arc-2");
    var body = request(3); body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode())))
        .containsExactly("arc-3", "arc-2", "arc-1");
  }

  @Test void 最近十场按真实时间区分整秒和同秒内较晚的提问() {
    historyAt("2026-01-01T00:00:00Z", "arc-1", "2026-10-18T12:00:00Z");
    historyAt("2026-01-02T00:00:00Z", "arc-2", "2026-10-18T12:00:00.100Z");
    for (int index = 1; index <= 9; index++)
      historyAt(String.format("2026-02-%02dT00:00:00Z", index), "arc-3", "2026-10-19T00:00:00Z");
    var body = request(3); body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode())))
        .containsExactly("arc-1", "arc-2", "arc-3");
  }

  @Test void 复习最久未练排序按真实时间区分整秒和小数秒() {
    historyAt("2026-01-01T00:00:00Z", "arc-1", "2026-10-18T12:00:00Z");
    historyAt("2026-01-02T00:00:00Z", "arc-2", "2026-10-18T12:00:00.100Z");
    var body = request(2).put("practicePreference", "REVIEW");
    body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode())))
        .containsExactly("arc-1", "arc-2");
  }

  @Test void 同一场内重复提问按最后实际时间记录考点() {
    var repeated = historyAt("2026-01-01T00:00:00Z", "arc-1", "2026-10-18T12:00:00Z");
    repeated.withArray("messages").addObject().put("role", "ASSISTANT").put("questionId", "followup")
        .put("pointId", "arc-1").put("createdAt", "2026-10-18T12:00:00.100Z");
    store.save(repeated);
    historyAt("2026-01-02T00:00:00Z", "arc-2", "2026-10-18T12:00:00.050Z");
    var body = request(2).put("practicePreference", "REVIEW");
    body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode())))
        .containsExactly("arc-2", "arc-1");
  }

  @Test void 不同场次重复考点按最后实际时间记录() {
    historyAt("2026-01-01T00:00:00Z", "arc-1", "2026-10-18T12:00:00Z");
    historyAt("2026-01-02T00:00:00Z", "arc-1", "2026-10-18T12:00:00.100Z");
    historyAt("2026-01-03T00:00:00Z", "arc-2", "2026-10-18T12:00:00.050Z");
    var body = request(2).put("practicePreference", "REVIEW");
    body.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode())))
        .containsExactly("arc-2", "arc-1");
  }

  @Test void 主题轮换按真实最近提问时间区分整秒和小数秒() {
    historyAt("2026-01-01T00:00:00Z", "arc-1", "2026-10-18T12:00:00Z");
    historyAt("2026-01-02T00:00:00Z", "ctx-1", "2026-10-18T12:00:00.100Z");
    var body = request(2);
    body.putArray("agentTopics").add("AGENT_ARCHITECTURE").add("AGENT_CONTEXT_MEMORY");
    assertThat(pointIds(planner.plan(session(body), json.createObjectNode())))
        .containsExactly("arc-2", "ctx-2");
  }

  @Test void 复习空池不能开场而不足必须明确确认补新() {
    var body = request(3).put("practicePreference", "REVIEW");
    assertThat(planner.preview(body).path("canStart").asBoolean()).isFalse();
    assertThatThrownBy(() -> session(body)).isInstanceOf(ApiException.class).hasMessageContaining("复习");
    history("2026-01-01T00:00:00Z", "QUICK", "rag-1");
    var preview = planner.preview(body);
    assertThat(preview.path("requiresAcknowledgement").asBoolean()).isTrue();
    assertThat(preview.path("newSupplementCount").asInt()).isEqualTo(2);
    assertThat(preview.path("canStart").asBoolean()).isTrue();
    assertThatThrownBy(() -> session(body)).isInstanceOf(ApiException.class).hasMessageContaining("确认");
    body.put("acceptReviewFill", true).put("expectedNewSupplementCount", 2);
    assertThat(planner.preview(body).path("canStart").asBoolean()).isTrue();
    assertThat(planner.plan(session(body), json.createObjectNode()).path("points")).hasSize(3);
  }

  @Test void 复习补新确认绑定当前数量且准备前历史变化要求重新确认() {
    var first = history("2026-01-01T00:00:00Z", "QUICK", "rag-1");
    history("2026-01-02T00:00:00Z", "QUICK", "ctx-1");
    var body = request(3).put("practicePreference", "REVIEW").put("acceptReviewFill", true);
    assertThatThrownBy(() -> session(body)).isInstanceOf(ApiException.class).hasMessageContaining("重新");
    body.put("expectedNewSupplementCount", 2);
    assertThatThrownBy(() -> session(body)).isInstanceOf(ApiException.class).hasMessageContaining("重新");
    body.put("expectedNewSupplementCount", 1);
    var value = session(body);
    first.put("deletedAt", "2026-01-03"); store.save(first);
    assertThatThrownBy(() -> planner.plan(value, json.createObjectNode())).isInstanceOf(ApiException.class).hasMessageContaining("重新");
  }

  @Test void 不采用删除Mock无可靠点旧数据不同职级或普通岗位历史() {
    var deleted = history("2026-02-01T00:00:00Z", "QUICK", "arc-1").put("deletedAt", "2026-02-02"); store.save(deleted);
    var mock = history("2026-02-03T00:00:00Z", "QUICK", "ctx-1").put("mock", true); store.save(mock);
    var senior = history("2026-02-04T00:00:00Z", "QUICK", "tool-1").put("level", "SENIOR"); store.save(senior);
    var java = history("2026-02-05T00:00:00Z", "QUICK", "rag-1").put("role", "Java 后端工程师"); store.save(java);
    var legacy = request(3).put("id", UUID.randomUUID().toString()).put("createdAt", "2026-02-06");
    legacy.putArray("messages").addObject().put("role", "ASSISTANT").put("questionId", "legacy").put("content", "旧场次没有考点身份"); store.save(legacy);
    assertThat(planner.preview(request(3).put("practicePreference", "REVIEW")).path("reviewAvailable").asInt()).isZero();
    assertThat(pointIds(planner.plan(session(request(3)), json.createObjectNode()))).containsExactly("arc-1", "ctx-1", "tool-1");
  }

  @Test void 快速综合跨类型共享且重启后仍读取真实已问历史() {
    history("2026-02-01T00:00:00Z", "QUICK", "arc-1");
    history("2026-02-02T00:00:00Z", "COMPREHENSIVE", "ctx-1");
    var restarted = new AgentPracticePlanner(json, new InterviewSessionStore(json, data.toString()), catalog);
    var body = request(3).put("role", "人工智能应用工程师");
    assertThat(pointIds(restarted.plan(session(body), json.createObjectNode()))).containsExactly("tool-1", "rag-1", "eval-1");
    assertThat(restarted.preview(body.put("practicePreference", "REVIEW")).path("reviewAvailable").asInt()).isEqualTo(2);
  }

  @Test void 综合只提供技术候选并尊重分析主题和真实项目主线() {
    var body = request(8).put("sessionType", "COMPREHENSIVE");
    var value = session(body);
    var analysis = json.createObjectNode(); analysis.putArray("topics").add("AI_RAG");
    assertThat(pointIds(planner.plan(value, analysis))).containsExactly("rag-1", "rag-2", "rag-3");
    analysis.putArray("topics").add("AGENT_CONTEXT_MEMORY");
    assertThat(pointIds(planner.plan(value, analysis))).containsExactly("ctx-1", "ctx-2", "ctx-3");
    value.putObject("mainProject").put("hypothetical", false).put("brief", "我负责订单参数校验");
    analysis.putArray("topics").add("DATABASE");
    assertThat(planner.plan(value, analysis).path("points")).isEmpty();
    assertThat(planner.plan(session(request(3).put("role", "Java 后端工程师")), analysis)).isNull();
  }

  @Test void 综合复习未手选主题时保留创建预检的全目录范围() {
    for (String point : List.of("arc-1", "ctx-1", "tool-1", "rag-1", "eval-1"))
      history("2026-01-01T00:00:00Z", "QUICK", point);
    var body = request(8).put("sessionType", "COMPREHENSIVE").put("practicePreference", "REVIEW");
    var preview = planner.preview(body);
    assertThat(preview.path("requestedCount").asInt()).isEqualTo(5);
    assertThat(preview.path("reviewAvailable").asInt()).isEqualTo(5);
    assertThat(preview.path("newSupplementCount").asInt()).isZero();
    var value = session(body);
    value.putObject("mainProject").put("hypothetical", false).put("brief", "我负责订单参数校验");
    var analysis = json.createObjectNode(); analysis.putArray("topics").add("DATABASE");
    var plan = planner.plan(value, analysis);
    assertThat(pointIds(plan)).containsExactlyInAnyOrder("arc-1", "ctx-1", "tool-1", "rag-1", "eval-1");
    assertThat(plan.path("reviewCount").asInt()).isEqualTo(5);
    assertThat(plan.path("newCount").asInt()).isZero();
    assertThat(value.path("mainProject").path("brief").asText()).isEqualTo("我负责订单参数校验");
  }

  @Test void 综合复习补新确认数量不受分析主题改变() {
    history("2026-01-01T00:00:00Z", "QUICK", "arc-1");
    history("2026-01-02T00:00:00Z", "COMPREHENSIVE", "ctx-1");
    var body = request(8).put("sessionType", "COMPREHENSIVE").put("practicePreference", "REVIEW")
        .put("acceptReviewFill", true).put("expectedNewSupplementCount", 3);
    var preview = planner.preview(body);
    assertThat(preview.path("reviewAvailable").asInt()).isEqualTo(2);
    assertThat(preview.path("newSupplementCount").asInt()).isEqualTo(3);
    var analysis = json.createObjectNode(); analysis.putArray("topics").add("AI_RAG");
    var plan = planner.plan(session(body), analysis);
    assertThat(pointIds(plan)).hasSize(5).contains("arc-1", "ctx-1");
    assertThat(plan.path("requestedCount").asInt()).isEqualTo(5);
    assertThat(plan.path("newSupplementCount").asInt()).isEqualTo(3);
    assertThat(plan.path("reviewCount").asInt()).isEqualTo(2);
    assertThat(plan.path("newCount").asInt()).isEqualTo(3);
  }

  @Test void 综合复习显式选择的主题仍严格限制候选范围() {
    history("2026-01-01T00:00:00Z", "QUICK", "arc-1");
    history("2026-01-02T00:00:00Z", "QUICK", "rag-1");
    var body = request(6).put("sessionType", "COMPREHENSIVE").put("practicePreference", "REVIEW")
        .put("acceptReviewFill", true).put("expectedNewSupplementCount", 2);
    body.putArray("agentTopics").add("AGENT_RAG");
    var analysis = json.createObjectNode(); analysis.putArray("topics").add("AI_MEMORY");
    var plan = planner.plan(session(body), analysis);
    assertThat(pointIds(plan)).containsExactly("rag-1", "rag-2", "rag-3");
    assertThat(plan.path("reviewCount").asInt()).isEqualTo(1);
    assertThat(plan.path("newCount").asInt()).isEqualTo(2);
  }

  @Test void 配置前拒绝非法类型未知主题非法偏好和非Agent主题() {
    var invalid = List.of(
        request(3).put("agentTopics", "AGENT_RAG"),
        request(3).put("practicePreference", "OTHER"),
        request(3).put("acceptReviewFill", "true"));
    for (var body : invalid) assertThatThrownBy(() -> session(body)).isInstanceOf(ApiException.class);
    var unknown = request(3); unknown.putArray("agentTopics").add("UNKNOWN");
    assertThatThrownBy(() -> session(unknown)).isInstanceOf(ApiException.class);
    var java = request(3).put("role", "Java 后端工程师"); java.putArray("agentTopics").add("AGENT_RAG");
    assertThatThrownBy(() -> session(java)).isInstanceOf(ApiException.class);
    assertThat(AgentPracticePlanner.isAgentRole("storage 后端工程师")).isFalse();
    for (String role : List.of("Agent开发", "大模型应用", "智能体", "人工智能工程师", "LLM Engineer", "RAG 工程师", "AI 应用"))
      assertThat(AgentPracticePlanner.isAgentRole(role)).isTrue();
  }

  @Test void 覆盖只计实际问过的考点并按原问题定位不把计划或追问重复算入() {
    var value = session(request(3));
    value.set("agentPractice", planner.plan(value, json.createObjectNode()));
    var messages = value.putArray("messages");
    messages.addObject().put("id", "a1").put("role", "ASSISTANT").put("questionId", "q1").put("rootQuestionId", "q1")
        .put("pointId", "arc-1").put("pointReview", true);
    messages.addObject().put("id", "u1").put("role", "USER").put("questionId", "q1").put("content", "本场原答");
    messages.addObject().put("id", "a2").put("role", "ASSISTANT").put("questionId", "q2").put("rootQuestionId", "q1")
        .put("pointId", "arc-1").put("pointReview", true);
    messages.addObject().put("id", "a3").put("role", "ASSISTANT").put("questionId", "q3").put("pointId", "ctx-1");
    messages.addObject().put("id", "fake").put("role", "USER").put("questionId", "q4").put("pointId", "tool-1");
    var coverage = planner.coverage(value);
    assertThat(coverage.path("askedCount").asInt()).isEqualTo(2);
    assertThat(coverage.path("answeredCount").asInt()).isEqualTo(1);
    assertThat(coverage.path("newCount").asInt()).isEqualTo(1);
    assertThat(coverage.path("reviewCount").asInt()).isEqualTo(1);
    assertThat(coverage.path("items").get(0).path("questionId").asText()).isEqualTo("q1");
    assertThat(coverage.path("items").get(1).path("answered").asBoolean()).isFalse();
    assertThat(coverage.toString()).doesNotContain("本场原答", "tool-1");
  }

  @Test void 主问题未答但同根追问已答时仍计考点已答并定位主问题() {
    var value = session(request(3));
    var messages = value.putArray("messages");
    messages.addObject().put("role", "ASSISTANT").put("questionId", "q-main").put("rootQuestionId", "plan-1").put("pointId", "arc-1");
    messages.addObject().put("role", "ASSISTANT").put("questionId", "q-follow").put("rootQuestionId", "plan-1").put("pointId", "arc-1");
    messages.addObject().put("role", "USER").put("questionId", "q-follow").put("content", "针对追问说明边界");
    var coverage = planner.coverage(value);
    assertThat(coverage.path("askedCount").asInt()).isEqualTo(1);
    assertThat(coverage.path("answeredCount").asInt()).isEqualTo(1);
    assertThat(coverage.path("items").get(0).path("questionId").asText()).isEqualTo("q-main");
    assertThat(coverage.path("items").get(0).path("rootQuestionId").asText()).isEqualTo("plan-1");
  }
}
