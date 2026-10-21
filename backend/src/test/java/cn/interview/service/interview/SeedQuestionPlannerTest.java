package cn.interview.service.interview;

import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.knowledge.AgentTopicCatalog;
import cn.interview.service.knowledge.SeedQuestionCatalog;
import cn.interview.validation.InterviewOutputValidator;
import cn.interview.client.mock.MockInterviewModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SeedQuestionPlannerTest {
  final ObjectMapper json = new ObjectMapper();
  @TempDir Path data;

  ObjectNode session() {
    return json.createObjectNode().put("id", UUID.randomUUID().toString()).put("sessionType", "QUICK")
        .put("role", "Agent 应用开发工程师").put("level", "JUNIOR").put("plannedQuestionCount", 3);
  }

  @Test void 按难度选取且素材已用完或考点未映射时回退动态出题() {
    var seeds = new SeedQuestionCatalog(json);
    var easy = seeds.choose("workflow-autonomy-boundary", "INTERN", Set.of());
    assertThat(easy.path("difficulty").asText()).isEqualTo("EASY");
    var hard = seeds.choose("workflow-autonomy-boundary", "SENIOR", Set.of());
    assertThat(hard.path("difficulty").asText()).isEqualTo("HARD");
    var used = new java.util.HashSet<String>();
    ObjectNode next;
    while ((next = seeds.choose("workflow-autonomy-boundary", "JUNIOR", used)) != null)
      assertThat(used.add(next.path("id").asText())).isTrue();
    assertThat(used).hasSize(4);
    assertThat(seeds.choose("workflow-autonomy-boundary", "JUNIOR", used)).isNull();
    assertThat(seeds.choose("tenant-memory-namespace", "JUNIOR", Set.of())).isNull();
  }

  @Test void 只排除同职级非Mock实际展示素材且不把规划题当已练() {
    var store = new InterviewSessionStore(json, data.toString());
    var planner = new AgentPracticePlanner(json, store);
    var current = session(); current.putArray("agentTopics").add("AGENT_ARCHITECTURE");
    var firstPlan = planner.plan(current, json.createObjectNode());
    var firstPoint = firstPlan.path("points").get(0);
    String seedId = firstPoint.path("seedQuestion").path("id").asText();
    assertThat(seedId).isNotBlank();
    var previous = session(); previous.put("mock", true);
    previous.putArray("messages").addObject().put("role", "ASSISTANT").put("questionId", "q1")
        .put("pointId", firstPoint.path("id").asText()).put("seedQuestionId", seedId);
    previous.set("questions", firstPlan.path("points"));
    store.save(previous);
    assertThat(planner.plan(current, json.createObjectNode()).path("points").get(0).path("seedQuestion").path("id").asText()).isEqualTo(seedId);
    previous.put("mock", false).put("level", "SENIOR"); store.save(previous);
    assertThat(planner.plan(current, json.createObjectNode()).path("points").get(0).path("seedQuestion").path("id").asText()).isEqualTo(seedId);
    previous.put("level", "JUNIOR"); store.save(previous);
    assertThat(planner.plan(current, json.createObjectNode()).findValuesAsText("id")).doesNotContain(seedId);
  }

  @Test void 已用完素材的考点仍被规划并且动态主问可通过校验() {
    var store = new InterviewSessionStore(json, data.toString());
    var previous = session(); var messages = previous.putArray("messages");
    var seeds = new SeedQuestionCatalog(json); var used = new java.util.HashSet<String>();
    for (var point : new AgentTopicCatalog(json).points()) {
      ObjectNode seed;
      while ((seed = seeds.choose(point.path("id").asText(), "JUNIOR", used)) != null) {
        used.add(seed.path("id").asText());
        messages.addObject().put("role", "ASSISTANT").put("questionId", UUID.randomUUID().toString())
            .put("pointId", point.path("id").asText()).put("seedQuestionId", seed.path("id").asText());
      }
    }
    store.save(previous);
    var current = session().put("totalQuestions", 3); var planner = new AgentPracticePlanner(json, store);
    var plan = planner.plan(current, json.createObjectNode());
    assertThat(plan.path("points")).hasSize(3);
    assertThat(plan.findValues("seedQuestion")).isEmpty();
    current.set("agentPractice", plan); current.putArray("sources");
    var raw = new MockInterviewModel(json).call("PLAN", current);
    assertThat(new InterviewOutputValidator(json).agentQuestions(raw, (ArrayNode) raw.path("questions").deepCopy(), current)).hasSize(3);
  }

  @Test void 拒绝伪造素材身份以及历史重复题干() {
    var store = new InterviewSessionStore(json, data.toString());
    var planner = new AgentPracticePlanner(json, store); var current = session().put("totalQuestions", 3);
    current.set("agentPractice", planner.plan(current, json.createObjectNode())); current.putArray("sources");
    var raw = new MockInterviewModel(json).call("PLAN", current);
    ((ObjectNode) raw.path("questions").get(0)).put("seedQuestionId", "jb-forged");
    assertThatThrownBy(() -> new InterviewOutputValidator(json).agentQuestions(raw, (ArrayNode) raw.path("questions").deepCopy(), current))
        .hasMessageContaining("参考原题");
    var previous = session(); previous.putArray("messages").addObject().put("role", "ASSISTANT")
        .put("questionKind", "MAIN").put("content", "Agent 如何 结束？"); store.save(previous);
    var questions = json.createArrayNode(); questions.addObject().put("question", "agent如何结束!");
    assertThatThrownBy(() -> planner.rejectRepeatedQuestions(questions, current)).hasMessageContaining("重复");
    previous.put("mock", true); store.save(previous);
    assertThatCode(() -> planner.rejectRepeatedQuestions(questions, current)).doesNotThrowAnyException();
  }
}
