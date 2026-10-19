package cn.interview.client.interview;

import cn.interview.dto.ModelSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** 用可控外部延迟验证账本关闭前完成用量和响应的保存，不发送网络请求。 */
class AgentTopicContinuationGatewayTest {
  @TempDir Path directory;
  final ObjectMapper json = new ObjectMapper();

  @Test void 请求仍在返回时不能视为已排空且迟到用量必须先落盘() throws Exception {
    approvedPilot();
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    var reference = new AtomicReference<AgentTopicContinuationLiveTest.RecordingGateway>();
    var client = new InterviewModelClient(json, "openai", "https://example.invalid", "offline-test", "offline-test", "", "", "") {
      @Override public ObjectNode call(String stage, ObjectNode input, int timeout, Consumer<ObjectNode> usage) {
        entered.countDown();
        try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("离线同步失败"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        usage.accept(json.createObjectNode().put("promptTokens", 17).put("completionTokens", 3));
        reference.get().active.get().put("sentAt", "合成发送时间").put("rawOutput", "合成外部响应")
            .put("returnedModel", "offline-test").put("finishReason", "stop").putObject("parameters");
        return json.createObjectNode().put("synthetic", true);
      }
    };
    try (var ledger = new AgentTopicContinuationLedger(json, directory);
        var executor = Executors.newSingleThreadExecutor()) {
      var gateway = new AgentTopicContinuationLiveTest.RecordingGateway(client);
      reference.set(gateway);
      gateway.budget = ledger; gateway.sessionNumber = 1;
      var task = executor.submit(() -> gateway.call("PLAN", json.createObjectNode(), 180, ignored -> {}));
      try {
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(gateway.awaitIdle(10)).as("仍在执行的请求不能提前释放账本").isFalse();
      } finally { release.countDown(); }
      assertThat(gateway.awaitIdle(1000)).isTrue();
      task.get(1, TimeUnit.SECONDS);
      var disk = json.readTree(Files.readString(directory.resolve("ledger.json")));
      assertThat(disk.path("attempts").get(6).path("usage").path("promptTokens").asInt()).isEqualTo(17);
      assertThat(disk.path("attempts").get(6).path("finishedAt").asText()).isNotBlank();
    }
  }

  @Test void 未经过真实传输的Mock结果必须停批() throws Exception {
    approvedPilot();
    var client = new InterviewModelClient(json, "mock", "https://example.invalid", "", "offline-test", "", "", "");
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      var gateway = new AgentTopicContinuationLiveTest.RecordingGateway(client);
      gateway.budget = ledger; gateway.sessionNumber = 1;
      assertThatThrownBy(() -> gateway.call("PLAN", json.createObjectNode().put("totalQuestions", 3), 180, ignored -> {}))
          .isInstanceOf(IllegalStateException.class);
      var disk = json.readTree(Files.readString(directory.resolve("ledger.json")));
      assertThat(disk.path("status").asText()).isEqualTo("STOPPED_ON_FAILURE");
      assertThat(disk.path("attempts")).hasSize(7);
    }
  }

  @Test void 等待线程已中断也必须保留账本直到请求排空() throws Exception {
    var client = new InterviewModelClient(json, "mock", "https://example.invalid", "", "offline-test", "", "", "");
    var gateway = new AgentTopicContinuationLiveTest.RecordingGateway(client);
    gateway.inFlight = 1;
    var entered = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var task = executor.submit(() -> {
        Thread.currentThread().interrupt();
        entered.countDown();
        return AgentTopicContinuationLiveTest.drain(gateway);
      });
      try {
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(30);
        assertThat(task.isDone()).as("中断不能提前结束证据排空").isFalse();
      } finally { synchronized (gateway) { gateway.inFlight = 0; gateway.notifyAll(); } }
      assertThat(task.get(1, TimeUnit.SECONDS)).as("调用方必须获知中断以在保存后恢复标志").isTrue();
    }
  }

  @Test void 已无在途请求时也先清除中断标志以便保存证据() {
    var client = new InterviewModelClient(json, "mock", "https://example.invalid", "", "offline-test", "", "", "");
    var gateway = new AgentTopicContinuationLiveTest.RecordingGateway(client);
    Thread.currentThread().interrupt();
    try {
      assertThat(AgentTopicContinuationLiveTest.drain(gateway)).isTrue();
      assertThat(Thread.currentThread().isInterrupted()).as("写入完成前保持可执行文件I/O").isFalse();
    } finally { Thread.interrupted(); }
  }

  void approvedPilot() throws Exception {
    String material = "仅供离线测试的合成材料";
    Files.writeString(directory.resolve("human-review.md"), material);
    var pilot = json.createObjectNode().put("totalBudget", 28).put("pilotBudget", 6).put("humanConfirmed", true);
    pilot.putObject("humanApproval").put("materialSha256", AgentTopicPilotLiveTest.hash(material));
    var attempts = pilot.putArray("attempts");
    for (int i = 1; i <= 6; i++) attempts.addObject().put("number", i).put("stage", "PLAN").put("status", "STRUCTURE_VALIDATED");
    Files.writeString(directory.resolve("ledger.json"), json.writeValueAsString(pilot));
  }
}
