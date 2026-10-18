package cn.interview.v3.validation;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 结构、当前快照引用与证据边界校验；不把结构通过当成语义正确。 */
@Component
public class V3OutputValidator {
  public static final Set<String> TOPICS =
      Set.of(
          "JAVA_CORE",
          "JAVA_CONCURRENCY",
          "JVM",
          "SPRING",
          "DATABASE",
          "REDIS",
          "DISTRIBUTED",
          "AI_RAG",
          "AI_TOOLS",
          "AI_MEMORY",
          "AI_EVALUATION");
  private final ObjectMapper json;

  public V3OutputValidator(ObjectMapper json) {
    this.json = json;
  }

  public static ApiException invalid() {
    return new ApiException(502, "V3_MODEL_INVALID", "模型输出结构或依据不合法，输入已保存；可显式重试");
  }

  public String text(JsonNode n, int max) {
    if (n == null || !n.isTextual() || n.asText().isBlank() || n.asText().length() > max)
      throw invalid();
    return n.asText().strip();
  }

  public ArrayNode strings(JsonNode n, int count, int length) {
    if (!n.isArray() || n.size() > count) throw invalid();
    ArrayNode out = json.createArrayNode();
    for (var v : n) out.add(text(v, length));
    return out;
  }

  public ObjectNode analysis(JsonNode raw) {
    if (!raw.isObject()) throw invalid();
    var summary = json.createObjectNode();
    for (String field : List.of("technicalStack", "responsibilities", "clarifications", "focus"))
      summary.set(field, strings(raw.path("summary").path(field), 20, 1000));
    var out = json.createObjectNode();
    out.set("summary", summary);
    var topics = out.putArray("topics");
    if (!raw.path("topics").isArray()) throw invalid();
    for (var topic : raw.path("topics"))
      if (topic.isTextual() && TOPICS.contains(topic.asText()) && topics.size() < 8)
        topics.add(topic.asText());
    return out;
  }

  private Set<String> ids(JsonNode sources) {
    var set = new HashSet<String>();
    for (var s : sources) set.add(s.path("id").asText());
    return set;
  }

  public ArrayNode references(JsonNode refs, JsonNode sources) {
    if (!refs.isArray() || refs.size() > 6) throw invalid();
    var allowed = ids(sources);
    var out = json.createArrayNode();
    for (var id : refs) {
      if (!id.isTextual() || !allowed.contains(id.asText())) throw invalid();
      out.add(id.asText());
    }
    return out;
  }

  public ArrayNode questions(JsonNode raw, JsonNode sources, String resume, String jd, int count) {
    var list = raw.path("questions");
    if (!list.isArray() || list.isEmpty() || list.size() > count) throw invalid();
    var out = json.createArrayNode();
    for (var q : list) {
      var clean = out.addObject();
      clean
          .put("id", UUID.randomUUID().toString())
          .put("question", text(q.path("question"), 2000))
          .put("topic", text(q.path("topic"), 150));
      String evidence = text(q.path("evidence"), 1000);
      boolean literal =
          (!resume.isBlank() && resume.contains(evidence))
              || (!jd.isBlank() && jd.contains(evidence));
      if (!literal && !Set.of("通用岗位知识", "通用基准摸底", "岗位要求").contains(evidence)) throw invalid();
      clean.put("evidence", evidence);
      clean
          .put("personalized", literal)
          .put("resumeBased", !resume.isBlank() && resume.contains(evidence));
      clean.set("sourceIds", references(q.path("sourceIds"), sources));
      var backups = clean.putArray("backups");
      if (!q.path("backups").isArray() || q.path("backups").size() > 3) throw invalid();
      for (var b : q.path("backups")) {
        var backup = backups.addObject();
        backup.put("question", text(b.path("question"), 1500));
        backup.set("sourceIds", references(b.path("sourceIds"), sources));
      }
    }
    // 有资料的面试必须至少有一道问题以当前资料原文为依据。
    if (!resume.isBlank()
        && java.util.stream.StreamSupport.stream(out.spliterator(), false)
            .noneMatch(q -> q.path("resumeBased").asBoolean())) throw invalid();
    return out;
  }

  /** 新场次题目必须按四阶段排列，不能用三道短题伪装完整轮次。 */
  public ArrayNode stageQuestions(JsonNode raw, ArrayNode clean, JsonNode input) {
    List<String> stages = List.of("INTRO", "PROJECT", "TECHNICAL", "QUESTIONS");
    if (clean.size() < 4) throw invalid();
    int previous = -1; Set<String> seen = new HashSet<>();
    for (int i = 0; i < clean.size(); i++) {
      String stage = raw.path("questions").get(i).path("stage").asText();
      int current = stages.indexOf(stage);
      if (current < 0 || current < previous) throw invalid();
      previous = current; seen.add(stage); ((ObjectNode) clean.get(i)).put("stage", stage);
    }
    if (seen.size() != 4) throw invalid();
    return clean;
  }

