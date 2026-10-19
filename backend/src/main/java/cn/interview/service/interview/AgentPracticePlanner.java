package cn.interview.service.interview;

import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.knowledge.AgentTopicCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 只用实际展示的考点安排本轮练习；跨场次不继承回答、项目经历或诊断。 */
public final class AgentPracticePlanner {
  private static final Pattern AGENT_ROLE = Pattern.compile("agent|大模型|人工智能|智能体|\\bllm\\b|\\brag\\b|ai\\s*应用", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  private final ObjectMapper json;
  private final InterviewSessionStore store;
  private final AgentTopicCatalog catalog;

  public AgentPracticePlanner(ObjectMapper json, InterviewSessionStore store) {
    this(json, store, new AgentTopicCatalog(json));
  }

  public AgentPracticePlanner(ObjectMapper json, InterviewSessionStore store, AgentTopicCatalog catalog) {
    this.json = json;
    this.store = store;
    this.catalog = catalog;
  }

  public static boolean isAgentRole(String role) {
    return role != null && AGENT_ROLE.matcher(role).find();
  }

  public void configure(ObjectNode session, JsonNode request, ObjectNode input) {
    var body = request.deepCopy();
    if (!body.isObject()) throw ApiException.bad("练习配置必须为对象");
    var normalized = (ObjectNode) body;
    for (String field : List.of("role", "level", "sessionType", "plannedQuestionCount"))
      if (!normalized.has(field) && session.has(field)) normalized.set(field, session.path(field).deepCopy());
    if (input.has("totalQuestions")) normalized.set("totalQuestions", input.path("totalQuestions").deepCopy());
    var options = options(normalized);
    var history = history(normalized);
    var pool = pool(options.topics());
    var status = status(normalized, options, history, pool);
    enforce(normalized, options, status);
    for (var target : List.of(session, input)) {
      var topics = target.putArray("agentTopics");
      options.topics().forEach(topics::add);
      target.put("practicePreference", options.preference()).put("acceptReviewFill", options.acceptFill());
      if (normalized.has("expectedNewSupplementCount")) target.set("expectedNewSupplementCount", normalized.path("expectedNewSupplementCount").deepCopy());
      else target.remove("expectedNewSupplementCount");
    }
  }

  public ObjectNode preview(JsonNode request) {
    var options = options(request);
    return status(request, options, history(request), pool(options.topics()));
  }

  public ObjectNode plan(ObjectNode session, JsonNode analysis) {
    if (!isAgentRole(session.path("role").asText())) return null;
    var body = session.deepCopy();
    if (session.path("input").has("totalQuestions")) body.set("totalQuestions", session.path("input").path("totalQuestions").deepCopy());
    if (!body.has("expectedNewSupplementCount") && session.path("input").has("expectedNewSupplementCount"))
      body.set("expectedNewSupplementCount", session.path("input").path("expectedNewSupplementCount").deepCopy());
    var options = options(body);
    var topics = new LinkedHashSet<>(options.topics());
    if (!quick(body) && topics.isEmpty() && options.preference().equals("NEW_FIRST")) {
      topics.addAll(analysisThemes(analysis));
      if (topics.isEmpty() && !session.path("mainProject").path("hypothetical").asBoolean(true))
        return emptyPlan();
    }
    var pool = pool(topics);
    var history = history(body);
    var status = status(body, options, history, pool);
    enforce(body, options, status);
    int count = status.path("requestedCount").asInt();
    var selected = new ArrayList<ObjectNode>();
    var used = new HashSet<String>();
    var themeCounts = new HashMap<String, Integer>();
    if (options.preference().equals("REVIEW")) {
      select(pool.stream().filter(point -> history.lastSeen().containsKey(id(point))).toList(), count, true, history, selected, used, themeCounts);
      select(pool.stream().filter(point -> !history.lastSeen().containsKey(id(point))).toList(), count, false, history, selected, used, themeCounts);
    } else {
      select(pool.stream().filter(point -> !history.recent().contains(id(point))).toList(), count, false, history, selected, used, themeCounts);
      select(pool.stream().filter(point -> history.recent().contains(id(point))).toList(), count, true, history, selected, used, themeCounts);
    }
    var result = status.deepCopy();
    var points = result.putArray("points");
    int reviewCount = 0;
    for (var point : selected) {
      boolean review = history.lastSeen().containsKey(id(point));
      points.add(point.deepCopy().put("review", review));
      if (review) reviewCount++;
    }
    result.put("newCount", selected.size() - reviewCount).put("reviewCount", reviewCount);
    var warnings = result.putArray("warnings");
    if (options.preference().equals("REVIEW") && status.path("newSupplementCount").asInt() > 0)
      warnings.add("本轮复习包含" + status.path("newSupplementCount").asInt() + "个已确认补充的新考点。");
    if (options.preference().equals("NEW_FIRST") && selected.stream().anyMatch(point -> history.recent().contains(id(point))))
      warnings.add("所选范围内近期未练考点不足，已从最久未练的考点补足。");
    return result;
  }

  private ObjectNode emptyPlan() {
    var result = json.createObjectNode().put("requestedCount", 0).put("newCount", 0).put("reviewCount", 0);
    result.putArray("points"); result.putArray("warnings");
    return result;
  }

  private void select(List<ObjectNode> candidates, int count, boolean oldest, History history,
      List<ObjectNode> selected, Set<String> used, Map<String, Integer> themeCounts) {
    var order = new HashMap<String, Integer>();
    for (int index = 0; index < candidates.size(); index++) order.put(id(candidates.get(index)), index);
    while (selected.size() < count) {
      Comparator<ObjectNode> comparator = Comparator.comparingInt(point -> themeCounts.getOrDefault(point.path("theme").asText(), 0));
      comparator = comparator.thenComparing(point -> history.recentThemeLastSeen().getOrDefault(point.path("theme").asText(), Instant.MIN));
      if (oldest) comparator = comparator.thenComparing(point -> history.lastSeen().getOrDefault(id(point), Instant.MIN));
      comparator = comparator.thenComparingInt(point -> order.get(id(point)));
      var next = candidates.stream().filter(point -> !used.contains(id(point))).min(comparator).orElse(null);
      if (next == null) return;
      selected.add(next); used.add(id(next));
      themeCounts.merge(next.path("theme").asText(), 1, Integer::sum);
    }
  }

  private record Options(Set<String> topics, String preference, boolean acceptFill) {}
  private record History(Map<String, Instant> lastSeen, Set<String> recent, Map<String, Instant> recentThemeLastSeen) {}
  private record PracticeSession(Map<String, Instant> asked, Instant lastAskedAt) {}

  private Options options(JsonNode request) {
    if (!request.isObject()) throw ApiException.bad("练习配置必须为对象");
    var topics = new LinkedHashSet<String>();
    if (request.has("agentTopics")) {
      if (!request.path("agentTopics").isArray()) throw ApiException.bad("Agent主题必须为数组");
      for (var item : request.path("agentTopics")) {
        if (!item.isTextual() || !AgentTopicCatalog.THEME_IDS.contains(item.asText())) throw ApiException.bad("请选择有效Agent主题");
        topics.add(item.asText());
      }
    }
    String preference = request.has("practicePreference") ? request.path("practicePreference").asText() : "NEW_FIRST";
    if (!Set.of("NEW_FIRST", "REVIEW").contains(preference)) throw ApiException.bad("请选择有效练习偏好");
    if (request.has("acceptReviewFill") && !request.path("acceptReviewFill").isBoolean()) throw ApiException.bad("补新确认必须为布尔值");
    if (request.has("expectedNewSupplementCount") && (!request.path("expectedNewSupplementCount").isIntegralNumber()
        || !request.path("expectedNewSupplementCount").canConvertToInt() || request.path("expectedNewSupplementCount").asInt() < 0))
      throw ApiException.bad("补新确认数量必须为非负整数");
    if (!isAgentRole(request.path("role").asText()) && (!topics.isEmpty() || preference.equals("REVIEW")))
      throw ApiException.bad("当前岗位不是Agent方向，请勿设置Agent主题或复习偏好");
    return new Options(topics, preference, request.path("acceptReviewFill").asBoolean());
  }

  private ObjectNode status(JsonNode request, Options options, History history, List<ObjectNode> pool) {
    boolean eligible = isAgentRole(request.path("role").asText());
    int requested = count(request);
    if (!quick(request)) requested = Math.min(requested, pool.size());
    int available = (int) pool.stream().filter(point -> history.lastSeen().containsKey(id(point))).count();
    int supplement = eligible && options.preference().equals("REVIEW") ? Math.max(0, requested - available) : 0;
    boolean needsAck = supplement > 0 && available > 0;
    boolean canStart = !eligible || (pool.size() >= requested
        && (!options.preference().equals("REVIEW") || available > 0));
    String message = !eligible ? "当前岗位沿用原有面试规划。" : pool.size() < requested ? "所选主题可用考点不足，请减少题量或扩大主题范围。"
        : options.preference().equals("REVIEW") && available == 0 ? "所选范围尚无可复习的已问考点，请先练新题。"
        : needsAck ? "可复习" + available + "个考点，需要确认补充" + supplement + "个新考点。" : "可按当前偏好开始练习。";
    return json.createObjectNode().put("eligible", eligible).put("requestedCount", requested)
        .put("reviewAvailable", available).put("newSupplementCount", supplement).put("canStart", canStart)
        .put("requiresAcknowledgement", needsAck).put("message", message);
  }

  private void enforce(JsonNode request, Options options, ObjectNode status) {
    if (!status.path("eligible").asBoolean()) return;
    if (options.preference().equals("REVIEW")) {
      if (status.path("reviewAvailable").asInt() == 0) throw ApiException.bad("所选范围没有可复习考点，请先练新题");
      int supplement = status.path("newSupplementCount").asInt();
      if (supplement > 0 && !options.acceptFill()) throw ApiException.bad("复习数量不足，请明确确认补充新考点");
      if (options.acceptFill() && (!request.path("expectedNewSupplementCount").isIntegralNumber()
          || request.path("expectedNewSupplementCount").asInt() != supplement))
        throw ApiException.conflict("可复习考点数量已变化，请重新检查并确认补新数量");
    }
    if (!status.path("canStart").asBoolean()) throw ApiException.bad(status.path("message").asText());
  }

  private int count(JsonNode request) {
    var value = request.has("totalQuestions") ? request.path("totalQuestions") : request.path("plannedQuestionCount");
    int count = value.isMissingNode() ? (quick(request) ? 5 : 8) : value.asInt(-1);
    if ((!value.isMissingNode() && (!value.isIntegralNumber() || !value.canConvertToInt())) || count < 1 || count > 20)
      throw ApiException.bad("请选择有效主问题数量");
    return quick(request) ? count : Math.max(0, count - 3);
  }

  private boolean quick(JsonNode request) {
    return !request.has("sessionType") || request.path("sessionType").asText().equals("QUICK");
  }

  private List<ObjectNode> pool(Set<String> topics) {
    return catalog.points().stream().filter(point -> topics.isEmpty() || topics.contains(point.path("theme").asText())).toList();
  }

  private Set<String> analysisThemes(JsonNode analysis) {
    var themes = new LinkedHashSet<String>();
    for (var value : analysis.path("topics")) {
      String topic = value.asText();
      if (AgentTopicCatalog.THEME_IDS.contains(topic)) themes.add(topic);
      else switch (topic) {
        case "AI_RAG" -> themes.add("AGENT_RAG");
        case "AI_MEMORY" -> themes.add("AGENT_CONTEXT_MEMORY");
        case "AI_TOOLS" -> { themes.add("AGENT_ARCHITECTURE"); themes.add("AGENT_HARNESS_TOOL"); themes.add("AGENT_FRAMEWORK"); }
        case "AI_EVALUATION" -> themes.add("AGENT_EVALUATION");
        default -> { }
      }
    }
    return themes;
  }

  private Instant askedAt(JsonNode message, JsonNode session) {
    for (String value : List.of(message.path("createdAt").asText(), session.path("createdAt").asText())) {
      try {
        return Instant.parse(value);
      } catch (DateTimeParseException ignored) { }
    }
    return Instant.MIN;
  }

  private History history(JsonNode request) {
    var lastSeen = new HashMap<String, Instant>();
    var recent = new HashSet<String>();
    var recentThemeLastSeen = new HashMap<String, Instant>();
    var practiced = new ArrayList<PracticeSession>();
    if (!isAgentRole(request.path("role").asText())) return new History(lastSeen, recent, recentThemeLastSeen);
    for (var session : store.list()) {
      if (session.hasNonNull("deletedAt") || session.path("mock").asBoolean() || !isAgentRole(session.path("role").asText())
          || !session.path("level").asText().equals(request.path("level").asText("JUNIOR"))) continue;
      var asked = new LinkedHashMap<String, Instant>();
      for (var message : session.path("messages")) {
        var point = reliablePoint(message);
        if (point == null) continue;
        Instant timestamp = askedAt(message, session);
        asked.merge(id(point), timestamp, (a, b) -> a.compareTo(b) >= 0 ? a : b);
      }
      if (asked.isEmpty()) continue;
      practiced.add(new PracticeSession(asked, asked.values().stream().max(Instant::compareTo).orElse(Instant.MIN)));
    }
    practiced.sort(Comparator.comparing(PracticeSession::lastAskedAt).reversed());
    int validSessions = 0;
    for (var session : practiced) {
      var asked = session.asked();
      if (validSessions++ < 10) {
        recent.addAll(asked.keySet());
        asked.forEach((pointId, timestamp) -> recentThemeLastSeen.merge(catalog.point(pointId).path("theme").asText(), timestamp,
            (a, b) -> a.compareTo(b) >= 0 ? a : b));
      }
      asked.forEach((point, timestamp) -> lastSeen.merge(point, timestamp, (a, b) -> a.compareTo(b) >= 0 ? a : b));
    }
    return new History(lastSeen, recent, recentThemeLastSeen);
  }

  private ObjectNode reliablePoint(JsonNode message) {
    if (!message.path("role").asText().equals("ASSISTANT") || !message.path("pointId").isTextual()
        || !message.path("questionId").isTextual() || message.path("questionId").asText().isBlank()) return null;
    var point = catalog.point(message.path("pointId").asText());
    if (point == null || (message.has("pointTheme") && !message.path("pointTheme").equals(point.path("theme")))) return null;
    return point;
  }

  public ObjectNode coverage(JsonNode session) {
    var result = json.createObjectNode();
    var items = result.putArray("items");
    var byPoint = new LinkedHashMap<String, ObjectNode>();
    var answered = new HashSet<String>();
    for (var message : session.path("messages")) if (message.path("role").asText().equals("USER") && !message.path("content").asText().isBlank())
      answered.add(message.path("questionId").asText());
    for (var message : session.path("messages")) {
      var point = reliablePoint(message);
      if (point == null) continue;
      String qid = message.path("questionId").asText();
      var item = byPoint.get(id(point));
      if (item == null) {
        item = json.createObjectNode().put("pointId", id(point)).put("theme", point.path("theme").asText())
            .put("title", point.path("title").asText()).put("questionId", qid)
            .put("rootQuestionId", message.path("rootQuestionId").asText(qid))
            .put("review", message.path("pointReview").asBoolean()).put("answered", false);
        byPoint.put(id(point), item);
      }
      if (answered.contains(qid)) item.put("answered", true);
    }
    byPoint.values().forEach(items::add);
    int answeredCount = 0, reviewCount = 0;
    for (var item : byPoint.values()) {
      if (item.path("answered").asBoolean()) answeredCount++;
      if (item.path("review").asBoolean()) reviewCount++;
    }
    return result.put("askedCount", items.size()).put("answeredCount", answeredCount)
        .put("newCount", items.size() - reviewCount).put("reviewCount", reviewCount);
  }

  private String id(JsonNode point) { return point.path("id").asText(); }
}
