package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.mock.MockInterviewModel;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 在真实Service及数据库中离线验证验收工具，只有外部模型使用显式替身。 */
@SpringBootTest(classes = Application.class, properties = {"app.llm.provider=openai", "app.llm.api-key=offline-test", "app.llm.model=offline-test",
    "app.training.enabled=false", "app.knowledge.network-enabled=false"})
@Import(AgentTopicContinuationFlowTest.Configuration.class)
class AgentTopicContinuationFlowTest {
  static final Path DATA = temporary();
  @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
  @Autowired InterviewService service;
  @Autowired InterviewSessionStore store;
  @Autowired ObjectMapper json;
  @Autowired ControlledModel model;

  @BeforeEach void reset() { for (var s : store.list()) service.delete(s.path("id").asText()); }

  @Test void 两场各三题按真实提问轮换主题并逐答核验诊断及复盘() throws Exception {
    var snapshots = new LinkedHashMap<Integer, ObjectNode>();
    AgentTopicContinuationLiveTest.exercise(service, store, json, answers(), ignored -> {}, snapshots::put);
    assertThat(snapshots).hasSize(2);
    for (var s : snapshots.values()) {
      assertThat(s.path("status").asText()).isEqualTo("REPORT_READY");
      assertThat(s.path("report").path("topicCoverage").path("askedCount").asInt()).isEqualTo(3);
      assertThat(s.path("report").path("topicCoverage").path("answeredCount").asInt()).isEqualTo(3);
    }
    var themes = snapshots.values().stream().flatMap(s -> s.path("questions").findValuesAsText("topic").stream()).distinct().toList();
    assertThat(themes).hasSize(6);
  }

  @Test void 服务端降级立即停止且不创建第二场() throws Exception {
    int before = store.list().size();
    model.badQuote = true;
    try {
      assertThatThrownBy(() -> AgentTopicContinuationLiveTest.exercise(service, store, json,
          answers(), ignored -> {}, (number, snapshot) -> {})).isInstanceOf(AssertionError.class);
      assertThat(store.list()).hasSize(before + 1);
    } finally { model.badQuote = false; }
  }

  JsonNode answers() {
    var result = json.createObjectNode(); var points = result.putObject("points");
    for (var id : List.of("workflow-autonomy-boundary", "context-budget-priority", "tool-schema-semantic-validation",
        "source-ingestion-integrity", "representative-eval-risk-strata", "chatmodel-business-adapter",
        "observation-backed-task-state", "summary-fidelity-validation", "least-privilege-tool-execution"))
      points.putArray(id).add("这是离线合成回答。我会先校验约束，缺少证据时继续核实。")
          .add("补充一个假设分支：超时后保留状态并阻断写操作；尚未做故障注入。")
          .add("我会加入固定失败样本对照验证，这仍是方案，没有实际测试结果。");
    return result;
  }
  static Path temporary() { try { return Files.createTempDirectory("agent-topic-continuation-offline-"); }
    catch (Exception e) { throw new IllegalStateException(e); } }
  @TestConfiguration static class Configuration {
    @Bean @Primary ControlledModel model(ObjectMapper json) { return new ControlledModel(json); }
  }
  static class ControlledModel implements InterviewModelGateway {
    final ObjectMapper json; volatile boolean badQuote;
    ControlledModel(ObjectMapper json) { this.json = json; }
    public ObjectNode call(String stage, ObjectNode input, int timeout) {
      var result = new MockInterviewModel(json).call(stage, input);
      if (stage.equals("FOLLOWUP") && badQuote)
        result.withObject("/diagnosis").put("answerQuote", "候选人从未讲过这段话");
      return result;
    }
  }
}
