package cn.interview.v3.service;

import cn.interview.entity.Material;
import cn.interview.exception.ApiException;
import cn.interview.service.MaterialService;
import cn.interview.service.ProjectEvidenceService;
import cn.interview.service.ProjectEvidenceSource;
import cn.interview.v3.knowledge.KnowledgeService;
import cn.interview.v3.mapper.V3Store;
import cn.interview.v3.model.V3ModelGateway;
import cn.interview.v3.validation.V3OutputValidator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 编排本场准备、检索、限时追问与终审；网络等待不占用存储锁。 */
@Service
@ConditionalOnProperty(name = "app.v3.enabled", havingValue = "true")
public class V3InterviewService implements ProjectEvidenceSource {
  private final V3Store store;
  private final ObjectMapper json;
  private final MaterialService materials;
  private final V3ModelGateway model;
  private final KnowledgeService knowledge;
  private final V3OutputValidator validator;
  private final Environment env;
  private final long fastDeadline;
  private final Clock clock;
  private final V3InterviewProgress progress;
  private final ProjectEvidenceService evidence;
  private final ExecutorService slow =
      new ThreadPoolExecutor(
          1,
          1,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          new ThreadPoolExecutor.AbortPolicy());
  private final ExecutorService fast =
      new ThreadPoolExecutor(
          2,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
  private final Map<String, Future<?>> jobs = new ConcurrentHashMap<>();
  private static final Set<String> BUSY = Set.of("QUEUED", "PREPARING", "ANSWERING", "REPORTING");
  private static final Map<String, Integer> TOKENS =
      Map.of("ANALYZE", 2500, "PLAN", 6500, "FOLLOWUP", 800, "REVIEW", 6500);

  public V3InterviewService(
      V3Store store,
      ObjectMapper json,
      MaterialService materials,
      V3ModelGateway model,
      KnowledgeService knowledge,
      V3OutputValidator validator,
      Environment env,
      @Value("${app.v3.fast-deadline-ms:7000}") long fastDeadline) {
    this(store, json, materials, model, knowledge, validator, env, fastDeadline, Clock.systemUTC());
  }

  @Autowired
  public V3InterviewService(V3Store store, ObjectMapper json, MaterialService materials,
      V3ModelGateway model, KnowledgeService knowledge, V3OutputValidator validator, Environment env,
      @Value("${app.v3.fast-deadline-ms:7000}") long fastDeadline, ObjectProvider<Clock> clocks) {
    this(store, json, materials, model, knowledge, validator, env, fastDeadline, clocks.getIfAvailable(Clock::systemUTC));
  }

  private V3InterviewService(V3Store store, ObjectMapper json, MaterialService materials,
      V3ModelGateway model, KnowledgeService knowledge, V3OutputValidator validator, Environment env,
      long fastDeadline, Clock clock) {
    this.clock = clock;
    this.progress = new V3InterviewProgress(json, clock);
    this.evidence = new ProjectEvidenceService(json);
    this.store = store;
    this.json = json;
    this.materials = materials;
    this.model = model;
    this.knowledge = knowledge;
    this.validator = validator;
    this.env = env;
    this.fastDeadline = Math.max(100, Math.min(fastDeadline, 7000));
  }

  public Map<String, Object> health() {
    String provider = env.getProperty("app.llm.provider", "openai");
    return Map.of(
        "enabled",
        true,
        "provider",
        provider,
        "configured",
        provider.equals("mock") || !env.getProperty("app.llm.api-key", "").isBlank(),
        "model",
        env.getProperty("app.llm.model", ""));
  }

  private String now() {
    return clock.instant().toString();
  }

  private String text(JsonNode b, String field, int max, boolean required) {
    var n = b.path(field);
    if (n.isMissingNode() || n.isNull()) {
      if (required) throw ApiException.bad("请填写" + field);
      return "";
    }
    if (!n.isTextual() || n.asText().length() > max) throw ApiException.bad("字段格式或长度无效：" + field);
    String s = n.asText().strip();
    if (required && s.isBlank()) throw ApiException.bad("请填写" + field);
    return s;
  }

  private String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void save(ObjectNode s) {
    s.put("revision", s.path("revision").asInt() + 1).put("updatedAt", now());
    store.save(s);
  }

  public ObjectNode get(String id) {
    return publicView(store.get(id));
  }

  public ArrayNode list() {
    var out = json.createArrayNode();
    for (var s : store.list()) {
      var row = json.createObjectNode();
      for (String k :
          List.of(
              "id",
              "status",
              "phase",
              "role",
              "level",
              "createdAt",
              "updatedAt",
              "mock",
              "resumeId",
              "jdId")) row.set(k, s.path(k));
      out.add(row);
    }
    return out;
  }

  private ObjectNode publicView(ObjectNode s) {
    var out = s.deepCopy();
    var actions = out.putArray("allowedActions");
    String state = s.path("status").asText(), pending = s.path("pendingStage").asText();
    if (state.equals("READY")) {
      actions.add("confirm").add("corrections").add("refresh");
    }
    if (state.equals("IN_PROGRESS")) {
      actions.add("answers").add("finish");
      if (V3InterviewProgress.enabled(s) && s.path("interviewMode").asText().equals("TIMED") && !progress.expired(s))
        actions.add("transcription-wait").add("transcription-resume");
    }
    if (state.equals("COMPLETED")) actions.add("finish");
    if (Set.of("FAILED", "INTERRUPTED").contains(state)) {
      if (!s.path("budgetReached").asBoolean()) actions.add("retry");
      if (pending.equals("FOLLOWUP")) {
        actions.add("next").add("finish");
      }
      if (s.path("messages").isEmpty()) actions.add("corrections").add("refresh");
    }
    if (!Set.of("REPORT_READY", "CANCELLED").contains(state)) actions.add("cancel");
    if (V3InterviewProgress.enabled(s)) {
      out.set("timing", progress.timing(s));
      out.set("stages", progress.stages(s));
    }
    out.remove("timingState");
    out.remove(
        List.of(
            "input",
            "questions",
            "requests",
            "createHash",
            "createRequestId",
            "generation",
            "pendingStage",
            "questionIndex",
            "followUpCount",
            "backupsUsed",
            "currentQuestion",
            "answerSubmittedAt",
            "forceRefresh"));
    return out;
  }

  private record MaterialSnapshot(String text, boolean truncated, String name, String updatedAt) {}

  private MaterialSnapshot snapshot(String id, String kind) {
    if (id.isBlank()) return new MaterialSnapshot("", false, "", "");
    Material found =
        materials.list(kind).stream()
            .filter(m -> m.id().equals(id))
            .findFirst()
            .orElseThrow(() -> ApiException.bad("所选资料不存在或类型不匹配"));
    if (found.content().isBlank() && found.documents().stream().allMatch(d -> d.text().isBlank()))
      throw ApiException.bad("所选资料没有有效内容");
    var context = materials.context(found, "");
    return new MaterialSnapshot(
        context.text(), context.truncated(), found.name(), found.updatedAt());
  }

  private void configure(ObjectNode s, JsonNode b) {
    String role = text(b, "role", 120, true), level = text(b, "level", 20, true);
    if (!Set.of("INTERN", "JUNIOR", "SENIOR").contains(level)) throw ApiException.bad("请选择有效职级");
    String resumeId = text(b, "resumeId", 100, false),
        jdId = text(b, "jdId", 100, false),
        jd = text(b, "jd", 10000, false);
    var resume = snapshot(resumeId, "RESUME");
    var jdSnapshot = snapshot(jdId, "JD");
    if (!jdId.isBlank()) jd = jdSnapshot.text();
    s.put("contextTruncated", resume.truncated() || jdSnapshot.truncated());
    int duration = b.path("durationMinutes").asInt(10);
    if (!Set.of(5, 10, 20).contains(duration)) throw ApiException.bad("目标时长请选择5、10或20分钟");
    s.put("role", role)
        .put("level", level)
        .put("resumeId", resumeId)
        .put("jdId", jdId)
        .put("durationMinutes", duration);
    var input =
        json.createObjectNode()
            .put("role", role)
            .put("level", level)
            .put("resume", resume.text())
            .put("jd", jd)
            .put("correction", s.path("input").path("correction").asText())
            .put("totalQuestions", duration == 5 ? 3 : duration == 20 ? 8 : 5);
    var snapshots = input.putObject("materialSnapshots");
    snapshots
        .putObject("resume")
        .put("id", resumeId)
        .put("name", resume.name())
        .put("updatedAt", resume.updatedAt())
        .put("sha256", hash(resume.text()))
        .put("truncated", resume.truncated());
    snapshots
        .putObject("jd")
        .put("id", jdId)
        .put("name", jdSnapshot.name())
        .put("updatedAt", jdSnapshot.updatedAt())
        .put("sha256", hash(jd))
        .put("truncated", jdSnapshot.truncated());
    if (V3InterviewProgress.enabled(s)) configureExperience(s, b, input);
    input.put("snapshotHash", hash(input.toString()));
    s.set("input", input);
  }

  private void configureExperience(ObjectNode s, JsonNode b, ObjectNode input) {
    String mode = b.has("interviewMode") ? text(b, "interviewMode", 20, true) : "TRAINING";
    if (!Set.of("TRAINING", "TIMED").contains(mode)) throw ApiException.bad("请选择训练或限时实战");
    int minutes = b.path("timeLimitMinutes").asInt(30);
    if ((b.has("timeLimitMinutes") && !b.path("timeLimitMinutes").isIntegralNumber())
        || !Set.of(20, 30, 45).contains(minutes)) throw ApiException.bad("实战时限请选择20、30或45分钟");
    String projectId = text(b, "projectId", 100, false), brief = text(b, "projectBrief", 6000, false);
    var project = snapshot(projectId, "PROJECT");
    String content = projectId.isBlank() ? brief : project.text() + (brief.isBlank() ? "" : "\n本场补充：" + brief);
    var main = json.createObjectNode().put("id", projectId.isBlank() ? "brief:" + s.path("id").asText() : projectId)
        .put("name", projectId.isBlank() ? "本场主项目" : project.name())
        .put("brief", content.substring(0, Math.min(content.length(), 6000)))
        .put("hypothetical", content.isBlank());
    if (content.isBlank()) main.put("name", "假设场景，尚未提供真实项目");
    s.put("interviewMode", mode).put("timeLimitMinutes", minutes).put("projectId", projectId).put("projectBrief", brief);
    s.set("mainProject", main);
    s.put("contextTruncated", s.path("contextTruncated").asBoolean() || project.truncated());
    input.put("experienceVersion", 1).put("project", content)
        .put("totalQuestions", mode.equals("TIMED") ? 8 : Math.max(4, input.path("totalQuestions").asInt()));
    input.set("mainProject", main.deepCopy());
    input.putArray("stageOrder").add("INTRO").add("PROJECT").add("TECHNICAL").add("QUESTIONS");
    var snapshot = ((ObjectNode) input.path("materialSnapshots")).putObject("project");
    snapshot.put("id", projectId).put("name", main.path("name").asText()).put("sha256", hash(content))
        .put("updatedAt", project.updatedAt()).put("truncated", project.truncated());
  }

  private void adoptResumeProject(ObjectNode s, ObjectNode input, JsonNode analysis) {
    if (!V3InterviewProgress.enabled(s) || !s.path("mainProject").path("hypothetical").asBoolean()) return;
    JsonNode candidate = analysis.path("mainProject");
    if (!candidate.isObject() || !candidate.path("brief").isTextual() || candidate.path("brief").asText().isBlank()) return;
    String brief = candidate.path("brief").asText(), resume = input.path("resume").asText();
    if (brief.length() > 6000 || !resume.contains(brief)) throw V3OutputValidator.invalid();
    String name = candidate.path("name").asText("简历中的主项目");
    if (name.isBlank() || name.length() > 120) throw V3OutputValidator.invalid();
    if (!resume.contains(name)) name = "简历中的主项目";
    var project = json.createObjectNode().put("id", "resume:" + s.path("resumeId").asText())
        .put("name", name).put("brief", brief).put("hypothetical", false).put("fromResume", true);
    s.set("mainProject", project); input.set("mainProject", project.deepCopy()); input.put("project", brief);
    snapshotSelectedProject(input, project, brief);
  }

  private void adoptConfirmationProject(ObjectNode s, JsonNode body) {
    if (!body.has("projectBrief")) return;
    String brief = text(body, "projectBrief", 6000, true);
    var main = json.createObjectNode().put("id", "brief:" + s.path("id").asText()).put("name", "本场主项目")
        .put("brief", brief).put("hypothetical", false);
    s.set("mainProject", main); s.put("projectBrief", brief).put("projectId", "");
    var input = (ObjectNode) s.path("input"); input.set("mainProject", main.deepCopy()); input.put("project", brief);
    snapshotSelectedProject(input, main, brief);
    // 开考前补述不额外调用模型。仅保留一道与原文绑定的项目主问题，旧假设场景题不混入新项目。
    var reusable = json.createArrayNode(); boolean projectKept = false;
    for (var question : s.path("questions")) {
      if (question.path("stage").asText().equals("PROJECT")) {
        if (projectKept) continue;
        projectKept = true;
        var q = (ObjectNode) question;
        q.put("question", "围绕你本场补充的真实项目（" + brief.substring(0, Math.min(brief.length(), 180))
            + "），请先说明业务背景、你实际负责的部分，以及请求经过的关键链路？");
        q.put("evidence", brief.substring(0, Math.min(brief.length(), 1000))).putArray("sourceIds");
        q.putArray("backups").addObject().put("question", "刚才这条链路中，你本人处理过什么异常？请说明触发条件与验证结果。")
            .putArray("sourceIds");
      }
      reusable.add(question);
    }
    s.set("questions", reusable);
    appendWarning(s, "临开考补充了主项目，本场项目题已收敛为澄清与定向追问；其他技术题沿用已准备内容。");
  }

  private void snapshotSelectedProject(ObjectNode input, JsonNode project, String brief) {
    ((ObjectNode) input.path("materialSnapshots")).putObject("project")
        .put("id", project.path("id").asText()).put("name", project.path("name").asText())
        .put("sha256", hash(brief)).put("updatedAt", now()).put("truncated", false);
    input.remove("snapshotHash"); input.put("snapshotHash", hash(input.toString()));
  }

  @Override public boolean supports(String kind) { return "V3".equals(kind); }
  @Override public ObjectNode source(String id) {
    var s = store.get(id);
    if (!V3InterviewProgress.enabled(s) || !s.path("report").path("matrix").isObject())
      throw ApiException.conflict("本场没有可补练的项目矩阵");
    s.set("matrix", s.path("report").path("matrix").deepCopy());
    s.put("mode", "PROJECT"); return s;
  }

  public synchronized ObjectNode create(JsonNode body) {
    if (!body.isObject()) throw ApiException.bad("请求应为JSON对象");
    String requestId = text(body, "requestId", 100, true), digest = hash(body.toString());
    for (var s : store.list())
      if (s.path("createRequestId").asText().equals(requestId)) {
        if (!s.path("createHash").asText().equals(digest))
          throw ApiException.conflict("同一请求标识不能对应不同配置");
        return publicView(s);
      }
    var s =
        json.createObjectNode()
            .put("id", UUID.randomUUID().toString())
            .put("createdAt", now())
            .put("createRequestId", requestId)
            .put("createHash", digest)
            .put("generation", 1)
            .put("revision", 0)
            .put("experienceVersion", 1)
            .put("mock", env.getProperty("app.llm.provider", "openai").equals("mock"));
    configure(s, body);
    s.put("status", "QUEUED").put("phase", "已进入准备队列").put("pendingStage", "PREPARE");
    s.putObject("summary");
    s.putArray("messages");
    s.putArray("sources");
    s.putArray("questions");
    s.putArray("warnings");
    s.putObject("requests");
    s.putObject("usage").put("calls", 0).put("declaredOutputLimit", 0).put("cost", "未知");
    s.putObject("metrics");
    s.putArray("backupsUsed");
    s.put("questionIndex", 0).put("followUpCount", 0);
    save(s);
    enqueuePrepare(s, false);
    return publicView(s);
  }

  private void enqueuePrepare(ObjectNode s, boolean refresh) {
    String id = s.path("id").asText();
    int generation = s.path("generation").asInt();
    try {
      jobs.put(id, slow.submit(() -> prepare(id, generation, refresh)));
      deadlines.schedule(() -> timeoutSlow(id, generation, "PREPARE"), 300, TimeUnit.SECONDS);
    } catch (RejectedExecutionException e) {
      fail(id, generation, "准备任务已满，请稍后显式重试");
    }
  }

  private synchronized ObjectNode phase(String id, int generation, String phase, String state) {
    var s = store.get(id);
    if (s.path("generation").asInt() != generation || !BUSY.contains(s.path("status").asText()))
      throw new CancellationException();
    s.put("status", state).put("phase", phase);
    save(s);
    return s;
  }

  private ObjectNode invoke(
      String id, int generation, String stage, ObjectNode input, int timeout) {
    synchronized (this) {
      var s = store.get(id);
      if (s.path("generation").asInt() != generation || !BUSY.contains(s.path("status").asText()))
        throw new CancellationException();
      var usage = (ObjectNode) s.path("usage");
      int calls = usage.path("calls").asInt(), tokens = usage.path("declaredOutputLimit").asInt();
      if (calls >= 32 || tokens + TOKENS.get(stage) > 100000) {
        s.put("budgetReached", true);
        save(s);
        throw new ApiException(429, "V3_BUDGET_REACHED", "本场模型调用预算已用完，请保留结果并结束本场");
      }
      usage.put("calls", calls + 1).put("declaredOutputLimit", tokens + TOKENS.get(stage));
      save(s);
    }
    var bounded = V3InputBudget.bound(input, json);
    if (bounded.path("inputTruncated").asBoolean())
      synchronized (this) {
        var s = store.get(id);
        if (s.path("generation").asInt() == generation) {
          appendWarning(s, "本场部分模型输入按预算节选，原始资料与已提交回答仍完整保存；涉及未覆盖细节的结论需核对原文");
          save(s);
        }
      }
    var result = model.call(stage, bounded, timeout);
    var usage = result.remove("_modelUsage");
    synchronized (this) {
      var s = store.get(id);
      if (s.path("generation").asInt() == generation && usage != null && usage.isObject()) {
        var u = (ObjectNode) s.path("usage");
        for (String field : List.of("promptTokens", "completionTokens")) {
          if (usage.path(field).isIntegralNumber())
            u.put(field, u.path(field).asLong() + usage.path(field).asLong());
          else u.put("usageIncomplete", true);
        }
        save(s);
      }
    }
    return result;
  }

  private void prepare(String id, int generation, boolean refresh) {
    try {
      var s = phase(id, generation, "正在深入分析资料与岗位", "PREPARING");
      var input = ((ObjectNode) s.path("input")).deepCopy();
      var rawAnalysis = invoke(id, generation, "ANALYZE", input, 180);
      var analysis = validator.analysis(rawAnalysis);
      if (V3InterviewProgress.enabled(s)) adoptResumeProject(s, input, rawAnalysis);
      phase(id, generation, "正在获取本场公开技术依据", "PREPARING");
      var topics = new ArrayList<String>();
      analysis.path("topics").forEach(t -> topics.add(t.asText()));
      if (topics.isEmpty())
        topics.add(
            input.path("role").asText().toLowerCase().matches(".*(agent|大模型).*")
                ? "AI_RAG"
                : "JAVA_CORE");
      var sources = knowledge.retrieve(topics, refresh);
      var retrievalWarnings = knowledge.warnings();
      input.set("summary", analysis.path("summary"));
      var selected = knowledge.select(sources, analysis.path("summary").toString(), 6);
      input.set("sources", selected);
      phase(id, generation, "正在结合经历与岗位准备问题", "PREPARING");
      var rawPlan = invoke(id, generation, "PLAN", input, 180);
      var plan =
          validator.questions(
              rawPlan,
              sources,
              input.path("resume").asText(),
              input.path("jd").asText() + (V3InterviewProgress.enabled(s) ? "\n" + input.path("project").asText() : ""),
              input.path("totalQuestions").asInt());
      if (V3InterviewProgress.enabled(s)) plan = validator.stageQuestions(rawPlan, plan, input);
      synchronized (this) {
        s = store.get(id);
        if (s.path("generation").asInt() != generation
            || !s.path("status").asText().equals("PREPARING")) return;
        if (V3InterviewProgress.enabled(s)) {
          s.set("mainProject", input.path("mainProject").deepCopy());
          s.set("input", input);
        }
        s.set("summary", analysis.path("summary"));
        s.set("sources", knowledge.mergeSources(sources, selected));
        s.set("questions", plan);
        s.set("topics", analysis.path("topics"));
        s.put("status", "READY").put("phase", "准备完成，请核对摘要").put("error", "");
        var warnings = ((ArrayNode) s.path("warnings")).deepCopy();
        retrievalWarnings.forEach(warnings::add);
        if (s.path("contextTruncated").asBoolean())
          warnings.add("所选资料超出单场读取范围，已使用节选；未覆盖内容不能视为个人能力缺失");
        if (input.path("resume").asText().isBlank() && input.path("jd").asText().isBlank()
            && !evidence.applicable(input.path("mainProject")))
          warnings.add("本场为无资料的通用基准摸底，不代表已针对个人经历分析");
        if (selected.isEmpty()) warnings.add("未取得可用公开技术依据，本场技术判断需进一步核实");
        for (var source : sources)
          if (source.path("stale").asBoolean()) warnings.add("部分知识来源已过期且刷新失败，复盘不能据此直接判错");
        s.set("warnings", warnings);
        s.put("phase", "准备完成，请核对摘要");
        save(s);
      }
    } catch (CancellationException ignored) {
    } catch (Exception e) {
      fail(id, generation, message(e));
    }
  }

  private void appendWarning(ObjectNode s, String warning) {
    var warnings = (ArrayNode) s.path("warnings");
    for (var existing : warnings) if (existing.asText().equals(warning)) return;
    warnings.add(warning);
  }

  private String message(Exception e) {
    return e instanceof ApiException ? e.getMessage() : "处理未完成，输入与已提交回答已保存；请显式重试";
  }

  private synchronized void fail(String id, int generation, String error) {
    var s = store.get(id);
    if (s.path("generation").asInt() != generation || !BUSY.contains(s.path("status").asText()))
      return;
    progress.stop(s);
    s.put("status", "FAILED")
        .put("phase", s.path("budgetReached").asBoolean() ? "本场模型预算已用完" : "处理失败，可显式重试")
        .put("error", error)
        .put("generation", generation + 1);
    save(s);
  }

  private synchronized void timeoutSlow(String id, int generation, String pending) {
    var s = store.get(id);
    if (s.path("generation").asInt() == generation
        && s.path("pendingStage").asText().equals(pending)
        && BUSY.contains(s.path("status").asText())) {
      fail(id, generation, "阶段处理超时，资料与进度已保存；可显式重试");
      var job = jobs.get(id);
      if (job != null) job.cancel(true);
    }
  }

  public synchronized ObjectNode action(String id, String action, JsonNode body) {
    if (!Set.of("corrections", "confirm", "answers", "next", "finish", "retry", "cancel", "refresh", "transcription-wait", "transcription-resume")
        .contains(action)) throw ApiException.missing("操作不存在");
    String requestId = text(body, "requestId", 100, true);
    var s = store.get(id);
    String digest = hash(action + body.toString());
    var requests = (ObjectNode) s.path("requests");
    if (requests.has(requestId)) {
      if (!requests.path(requestId).asText().equals(digest))
        throw ApiException.conflict("同一请求标识不能提交不同内容");
      return publicView(s);
    }
    if (!body.path("expectedRevision").isIntegralNumber()
        || s.path("revision").asInt() != body.path("expectedRevision").asInt())
      throw ApiException.conflict("本场状态已变化，请刷新后继续");
    if (requests.size() >= 256) throw ApiException.conflict("本场操作过多，请结束后建立新面试");
    String state = s.path("status").asText();
    Runnable start = () -> {};
    switch (action) {
      case "corrections", "refresh" -> {
        require(
            s.path("messages").isEmpty()
                && Set.of("READY", "FAILED", "INTERRUPTED").contains(state),
            "只能在开考前调整准备内容");
        if (action.equals("corrections")) {
          boolean configChanged =
              body.has("role")
                  || body.has("level")
                  || body.has("resumeId")
                  || body.has("jdId")
                  || body.has("jd")
                  || body.has("durationMinutes") || body.has("projectId") || body.has("projectBrief")
                  || body.has("interviewMode") || body.has("timeLimitMinutes");
          if (configChanged) {
            var config = json.createObjectNode();
            for (String k : List.of("role", "level", "resumeId", "jdId", "durationMinutes", "projectId", "projectBrief", "interviewMode", "timeLimitMinutes"))
              config.set(k, s.path(k));
            config.put("jd", s.path("input").path("jd").asText());
            for (String k : List.of("role", "level", "resumeId", "jdId", "jd", "durationMinutes", "projectId", "projectBrief", "interviewMode", "timeLimitMinutes"))
              if (body.has(k)) config.set(k, body.get(k));
            if (body.has("jdId")
                && body.path("jdId").asText().isBlank()
                && !s.path("jdId").asText().isBlank()
                && !body.has("jd")) config.put("jd", "");
            configure(s, config);
          }
          String correction = text(body, "correction", 5000, !configChanged),
              previous = s.path("input").path("correction").asText();
          if (!correction.isBlank()) {
            String combined = previous.isBlank() ? correction : previous + "\n本次补充更正：" + correction;
            if (combined.length() > 12000) throw ApiException.bad("本场累计更正较长，请建立新场次以保留清晰的资料范围");
            ((ObjectNode) s.path("input")).put("correction", combined);
          }
          var revised = (ObjectNode) s.path("input");
          revised.remove("snapshotHash");
          revised.put("snapshotHash", hash(revised.toString()));
        }
        s.put("generation", s.path("generation").asInt() + 1)
            .put("status", "QUEUED")
            .put("phase", "已进入重新准备队列")
            .put("pendingStage", "PREPARE")
            .put("error", "");
        s.putArray("sources");
        s.putArray("questions");
        boolean refresh = action.equals("refresh");
        start = () -> enqueuePrepare(s, refresh);
      }
      case "confirm" -> {
        require(state.equals("READY"), "请等待准备完成后确认开考");
        s.put("questionIndex", 0)
            .put("followUpCount", 0)
            .put("status", "IN_PROGRESS")
            .put("phase", "面试进行中");
        if (V3InterviewProgress.enabled(s)) {
          adoptConfirmationProject(s, body);
          progress.start(s);
          s.put("stage", s.path("questions").get(0).path("stage").asText());
        }
        ask(
            s,
            s.path("questions").get(0).path("question").asText(),
            s.path("questions").get(0).path("sourceIds"),
            false);
      }
      case "transcription-wait", "transcription-resume" -> {
        progress.recognition(s, action.equals("transcription-wait"), text(body, "expectedQuestionId", 100, true));
      }
      case "answers" -> {
        require(state.equals("IN_PROGRESS"), "当前不能提交回答，请读取最新状态");
        String expected = text(body, "expectedQuestionId", 100, true);
        if (!expected.equals(s.path("currentQuestion").path("questionId").asText()))
          throw ApiException.conflict("问题已变化，未提交旧问题的回答");
        String answer = text(body, "content", 12000, true);
        progress.stop(s);
        var m =
            ((ArrayNode) s.path("messages"))
                .addObject()
                .put("id", UUID.randomUUID().toString())
                .put("role", "USER")
                .put("content", answer)
                .put("questionId", expected)
                .put("createdAt", now());
        if (V3InterviewProgress.enabled(s)) m.put("stage", s.path("stage").asText());
        if (progress.expired(s)) {
          if (s.path("stage").asText().equals("QUESTIONS")) reply(s, "时间已到，你的反问已保存；未额外调用模型生成回应，具体公司情况请向招聘方确认。本场面试已结束。");
          completeQuestions(s, "时间已到，最后一答已保存，可以生成复盘");
          break;
        }
        s.put("status", "ANSWERING")
            .put("phase", "面试官思考中")
            .put("pendingStage", "FOLLOWUP")
            .put("answerSubmittedAt", System.currentTimeMillis())
            .put("error", "");
        start = () -> enqueueFollowup(s);
      }
      case "next" -> {
        require(
            state.equals("INTERRUPTED") && s.path("pendingStage").asText().equals("FOLLOWUP"),
            "当前无需跳题");
        s.put("generation", s.path("generation").asInt() + 1);
        next(s);
      }
      case "finish" -> {
        require(Set.of("IN_PROGRESS", "INTERRUPTED", "COMPLETED").contains(state), "当前不能开始复盘");
        progress.stop(s);
        s.put("generation", s.path("generation").asInt() + 1)
            .put("status", "REPORTING")
            .put("phase", "正在准备终审核实")
            .put("pendingStage", "REVIEW")
            .put("error", "");
        start = () -> enqueueReview(s);
      }
      case "retry" -> {
        require(Set.of("FAILED", "INTERRUPTED").contains(state), "只有失败或中断任务可以重试");
        s.put("generation", s.path("generation").asInt() + 1).put("error", "");
        switch (s.path("pendingStage").asText()) {
          case "PREPARE" -> {
            s.put("status", "QUEUED").put("phase", "已进入准备队列");
            start = () -> enqueuePrepare(s, false);
          }
          case "FOLLOWUP" -> {
            s.put("status", "ANSWERING")
                .put("phase", "面试官思考中")
                .put("answerSubmittedAt", System.currentTimeMillis());
            start = () -> enqueueFollowup(s);
          }
          case "REVIEW" -> {
            s.put("status", "REPORTING").put("phase", "正在准备终审核实");
            start = () -> enqueueReview(s);
          }
          default -> throw ApiException.conflict("当前没有可恢复任务");
        }
      }
      case "cancel" -> {
        progress.stop(s);
        require(!Set.of("REPORT_READY", "CANCELLED").contains(state), "本场已结束");
        s.put("generation", s.path("generation").asInt() + 1)
            .put("status", "CANCELLED")
            .put("phase", "已取消，已提交内容保留");
        var job = jobs.get(id);
        if (job != null) job.cancel(true);
      }
    }
    requests.put(requestId, digest);
    save(s);
    ObjectNode result = publicView(s);
    start.run();
    return result;
  }

  private void require(boolean allowed, String message) {
    if (!allowed) throw ApiException.conflict(message);
  }

  private void ask(ObjectNode s, String question, JsonNode sourceIds, boolean fallback) {
    String qid = UUID.randomUUID().toString();
    var m =
        ((ArrayNode) s.path("messages"))
            .addObject()
            .put("id", UUID.randomUUID().toString())
            .put("role", "ASSISTANT")
            .put("content", question)
            .put("questionId", qid)
            .put("createdAt", now())
            .put("fallback", fallback);
    m.set("sourceIds", sourceIds.deepCopy());
    if (V3InterviewProgress.enabled(s)) m.put("stage", s.path("stage").asText());
    s.set("currentQuestion", m.deepCopy());
    progress.resume(s);
  }

  private void next(ObjectNode s) {
    if (progress.expired(s)) { completeQuestions(s, "时间已到，本场已收尾，可以生成复盘"); return; }
    int index = V3InterviewProgress.enabled(s) ? progress.nextIndex(s) : s.path("questionIndex").asInt() + 1;
    s.put("questionIndex", index).put("followUpCount", 0).put("error", "");
    if (index >= s.path("questions").size()) {
      completeQuestions(s, "本场问题已完成，可以生成复盘");
    } else {
      var q = s.path("questions").get(index);
      if (V3InterviewProgress.enabled(s)) s.put("stage", q.path("stage").asText());
      ask(s, q.path("question").asText(), q.path("sourceIds"), false);
      s.put("status", "IN_PROGRESS").put("phase", "面试进行中");
    }
  }

  private void reply(ObjectNode s, String content) {
    ((ArrayNode) s.path("messages")).addObject().put("id", UUID.randomUUID().toString())
        .put("role", "ASSISTANT").put("kind", "REPLY").put("content", content)
        .put("stage", "QUESTIONS").put("createdAt", now());
  }

  private void completeQuestions(ObjectNode s, String phase) {
    progress.stop(s);
    s.put("status", "COMPLETED").put("phase", phase);
    if (V3InterviewProgress.enabled(s)) s.put("endedAt", now());
  }

  private void enqueueFollowup(ObjectNode s) {
    String id = s.path("id").asText();
    int generation = s.path("generation").asInt();
    try {
      jobs.put(id, fast.submit(() -> followup(id, generation)));
      deadlines.schedule(() -> fallback(id, generation), fastDeadline, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      fallback(id, generation);
    }
  }

  private void followup(String id, int generation) {
    try {
      var s = store.get(id);
      var input = json.createObjectNode();
      for (String k :
          List.of("role", "level", "summary", "messages", "followUpCount", "currentQuestion"))
        input.set(k, s.path(k));
      input.put("jd", s.path("input").path("jd").asText());
      if (V3InterviewProgress.enabled(s)) {
        input.put("experienceVersion", 1).put("stage", s.path("stage").asText())
            .put("reduceFollowups", progress.narrowFollowups(s));
        input.set("mainProject", s.path("mainProject"));
      }
      input.put(
          "answer", s.path("messages").get(s.path("messages").size() - 1).path("content").asText());
      var sources =
          knowledge.select(
              s.path("sources"),
              s.path("currentQuestion").path("content").asText()
                  + " "
                  + input.path("answer").asText(),
              3);
      input.set("sources", sources);
      var decision =
          validator.followup(
              invoke(
                  id, generation, "FOLLOWUP", input, Math.max(1, (int) (fastDeadline / 1000) - 1)),
              sources);
      synchronized (this) {
        s = store.get(id);
        if (s.path("generation").asInt() != generation
            || !s.path("status").asText().equals("ANSWERING")) return;
        s.set("sources", knowledge.mergeSources(s.path("sources"), sources));
        if (decision.path("action").asText().equals("FOLLOW_UP")
            && s.path("followUpCount").asInt() < 2
            && !progress.expired(s)
            && !(V3InterviewProgress.enabled(s) && (progress.narrowFollowups(s) || s.path("stage").asText().equals("QUESTIONS")))) {
          ask(s, decision.path("question").asText(), decision.path("sourceIds"), false);
          s.put("followUpCount", s.path("followUpCount").asInt() + 1)
              .put("status", "IN_PROGRESS")
              .put("phase", "面试进行中");
        } else {
          if (V3InterviewProgress.enabled(s) && s.path("stage").asText().equals("QUESTIONS"))
            reply(s, decision.path("reply").asText("感谢你的提问。本场面试已结束；具体公司的内部信息需要向招聘方确认。"));
          next(s);
        }
        s.put("generation", generation + 1);
        ((ObjectNode) s.path("metrics"))
            .put(
                "lastFollowupMs", System.currentTimeMillis() - s.path("answerSubmittedAt").asLong())
            .put("lastFallback", false);
        save(s);
      }
    } catch (CancellationException ignored) {
    } catch (ApiException e) {
      if (e.code.equals("V3_BUDGET_REACHED")) budgetStop(id, generation);
      else fallback(id, generation);
    } catch (Exception e) {
      fallback(id, generation);
    }
  }

  private synchronized void budgetStop(String id, int generation) {
    var s = store.get(id);
    if (s.path("generation").asInt() != generation
        || !s.path("status").asText().equals("ANSWERING")) return;
    s.put("budgetReached", true)
        .put("generation", generation + 1)
        .put("status", "INTERRUPTED")
        .put("phase", "本场模型预算已用完")
        .put("error", "本场模型调用已达上限，回答已保存；可浏览已有问题或保留本场记录");
    save(s);
  }

  private synchronized void fallback(String id, int generation) {
    var s = store.get(id);
    if (s.path("generation").asInt() != generation
        || !s.path("status").asText().equals("ANSWERING")) return;
    if (V3InterviewProgress.enabled(s) && s.path("stage").asText().equals("QUESTIONS")) {
      s.put("generation", generation + 1);
      reply(s, "本次未及时取得对反问的回应，你的问题已保留；具体公司的内部信息需要向招聘方确认。本场面试已结束。");
      completeQuestions(s, "本场问题已完成，可以生成复盘"); save(s); return;
    }
    var q = s.path("questions").get(s.path("questionIndex").asInt());
    JsonNode selected = null;
    if (V3InterviewProgress.enabled(s) && (progress.expired(s) || progress.narrowFollowups(s))) {
      s.put("generation", generation + 1); next(s); save(s); return;
    }
    if (s.path("followUpCount").asInt() < 2)
      for (var b : q.path("backups")) {
        boolean seen = false;
        for (var m : s.path("messages"))
          if (m.path("role").asText().equals("ASSISTANT")
              && m.path("content").asText().equals(b.path("question").asText())) seen = true;
        if (!seen) {
          selected = b;
          break;
        }
      }
    s.put("generation", generation + 1);
    if (selected != null) {
      ask(s, selected.path("question").asText(), selected.path("sourceIds"), true);
      s.put("followUpCount", s.path("followUpCount").asInt() + 1)
          .put("status", "IN_PROGRESS")
          .put("phase", "已切换本场相关备用问题");
      ((ArrayNode) s.path("warnings")).add("本轮未及时取得有效即时追问，已切换本场已校验备用问题");
    } else {
      s.put("status", "INTERRUPTED")
          .put("phase", "生成超时，回答已保存")
          .put("error", "本轮没有合适备用问题，请重试或进入下一题");
    }
    ((ObjectNode) s.path("metrics"))
        .put("lastFollowupMs", System.currentTimeMillis() - s.path("answerSubmittedAt").asLong())
        .put("lastFallback", true);
    save(s);
  }

  private void enqueueReview(ObjectNode s) {
    String id = s.path("id").asText();
    int generation = s.path("generation").asInt();
    try {
      jobs.put(id, slow.submit(() -> review(id, generation)));
      deadlines.schedule(() -> timeoutSlow(id, generation, "REVIEW"), 180, TimeUnit.SECONDS);
    } catch (RejectedExecutionException e) {
      fail(id, generation, "复盘任务队列已满，回答已保存；请稍后重试");
    }
  }

  private void review(String id, int generation) {
    try {
      var s = phase(id, generation, "正在终审核实公开技术依据", "REPORTING");
      var topics = new ArrayList<String>();
      s.path("topics").forEach(t -> topics.add(t.asText()));
      var refreshed = knowledge.retrieve(topics, true);
      var retrievalWarnings = knowledge.warnings();
      var sources = knowledge.mergeSources(s.path("sources"), refreshed);
      var selected =
          knowledge.select(
              sources, s.path("summary").toString() + " " + s.path("messages").toString(), 6);
      sources = knowledge.mergeSources(sources, selected);
      phase(id, generation, "正在结合原始回答形成复盘", "REPORTING");
      var input = json.createObjectNode();
      for (String k : List.of("role", "level", "summary", "messages")) input.set(k, s.path(k));
      if (V3InterviewProgress.enabled(s)) { input.put("experienceVersion", 1); input.set("mainProject", s.path("mainProject")); }
      input.set("sources", selected);
      input
          .put("resume", s.path("input").path("resume").asText())
          .put("jd", s.path("input").path("jd").asText());
      boolean answered = false;
      for (var m : s.path("messages")) if (m.path("role").asText().equals("USER")) answered = true;
      ObjectNode rawReport = answered ? invoke(id, generation, "REVIEW", input, 150) : null;
      ObjectNode report = answered ? validator.report(rawReport, s.path("messages"), selected)
          : json.createObjectNode().put("summary", "本场尚无已提交回答，样本不足，无法评价能力");
      if (!answered) report.putArray("findings");
      if (V3InterviewProgress.enabled(s)) {
        var matrix = answered ? evidence.normalize(rawReport.path("matrix"), s.path("messages"), s.path("mainProject"))
            : evidence.uncovered(s.path("mainProject"));
        if (matrix != null) report.set("matrix", matrix);
        report.set("stages", progress.stages(s));
      }
      synchronized (this) {
        s = store.get(id);
        if (s.path("generation").asInt() != generation
            || !s.path("status").asText().equals("REPORTING")) return;
        for (String warning : retrievalWarnings) appendWarning(s, warning);
        s.set("sources", sources);
        s.set("report", report);
        s.put("status", "REPORT_READY").put("phase", "复盘已完成").put("error", "");
        save(s);
      }
    } catch (CancellationException ignored) {
    } catch (Exception e) {
      fail(id, generation, message(e));
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void recover() {
    for (var s : store.list()) {
      if (V3InterviewProgress.enabled(s) && s.path("interviewMode").asText().equals("TIMED")
          && s.path("status").asText().equals("IN_PROGRESS")) {
        progress.recover(s);
        appendWarning(s, "服务恢复后按最后保存的累计用时继续计时，不扣除宕机时间；未正常关闭时，本题尚未保存的作答时间可能少计。");
        save(s);
      } else if (BUSY.contains(s.path("status").asText())) {
        progress.stop(s);
        s.put("generation", s.path("generation").asInt() + 1)
            .put("status", "INTERRUPTED")
            .put("phase", "服务重启，任务已中断")
            .put("error", "输入与已提交回答已保存，请显式重试；不会自动重新付费调用");
        save(s);
      }
    }
  }

  @PreDestroy
  public synchronized void shutdown() {
    for (var s : store.list()) {
      if (V3InterviewProgress.enabled(s) && s.path("interviewMode").asText().equals("TIMED")
          && s.path("status").asText().equals("IN_PROGRESS")) {
        progress.stop(s); save(s);
      }
    }
    slow.shutdownNow();
    fast.shutdownNow();
    deadlines.shutdownNow();
  }
}
