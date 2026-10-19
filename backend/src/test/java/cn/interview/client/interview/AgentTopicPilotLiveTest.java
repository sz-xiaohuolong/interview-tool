package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.dto.ModelSettings;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.AgentPracticePlanner;
import cn.interview.service.knowledge.AgentTopicCatalog;
import cn.interview.service.knowledge.KnowledgeService;
import cn.interview.service.model.ModelConfigurationService;
import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 独立的六主题选题抽查；人工关卡后的22次不由本试跑执行。 */
@EnabledIfEnvironmentVariable(named = "AGENT_TOPICS_LIVE_PILOT", matches = "true")
@SpringBootTest(classes = Application.class, properties = {"app.training.enabled=false"})
class AgentTopicPilotLiveTest {
  static final Path DATA = isolatedData();
  @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
  @Autowired ObjectMapper json;
  @Autowired InterviewSessionStore store;
  @Autowired KnowledgeService knowledge;
  @Autowired ModelConfigurationService settings;
  @Autowired InterviewOutputValidator validator;
  Path sharedLedger;

  static Path isolatedData() {
    try {
      var directory = Files.createTempDirectory("agent-topic-pilot-");
      String source = System.getenv("AGENT_TOPICS_LIVE_MODEL_CONFIG");
      if (source != null && !source.isBlank() && Files.isRegularFile(Path.of(source))) {
        Files.copy(Path.of(source), directory.resolve("model-config.json"));
        Files.setPosixFilePermissions(directory.resolve("model-config.json"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      }
      return directory;
    } catch (Exception error) { throw new IllegalStateException("无法准备隔离选题环境"); }
  }

  @Test void 六主题各一次真实规划并停在人工确认前() throws Exception {
    assertThat(settings.connection().provider()).isEqualTo("openai");
    assertThat(settings.connection().configured()).isTrue();
    Path root = Path.of("").toAbsolutePath().normalize();
    while (root != null && !Files.exists(root.resolve("DOCUMENT_MAP.md"))) root = root.getParent();
    if (root == null) throw new IllegalStateException("无法定位仓库");
    Path evidence = root.resolve(".evidence/agent-topic-coverage");
    var git = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir").start();
    String common = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (git.waitFor() != 0 || common.isBlank()) throw new IllegalStateException("无法定位共享预算账本");
    Path sharedDirectory = Path.of(common).getParent().resolve(".evidence/agent-topic-coverage");
    Files.createDirectories(sharedDirectory);
    sharedLedger = sharedDirectory.resolve("ledger.json");
    Files.createDirectories(evidence);
    var ledger = json.createObjectNode().put("protocol", "Agent考点覆盖独立试跑")
        .put("totalBudget", 28).put("pilotBudget", 6).put("automaticRetries", 0)
        .put("synthetic", true).put("humanConfirmed", false).put("status", "PREPARED")
        .put("startedAt", Instant.now().toString());
    ledger.put("catalogSha256", hash(Files.readString(root.resolve("backend/src/main/resources/knowledge/agent-interview-topics.json"))))
        .put("planPromptSha256", hash(Files.readString(root.resolve("backend/src/main/resources/prompts/interview/plan.txt"))))
        .put("runnerSha256", hash(Files.readString(root.resolve("backend/src/test/java/cn/interview/client/interview/AgentTopicPilotLiveTest.java"))));
    var build = ledger.putObject("loadedBuildSha256");
    for (var type : List.of(AgentPracticePlanner.class, AgentTopicCatalog.class, InterviewModelClient.class,
        InterviewOutputValidator.class, OpenAiJsonClient.class, AgentTopicPilotLiveTest.class)) {
      try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
        if (stream == null) throw new IllegalStateException("无法摘要已加载构建");
        build.put(type.getName(), digest(stream.readAllBytes()));
      }
    }
    try (var stream = AgentTopicPilotLiveTest.class.getResourceAsStream("/knowledge/agent-interview-topics.json")) {
      assertThat(digest(stream.readAllBytes())).isEqualTo(ledger.path("catalogSha256").asText());
    }
    try (var stream = AgentTopicPilotLiveTest.class.getResourceAsStream("/prompts/interview/plan.txt")) {
      assertThat(digest(stream.readAllBytes())).isEqualTo(ledger.path("planPromptSha256").asText());
    }
    var attempts = ledger.putArray("attempts");
    var planner = new AgentPracticePlanner(json, store);
    try (var channel = FileChannel.open(sharedDirectory.resolve("pilot.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null) throw new IllegalStateException("试跑已在执行");
      // 固定账本位置且禁止覆盖，换输出目录不能重置试跑额度。
      Files.writeString(sharedLedger, json.writeValueAsString(ledger), StandardOpenOption.CREATE_NEW);
      try {
        for (String theme : AgentTopicCatalog.THEME_IDS) {
          var session = json.createObjectNode().put("sessionType", "QUICK").put("role", "Agent应用工程师")
              .put("level", "JUNIOR").put("plannedQuestionCount", 3);
          var request = session.deepCopy(); request.putArray("agentTopics").add(theme);
          var input = session.putObject("input").put("sessionType", "QUICK").put("role", "Agent应用工程师")
              .put("level", "JUNIOR").put("totalQuestions", 3).put("resume", "").put("jd", "");
          planner.configure(session, request, input);
          var analysis = json.createObjectNode(); analysis.putArray("topics").add(theme);
          var plan = planner.plan(session, analysis);
          input.set("agentPractice", plan);
          var summary = input.putObject("summary");
          summary.putArray("technicalStack").add("Agent应用工程");
          summary.putArray("responsibilities"); summary.putArray("clarifications");
          summary.putArray("focus").add("合成技术与工程场景抽查");
          var references = new java.util.LinkedHashSet<String>();
          plan.path("points").forEach(point -> point.path("referenceIds").forEach(ref -> references.add(ref.asText())));
          var sources = knowledge.retrieve(List.of(theme), false, List.copyOf(references));
          input.set("sources", knowledge.select(sources, plan.path("points").toString(), 6));
          if (attempts.size() >= 6) throw new IllegalStateException("已达到六次试跑上限");
          int number = attempts.size() + 1;
          var attempt = attempts.addObject().put("number", number).put("caseId", theme)
              .put("stage", "PLAN").put("status", "RESERVED").put("reservedAt", Instant.now().toString())
              .put("inputSha256", hash(input.toString()));
          attempt.set("input", input.deepCopy());
          ledger.put("status", "RUNNING").put("attemptCount", attempts.size());
          persist(evidence, ledger);
          var recorder = new RecordingTransport(json, attempt, () -> {
            try { persist(evidence, ledger); }
            catch (Exception error) { throw new IllegalStateException("发送前无法保存协议，未发送请求"); }
          });
          var client = new InterviewModelClient(json, settings, recorder);
          try {
            var output = client.call("PLAN", input, 180, usage -> attempt.set("usage", usage.deepCopy()));
            var questions = validator.questions(output, input.path("sources"), "", "", 3);
            validator.agentQuestions(output, questions, input);
            assertThat(questions).hasSize(3);
            attempt.set("validatedOutput", output);
            attempt.put("status", "STRUCTURE_VALIDATED").put("humanScore", "待人工确认");
          } catch (Throwable error) {
            attempt.put("status", "FAILED").put("errorType", error.getClass().getSimpleName());
            ledger.put("status", "STOPPED_ON_FAILURE");
            throw error;
          } finally {
            attempt.put("finishedAt", Instant.now().toString());
            attempt.put("usageIncomplete", !attempt.path("usage").path("promptTokens").isIntegralNumber()
                || !attempt.path("usage").path("completionTokens").isIntegralNumber());
            persist(evidence, ledger);
          }
        }
        ledger.put("status", "WAITING_HUMAN").put("remainingAfterPilot", 28 - attempts.size());
      } finally {
        ledger.put("finishedAt", Instant.now().toString());
        persist(evidence, ledger);
      }
    }
  }

  private void persist(Path directory, ObjectNode ledger) throws Exception {
    var temporary = Files.createTempFile(directory, "pilot-", ".tmp");
    try {
      Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(ledger));
      Files.move(temporary, directory.resolve("ledger.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      if (!sharedLedger.equals(directory.resolve("ledger.json"))) {
        var sharedTemporary = Files.createTempFile(sharedLedger.getParent(), "pilot-budget-", ".tmp");
        try {
          Files.setPosixFilePermissions(sharedTemporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
          Files.writeString(sharedTemporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(ledger));
          Files.move(sharedTemporary, sharedLedger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(sharedTemporary); }
      }
    } finally { Files.deleteIfExists(temporary); }
  }
  static String hash(String value) {
    return digest(value.getBytes(StandardCharsets.UTF_8));
  }
  static String digest(byte[] value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    catch (Exception error) { throw new IllegalStateException("无法计算摘要"); }
  }
  static class RecordingTransport extends OpenAiJsonClient {
    final ObjectNode attempt;
    final Runnable beforeSend;
    RecordingTransport(ObjectMapper json, ObjectNode attempt, Runnable beforeSend) {
      super(json); this.attempt = attempt; this.beforeSend = beforeSend;
    }
    @Override public Reply exchange(ModelSettings settings, String prompt, ObjectNode input, int maxTokens,
        int timeout, boolean fast, Consumer<ObjectNode> usage) {
      if (fast || maxTokens != 6500 || timeout != 180) throw new IllegalStateException("试跑阶段参数越界");
      attempt.putObject("parameters").put("model", settings.model()).put("reasoningEffort", settings.effort(false))
          .put("temperature", 0.4).put("maxTokens", maxTokens).put("httpTimeoutSeconds", timeout)
          .put("provider", settings.provider()).put("serviceSha256", hash(settings.baseUrl()))
          .put("extraBodySha256", hash(settings.extraBody(false).toString()));
      attempt.put("loadedPromptSha256", hash(prompt));
      beforeSend.run();
      long started = System.nanoTime();
      try {
        var reply = super.exchange(settings, prompt, input, maxTokens, timeout, fast, usage);
        attempt.put("rawOutput", reply.text()).put("finishReason", reply.finishReason()).put("returnedModel", reply.model());
        return reply;
      } finally { attempt.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000); }
    }
  }
}
