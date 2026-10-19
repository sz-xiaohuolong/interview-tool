package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.dto.ModelSettings;
import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.InterviewService;
import cn.interview.service.interview.AgentPracticePlanner;
import cn.interview.service.knowledge.AgentTopicCatalog;
import cn.interview.service.knowledge.KnowledgeService;
import cn.interview.service.model.ModelConfigurationService;
import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 人工批准后的两场连续练习；仅作为显式开启的本机验收工具。 */
@EnabledIfEnvironmentVariable(named = "AGENT_TOPICS_LIVE_CONTINUATION", matches = "true")
@SpringBootTest(classes = Application.class, properties = {"app.training.enabled=false", "app.interview.followup-deadline-ms=31000"})
@Import(AgentTopicContinuationLiveTest.Configuration.class)
class AgentTopicContinuationLiveTest {
  static final Path DATA = AgentTopicPilotLiveTest.isolatedData();
  @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
  @Autowired InterviewService service;
  @Autowired InterviewSessionStore store;
  @Autowired ObjectMapper json;
  @Autowired ModelConfigurationService settings;
  @Autowired RecordingGateway gateway;

  @Test void 人工确认后两场真实练习沿用同一预算() throws Exception {
    assertThat(Runtime.version().feature()).as("后两场固定Java21").isEqualTo(21);
    assertThat(settings.connection().provider()).isEqualTo("openai");
    assertThat(settings.connection().configured()).isTrue();
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("DOCUMENT_MAP.md"))) root = root.getParent();
    if (root == null) throw new IllegalStateException("无法定位仓库");
    var git = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir").start();
    String common = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    if (git.waitFor() != 0 || common.isBlank()) throw new IllegalStateException("无法定位固定共享账本");
    Path evidence = Path.of(common).getParent().resolve(".evidence/agent-topic-coverage");
    var answersPath = root.resolve("evals/agent-topic-coverage/continuation-answers.json");
    var answers = json.readTree(Files.readString(answersPath));
    assertThat(answers.path("synthetic").asBoolean()).isTrue();
    try (var budget = new AgentTopicContinuationLedger(json, evidence)) {
      var metadata = (ObjectNode) budget.ledger().path("continuation");
      Throwable failure = null;
      try {
      gateway.begin(budget, settings.connection());
      metadata.put("dataDirectory", DATA.toString()).put("javaVersion", System.getProperty("java.version"))
          .put("answersSha256", AgentTopicPilotLiveTest.hash(Files.readString(answersPath)));
      metadata.set("fixedAnswers", answers.deepCopy());
      var fixed = metadata.putObject("fixedParameters");
      for (String stage : List.of("PLAN", "FOLLOWUP", "REVIEW")) {
        boolean fast = stage.equals("FOLLOWUP");
        fixed.putObject(stage).put("model", gateway.frozen.model()).put("reasoningEffort", gateway.frozen.effort(fast))
            .put("temperature", 0.4).put("maxTokens", fast ? 800 : stage.equals("PLAN") ? 6500 : 10000)
            .put("httpTimeoutSeconds", fast ? 30 : stage.equals("PLAN") ? 180 : 150)
            .put("serviceDeadlineMillis", fast ? 31000 : stage.equals("PLAN") ? 300000 : 180000);
      }
      var sourceHashes = metadata.putObject("sourceSha256");
      for (String path : List.of("backend/src/main/resources/knowledge/agent-interview-topics.json",
          "backend/src/main/java/cn/interview/service/interview/InterviewService.java",
          "backend/src/main/java/cn/interview/service/interview/AgentPracticePlanner.java",
          "backend/src/main/java/cn/interview/service/knowledge/KnowledgeService.java",
          "backend/src/main/java/cn/interview/validation/InterviewOutputValidator.java",
          "backend/src/test/java/cn/interview/client/interview/AgentTopicContinuationLiveTest.java",
          "backend/src/test/java/cn/interview/client/interview/AgentTopicContinuationLedger.java"))
        sourceHashes.put(path, AgentTopicPilotLiveTest.digest(Files.readAllBytes(root.resolve(path))));
      assertThat(sourceHashes.path("backend/src/main/resources/knowledge/agent-interview-topics.json").asText())
          .isEqualTo(budget.ledger().path("catalogSha256").asText());
      try (var in = AgentTopicContinuationLiveTest.class.getResourceAsStream("/knowledge/agent-interview-topics.json")) {
        assertThat(AgentTopicPilotLiveTest.digest(in.readAllBytes())).isEqualTo(budget.ledger().path("catalogSha256").asText());
      }
      var promptHashes = metadata.putObject("promptSha256");
      for (String stage : List.of("plan", "followup", "review")) {
        String path = "prompts/interview/" + stage + ".txt";
        try (var in = AgentTopicContinuationLiveTest.class.getResourceAsStream("/" + path)) {
          String digest = AgentTopicPilotLiveTest.digest(in.readAllBytes());
          assertThat(digest).isEqualTo(AgentTopicPilotLiveTest.digest(Files.readAllBytes(root.resolve("backend/src/main/resources/" + path))));
          promptHashes.put(stage.toUpperCase(java.util.Locale.ROOT), digest);
        }
      }
      assertThat(promptHashes.path("PLAN").asText()).isEqualTo(budget.ledger().path("planPromptSha256").asText());
      var build = metadata.putObject("loadedBuildSha256");
      for (var type : List.of(InterviewService.class, AgentPracticePlanner.class, AgentTopicCatalog.class,
          KnowledgeService.class, InterviewOutputValidator.class, InterviewModelClient.class,
          OpenAiJsonClient.class, AgentTopicContinuationLiveTest.class, AgentTopicContinuationLedger.class,
          RecordingGateway.class, RecordingTransport.class))
        try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
          build.put(type.getName(), AgentTopicPilotLiveTest.digest(in.readAllBytes()));
        }
      budget.persist();
        exercise(service, store, json, answers, gateway::beginSession, gateway::observe);
        budget.complete();
      } catch (Throwable error) {
        failure = error;
        gateway.stopped = true;
        throw error;
      } finally {
        gateway.stopped = true;
        service.shutdown();
        boolean interrupted = drain(gateway);
        try {
          if (failure != null || interrupted) {
            var snapshots = metadata.putArray("stoppedSessionSnapshots");
            for (var row : store.list()) snapshots.add(store.get(row.path("id").asText()));
            budget.stop("连续练习失败，" + (failure == null ? "收尾等待被中断" : failure.getClass().getSimpleName()) + "；没有自动重试");
          } else budget.persist();
          if (interrupted && failure == null) throw new IllegalStateException("验收收尾被中断，证据已保存");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
      }
    }
  }

  static void exercise(InterviewService service, InterviewSessionStore store, ObjectMapper json,
      JsonNode answers, IntConsumer begin, BiConsumer<Integer, ObjectNode> observe) throws Exception {
    var allPoints = new HashSet<String>();
    var allThemes = new HashSet<String>();
    for (int number = 1; number <= 2; number++) {
      begin.accept(number);
      var request = json.createObjectNode().put("requestId", UUID.randomUUID().toString())
          .put("sessionType", "QUICK").put("role", "Agent应用工程师").put("level", "JUNIOR")
          .put("plannedQuestionCount", 3).put("practicePreference", "NEW_FIRST");
      var created = service.create(request);
      String id = created.path("id").asText();
      var view = settled(service, id);
      assertThat(view.path("status").asText()).isEqualTo("IN_PROGRESS");
      var internal = store.get(id);
      assertThat(internal.path("mock").asBoolean()).isFalse();
      assertThat(internal.path("questions")).hasSize(3);
      for (var point : internal.path("agentPractice").path("points")) {
        assertThat(allPoints.add(point.path("id").asText())).as("两场未练优先不重复实际考点").isTrue();
        allThemes.add(point.path("theme").asText());
      }
      observe.accept(number, internal);
      int submitted = 0;
      var answerOrdinals = new HashMap<String, Integer>();
      while (view.path("status").asText().equals("IN_PROGRESS")) {
        assertThat(submitted).as("每场最多九次追问决策").isLessThan(9);
        internal = store.get(id);
        var question = internal.path("currentQuestion");
        String pointId = question.path("pointId").asText();
        int ordinal = answerOrdinals.getOrDefault(pointId, 0);
        assertThat(ordinal).isLessThan(3);
        var fixed = answers.path("points").path(pointId);
        assertThat(fixed).as("本次问题必须有已固定合成回答：%s", pointId).hasSize(3);
        String answer = fixed.get(ordinal).asText();
        var body = action(json, view).put("expectedQuestionId", question.path("questionId").asText())
            .put("content", answer);
        service.action(id, "answers", body);
        answerOrdinals.put(pointId, ordinal + 1);
        submitted++;
        view = settled(service, id);
        internal = store.get(id);
        assertThat(view.path("status").asText()).isIn("IN_PROGRESS", "COMPLETED");
        assertThat(internal.path("metrics").path("lastFallback").asBoolean()).as("发生备用题则停批").isFalse();
        assertThat(internal.path("messages").findValuesAsText("fallback")).doesNotContain("true");
        assertThat(internal.path("backupsUsed")).isEmpty();
        assertThat(view.has("diagnostics")).isFalse();
        assertThat(view.has("agentPractice")).isFalse();
        assertThat(internal.path("diagnostics")).hasSize(submitted);
        var trace = internal.path("diagnostics").get(submitted - 1);
        assertThat(trace.path("questionId").asText()).isEqualTo(question.path("questionId").asText());
        String messageId = trace.path("answerMessageId").asText();
        var candidate = java.util.stream.StreamSupport.stream(internal.path("messages").spliterator(), false)
            .filter(m -> m.path("id").asText().equals(messageId)).findFirst().orElseThrow();
        assertThat(candidate.path("role").asText()).isEqualTo("USER");
        assertThat(candidate.path("content").asText()).isEqualTo(answer);
        String quote = trace.path("diagnosis").path("answerQuote").asText();
        assertThat(quote).isNotBlank();
        assertThat(answer).contains(quote);
        observe.accept(number, internal);
      }
      assertThat(view.path("status").asText()).isEqualTo("COMPLETED");
      assertThat(answerOrdinals).hasSize(3);
      service.action(id, "finish", action(json, view));
      view = settled(service, id);
      internal = store.get(id);
      assertThat(view.path("status").asText()).isEqualTo("REPORT_READY");
      var coverage = view.path("report").path("topicCoverage");
      assertThat(coverage.path("askedCount").asInt()).isEqualTo(3);
      assertThat(coverage.path("answeredCount").asInt()).isEqualTo(3);
      assertThat(coverage.path("newCount").asInt()).isEqualTo(3);
      assertThat(coverage.path("reviewCount").asInt()).isZero();
      assertThat(view.path("report").path("diagnostics")).hasSize(submitted);
      for (var item : coverage.path("items")) {
        assertThat(item.path("answered").asBoolean()).isTrue();
        assertThat(internal.path("messages").findValuesAsText("questionId")).contains(item.path("questionId").asText());
      }
      observe.accept(number, internal);
    }
    assertThat(allPoints).hasSize(6);
    assertThat(allThemes).as("默认全主题连续两场覆盖六个板块").hasSize(6);
  }

  private static ObjectNode action(ObjectMapper json, JsonNode session) {
    return json.createObjectNode().put("requestId", UUID.randomUUID().toString())
        .put("expectedRevision", session.path("revision").asInt());
  }

  static boolean drain(RecordingGateway gateway) {
    boolean interrupted = Thread.interrupted();
    while (true) {
      try {
        if (gateway.awaitIdle(1000)) return interrupted;
        System.out.println("正在等待在途请求结束并保存原始证据");
      } catch (InterruptedException error) { interrupted = true; }
    }
  }

  private static ObjectNode settled(InterviewService service, String id) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(310);
    while (System.nanoTime() < deadline) {
      var session = service.get(id);
      if (!Set.of("QUEUED", "PREPARING", "ANSWERING", "REPORTING").contains(session.path("status").asText())) return session;
      Thread.sleep(50);
    }
    throw new AssertionError("等待生产阶段超过限定时间，停止后续调用");
  }

  @TestConfiguration static class Configuration {
    @Bean @Primary RecordingTransport recordingTransport(ObjectMapper json) { return new RecordingTransport(json); }
    @Bean @Primary RecordingGateway recordingGateway(InterviewModelClient real, RecordingTransport transport) {
      var gateway = new RecordingGateway(real); transport.owner = gateway; return gateway;
    }
  }

  static class RecordingGateway implements InterviewModelGateway {
    final InterviewModelClient real;
    final ThreadLocal<ObjectNode> active = new ThreadLocal<>();
    volatile AgentTopicContinuationLedger budget;
    volatile ModelSettings frozen;
    volatile int sessionNumber;
    volatile boolean stopped;
    volatile ObjectNode latest;
    int inFlight;
    RecordingGateway(InterviewModelClient real) { this.real = real; }
    void begin(AgentTopicContinuationLedger budget, ModelSettings frozen) {
      this.budget = budget; this.frozen = frozen;
      var pilot = budget.ledger().path("attempts").get(0).path("parameters");
      assertThat(frozen.model()).isEqualTo(pilot.path("model").asText());
      assertThat(frozen.effort(false)).isEqualTo(pilot.path("reasoningEffort").asText());
      assertThat(AgentTopicPilotLiveTest.hash(frozen.baseUrl())).isEqualTo(pilot.path("serviceSha256").asText());
      assertThat(frozen.fastModel().isBlank() ? frozen.model() : frozen.fastModel()).isEqualTo(frozen.model());
    }
    void beginSession(int number) { sessionNumber = number; }
    synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
      while (inFlight > 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
      return true;
    }
    public ObjectNode call(String stage, ObjectNode input, int timeout) {
      throw new IllegalStateException("验收必须保留真实用量回调");
    }
    public ObjectNode call(String stage, ObjectNode input, int timeout, Consumer<ObjectNode> reportUsage) {
      ObjectNode attempt;
      synchronized (this) {
        if (stopped || budget == null) throw new IllegalStateException("验收未准备或已停批");
        try { attempt = budget.reserve(sessionNumber, stage, input); }
        catch (Exception error) { stopped = true; throw new IllegalStateException("无法预登记，未发送请求", error); }
        inFlight++;
      }
      latest = attempt; active.set(attempt);
      System.out.println("验收调用已登记：第" + sessionNumber + "场，" + stage + "，总账第" + attempt.path("number").asInt() + "次");
      try {
        var result = real.call(stage, input, timeout, usage -> {
          attempt.set("usage", usage.deepCopy()); reportUsage.accept(usage);
        });
        if (!attempt.has("sentAt") || !attempt.has("parameters") || !attempt.has("rawOutput")
            || attempt.path("returnedModel").asText().isBlank()
            || !attempt.path("finishReason").asText().equalsIgnoreCase("stop"))
          throw new IllegalStateException("本次结果缺少真实传输证据，已停批");
        String pilotModel = budget.ledger().path("attempts").get(0).path("returnedModel").asText();
        if (!pilotModel.isBlank() && !attempt.path("returnedModel").asText().equals(pilotModel))
          throw new IllegalStateException("实际返回模型与首批不一致，已停批");
        attempt.set("validatedOutput", result.deepCopy());
        attempt.put("status", "CLIENT_VALIDATED");
        return result;
      } catch (Throwable error) {
        stopped = true;
        attempt.put("status", "FAILED").put("errorType", error.getClass().getSimpleName());
        if (error instanceof ApiException api) attempt.put("errorCode", api.code);
        try { budget.stop("真实请求失败，无自动重试"); }
        catch (Exception persistence) { error.addSuppressed(persistence); }
        if (error instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException("真实练习失败，已停批", error);
      } finally {
        active.remove();
        attempt.put("finishedAt", Instant.now().toString()).put("usageIncomplete",
            !attempt.path("usage").path("promptTokens").isIntegralNumber()
                || !attempt.path("usage").path("completionTokens").isIntegralNumber());
        try { persist(); }
        finally { synchronized (this) { inFlight--; notifyAll(); } }
      }
    }
    void observe(int number, ObjectNode session) {
      var sessions = budget.ledger().withObject("/continuation/sessions");
      sessions.set(Integer.toString(number), session.deepCopy());
      if (latest != null && latest.path("status").asText().equals("CLIENT_VALIDATED"))
        latest.put("status", "SERVICE_VALIDATED").put("serviceObservedAt", Instant.now().toString());
      persist();
      System.out.println("真实练习已核验：第" + number + "场，" + session.path("status").asText()
          + "，已答" + session.path("diagnostics").size() + "次");
    }
    void persist() {
      try { budget.persist(); }
      catch (Exception error) { stopped = true; throw new IllegalStateException("无法持久化验收账本，已停批", error); }
    }
  }

  static class RecordingTransport extends OpenAiJsonClient {
    RecordingGateway owner;
    RecordingTransport(ObjectMapper json) { super(json); }
    @Override public Reply exchange(ModelSettings settings, String prompt, ObjectNode input, int maxTokens,
        int timeout, boolean fast, Consumer<ObjectNode> usage) {
      var attempt = owner.active.get();
      if (attempt == null || owner.stopped || !settings.equals(owner.frozen))
        throw new IllegalStateException("请求没有预登记或连接配置已变化，未发送请求");
      String stage = attempt.path("stage").asText();
      int expectedTokens = switch (stage) { case "PLAN" -> 6500; case "FOLLOWUP" -> 800; case "REVIEW" -> 10000;
        default -> throw new IllegalStateException("禁止未批准阶段"); };
      int expectedTimeout = switch (stage) { case "PLAN" -> 180; case "FOLLOWUP" -> 30; default -> 150; };
      if (maxTokens != expectedTokens || timeout != expectedTimeout || fast != stage.equals("FOLLOWUP"))
        throw new IllegalStateException("请求参数与生产配置不一致，未发送请求");
      attempt.putObject("parameters").put("model", fast && !settings.fastModel().isBlank() ? settings.fastModel() : settings.model())
          .put("reasoningEffort", settings.effort(fast)).put("temperature", 0.4).put("maxTokens", maxTokens)
          .put("httpTimeoutSeconds", timeout).put("provider", settings.provider()).put("preset", settings.preset())
          .put("serviceDeadlineMillis", fast ? 31000 : stage.equals("PLAN") ? 300000 : 180000)
          .put("serviceSha256", AgentTopicPilotLiveTest.hash(settings.baseUrl()))
          .put("extraBodySha256", AgentTopicPilotLiveTest.hash(settings.extraBody(fast).toString()));
      attempt.put("prompt", prompt).put("promptSha256", AgentTopicPilotLiveTest.hash(prompt));
      assertThat(attempt.path("promptSha256").asText())
          .isEqualTo(owner.budget.ledger().path("continuation").path("promptSha256").path(stage).asText());
      attempt.put("sentAt", Instant.now().toString());
      owner.persist();
      long started = System.nanoTime();
      try {
        var reply = super.exchange(settings, prompt, input, maxTokens, timeout, fast, usage);
        attempt.put("rawOutput", reply.text()).put("finishReason", reply.finishReason())
            .put("returnedModel", reply.model()).put("systemFingerprint", reply.fingerprint());
        return reply;
      } finally {
        attempt.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        owner.persist();
      }
    }
  }
}