  public ObjectNode followup(JsonNode raw, JsonNode sources) {
    String action = text(raw.path("action"), 20);
    if (!Set.of("FOLLOW_UP", "NEXT").contains(action)) throw invalid();
    var out = json.createObjectNode().put("action", action);
    if (raw.has("stageComplete")) {
      if (!raw.path("stageComplete").isBoolean()
          || (raw.path("stageComplete").asBoolean() && !action.equals("NEXT"))) throw invalid();
      out.put("stageComplete", raw.path("stageComplete").asBoolean());
    }
    if (action.equals("FOLLOW_UP")) out.put("question", text(raw.path("question"), 1500));
    out.set("sourceIds", references(raw.path("sourceIds"), sources));
    if (raw.has("reply") && !raw.path("reply").asText().isBlank()) out.put("reply", text(raw.path("reply"), 2000));
    return out;
  }

  public ObjectNode report(JsonNode raw, JsonNode messages, JsonNode sources) {
    var out = json.createObjectNode().put("summary", text(raw.path("summary"), 4000));
    var findings = out.putArray("findings");
    var answered = new HashSet<String>();
    for (var m : messages)
      if (m.path("role").asText().equals("USER")) answered.add(m.path("questionId").asText());
    if (!raw.path("findings").isArray() || raw.path("findings").size() > 40) throw invalid();
    var seen = new HashSet<String>();
    for (var f : raw.path("findings")) {
      String q = text(f.path("questionId"), 100);
      if (!answered.contains(q) || !seen.add(q)) throw invalid();
      var refs = references(f.path("sourceIds"), sources);
      String status = f.path("status").asText();
      if (!Set.of("SUPPORTED", "INSUFFICIENT", "CONFLICTING").contains(status)) throw invalid();
      String quote = f.path("sourceQuote").asText("");
      boolean supported = false;
      for (var source : sources)
        if (refs.toString().contains("\"" + source.path("id").asText() + "\"")
            && source.path("kind").asText().equals("OFFICIAL")
            && !source.path("stale").asBoolean()
            && quote.length() >= 12
            && source.path("excerpt").asText().contains(quote)) supported = true;
      var finding =
          findings
              .addObject()
              .put("questionId", q)
              .put("assessment", text(f.path("assessment"), 4000))
              .put("suggestion", text(f.path("suggestion"), 2000));
      if (!supported) {
        status = "INSUFFICIENT";
        finding.put(
            "assessment",
            "技术结论待核实：当前没有足够且可逐字核对的官方依据。模型观察（不作为判错依据）：" + finding.path("assessment").asText());
      }
      finding.put("status", status);
      finding.set("sourceIds", refs);
      if (supported) finding.put("sourceQuote", quote);
    }
    for (String q : answered)
      if (!seen.contains(q))
        findings
            .addObject()
            .put("questionId", q)
            .put("assessment", "本题未取得足够评估依据，保留原回答供核对")
            .put("status", "INSUFFICIENT")
            .put("suggestion", "补充业务条件和个人职责后再验证")
            .putArray("sourceIds");
    return out;
  }
  /** 内容观察与技术查证分别校验，原答短引只能来自对应已提交回答。 */
  public ObjectNode reportV2(JsonNode raw, JsonNode messages, JsonNode sources) {
    var out = report(raw, messages, sources);
    out.put("version", 2);
    for (var node : out.path("findings")) {
      var finding = (ObjectNode) node;
      String qid = finding.path("questionId").asText();
      JsonNode candidate = null;
      for (var f : raw.path("findings")) if (f.path("questionId").asText().equals(qid)) candidate = f;
      finding.put("answerStatus", "UNVERIFIED").put("headline", "本题尚未形成充分内容观察")
          .put("observation", "现有评估样本不足，保留原回答，不能据此判断为短板。")
          .put("priority", 3).putArray("terms");
      if (candidate == null || !candidate.has("answerStatus")) continue;
      String state = candidate.path("answerStatus").asText();
      if (!Set.of("CLEAR", "PARTIAL", "UNVERIFIED").contains(state)) throw invalid();
      String quote = candidate.path("answerQuote").asText("");
      if (!quote.isEmpty()) {
        if (!candidate.path("answerQuote").isTextual() || quote.length() > 500) throw invalid();
        boolean literal = false;
        for (var m : messages) if (m.path("role").asText().equals("USER")
            && m.path("questionId").asText().equals(qid) && m.path("content").asText().contains(quote)) literal = true;
        if (!literal) throw invalid();
        finding.put("answerQuote", quote);
      }
      if (!state.equals("UNVERIFIED") && (quote.isBlank() || candidate.path("observation").asText().isBlank())) continue;
      finding.put("answerStatus", state).put("headline", text(candidate.path("headline"), 120))
          .put("observation", text(candidate.path("observation"), 1500));
      var priority = candidate.path("priority");
      if (!priority.isIntegralNumber() || priority.asInt() < 1 || priority.asInt() > 3) throw invalid();
      finding.put("priority", priority.asInt());
      if (!candidate.path("terms").isArray() || candidate.path("terms").size() > 3) throw invalid();
      var terms = finding.putArray("terms");
      for (var term : candidate.path("terms")) terms.addObject().put("term", text(term.path("term"), 80))
          .put("explanation", text(term.path("explanation"), 250));
    }
    return out;
  }

}
