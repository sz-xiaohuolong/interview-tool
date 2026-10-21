package cn.interview.validation;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 结构、当前快照引用与证据边界校验；不把结构通过当成语义正确。 */
@Component
public class InterviewOutputValidator {
  public static final Set<String> TOPICS =
      Set.of(
          "JAVA_CORE",
          "JAVA_CONCURRENCY",
          "JVM",
          "SPRING",
          "DATABASE",
          "REDIS",
          "DISTRIBUTED",
          "NETWORK",
          "AI_RAG",
          "AI_TOOLS",
          "AI_MEMORY",
          "AI_EVALUATION",
          "AGENT_ARCHITECTURE",
          "AGENT_CONTEXT_MEMORY",
          "AGENT_HARNESS_TOOL",
          "AGENT_RAG",
          "AGENT_EVALUATION",
          "AGENT_FRAMEWORK");
  private final ObjectMapper json;

  public InterviewOutputValidator(ObjectMapper json) {
    this.json = json;
  }

  public static ApiException invalid() {
    return new ApiException(502, "V3_MODEL_INVALID", "模型输出结构或依据不合法，输入已保存；可显式重试");
  }

  private static ApiException invalid(String reason) {
    return new ApiException(502, "V3_MODEL_INVALID", "模型输出结构或依据不合法：" + reason + "；输入已保存，可显式重试");
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
    if (!list.isArray() || list.isEmpty() || list.size() > count) throw invalid("主问题数量不符合本场规划");
    var out = json.createArrayNode();
    for (var q : list) {
      var clean = out.addObject();
      clean
          .put("id", UUID.randomUUID().toString())
          .put("question", planText(q.path("question"), 2000, out.size(), "题干"))
          .put("topic", planText(q.path("topic"), 150, out.size(), "技术主题"));
      String evidence = planText(q.path("evidence"), 1000, out.size(), "资料引用");
      boolean literal =
          (!resume.isBlank() && resume.contains(evidence))
              || (!jd.isBlank() && jd.contains(evidence));
      if (!literal && !Set.of("通用岗位知识", "通用基准摸底", "岗位要求").contains(evidence))
        throw invalid("第" + out.size() + "道主问题的资料引用未逐字匹配当前资料原文");
      clean.put("evidence", evidence);
      clean
          .put("personalized", literal)
          .put("resumeBased", !resume.isBlank() && resume.contains(evidence));
      clean.set("sourceIds", planReferences(q.path("sourceIds"), sources, out.size()));
      var backups = clean.putArray("backups");
      if (!q.path("backups").isArray() || q.path("backups").size() > 3)
        throw invalid("第" + out.size() + "道主问题的备用追问数量或格式不合法");
      for (var b : q.path("backups")) {
        var backup = backups.addObject();
        backup.put("question", planText(b.path("question"), 1500, out.size(), "备用追问"));
        backup.set("sourceIds", planReferences(b.path("sourceIds"), sources, out.size()));
      }
    }
    // 有资料的面试必须至少有一道问题以当前资料原文为依据。
    if (!resume.isBlank()
        && java.util.stream.StreamSupport.stream(out.spliterator(), false)
            .noneMatch(q -> q.path("resumeBased").asBoolean())) throw invalid("有简历时至少一道主问题须引用简历原文");
    return out;
  }

  private String planText(JsonNode value, int max, int number, String field) {
    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max)
      throw invalid("第" + number + "道主问题的" + field + "缺失、格式错误或超过长度限制");
    return value.asText().strip();
  }

  /** 用服务端选定的稳定身份绑定考点，不能由模型自称已覆盖其他考点。 */
  public ArrayNode agentQuestions(JsonNode raw, ArrayNode clean, JsonNode input) {
    var points = input.path("agentPractice").path("points");
    if (!points.isArray() || points.isEmpty()) return clean;
    Map<String, JsonNode> allowed = new LinkedHashMap<>();
    for (var point : points) allowed.put(point.path("id").asText(), point);
    Set<String> used = new HashSet<>();
    Set<String> questionTexts = new HashSet<>();
    boolean quick = input.path("sessionType").asText().equals("QUICK");
    for (int i = 0; i < clean.size(); i++) {
      var question = (ObjectNode) clean.get(i);
      var original = raw.path("questions").get(i);
      if (!quick && !question.path("stage").asText().equals("TECHNICAL")) {
        if (original.hasNonNull("pointId") && !original.path("pointId").asText().isBlank())
          throw invalid("非技术阶段不应冒充目录考点覆盖");
        continue;
      }
      String id = original.path("pointId").asText();
      String normalizedQuestion = question.path("question").asText().replaceAll("[\\p{Z}\\s]+", "").toLowerCase(Locale.ROOT);
      if (!questionTexts.add(normalizedQuestion)) throw invalid("主问题题干重复，不能用不同考点标识重复考查");
      JsonNode point = allowed.get(id);
      if (point == null || !used.add(id)) throw invalid("考点标识未选定、缺失或重复");
      if (!original.path("topic").asText().equals(point.path("theme").asText()))
        throw invalid("考点与题目主题不一致");
      question.put("pointId", id).put("pointTheme", point.path("theme").asText())
          .put("pointTitle", point.path("title").asText()).put("pointReview", point.path("review").asBoolean());
      var seed = point.path("seedQuestion");
      String declaredSeed = original.path("seedQuestionId").asText();
      if (!declaredSeed.equals(seed.path("id").asText()))
        throw invalid("参考原题身份与服务端选定素材不一致");
      if (seed.isObject()) question.put("seedQuestionId", seed.path("id").asText())
          .put("seedQuestionTitle", seed.path("title").asText()).put("seedQuestionUrl", seed.path("url").asText())
          .put("seedQuestionDifficulty", seed.path("difficulty").asText());
    }
    if (quick && used.size() != allowed.size()) throw invalid("主问题未覆盖本场选定考点");
    return clean;
  }

  private ArrayNode planReferences(JsonNode refs, JsonNode sources, int number) {
    try {
      return references(refs, sources);
    } catch (ApiException error) {
      throw invalid("第" + number + "道主问题或备用追问的公开来源引用不合法");
    }
  }

  /** 新场次题目必须按四阶段排列，不能用三道短题伪装完整轮次。 */
  public ArrayNode stageQuestions(JsonNode raw, ArrayNode clean, JsonNode input) {
    List<String> stages = List.of("INTRO", "PROJECT", "TECHNICAL", "QUESTIONS");
    if (clean.size() < 4) throw invalid("主问题未覆盖完整的四个面试阶段");
    int previous = -1; Set<String> seen = new HashSet<>();
    for (int i = 0; i < clean.size(); i++) {
      String stage = raw.path("questions").get(i).path("stage").asText();
      int current = stages.indexOf(stage);
      if (current < 0) throw invalid("第" + (i + 1) + "道主问题的面试阶段缺失或无效");
      if (current < previous) throw invalid("第" + (i + 1) + "道主问题的阶段顺序不符合四阶段规划");
      previous = current; seen.add(stage); ((ObjectNode) clean.get(i)).put("stage", stage);
    }
    if (seen.size() != 4) throw invalid("主问题未覆盖完整的四个面试阶段");
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

  public ObjectNode followup(JsonNode raw, JsonNode sources, String answer, boolean required) {
    var out=followup(raw,sources);
    if(required || raw.has("diagnosis")) {
      validateDiagnosis(raw.path("diagnosis"),answer,raw.path("action").asText());
      out.set("diagnosis",raw.path("diagnosis").deepCopy());
    }
    return out;
  }

  /** 只验证引用与结构，诊断本身仍须对照原答和技术来源核实。 */
  private static void validateDiagnosis(JsonNode d,String answer,String action) {
    if(!d.isObject() || !Set.of("SUFFICIENT","INCOMPLETE","INCORRECT","UNCERTAIN","CONTRADICTORY","NO_EVIDENCE").contains(d.path("category").asText()))throw invalid();
    var covered=d.path("coveredPoints");
    if(!covered.isArray() || covered.size()>3)throw invalid();
    for(var point:covered)diagnosisText(point,30,false);
    diagnosisText(d.path("answerQuote"),80,false);
    if(!answer.contains(d.path("answerQuote").asText()))throw invalid();
    diagnosisText(d.path("gap"),70,true);diagnosisText(d.path("nextGoal"),60,true);
    diagnosisText(d.path("reason"),70,false);
    if(action.equals("FOLLOW_UP") && d.path("nextGoal").asText().isBlank())throw invalid();
    if(d.toString().length()>650)throw invalid();
  }

  private static void diagnosisText(JsonNode value,int max,boolean blankAllowed) {
    if(!value.isTextual() || value.asText().length()>max || (!blankAllowed && value.asText().isBlank()))throw invalid();
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
  public ObjectNode evidenceReport(JsonNode raw, JsonNode messages, JsonNode sources) {
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
    if (raw.has("detailedAssessment"))
      out.set("detailedAssessment", detailedAssessment(raw.path("detailedAssessment"), out.path("findings")));
    return out;
  }

  /** 综合段落仅引用已校验的逐题观察，避免把未作答或尚未验证的题目变成短板。 */
  private ObjectNode detailedAssessment(JsonNode raw, JsonNode findings) {
    if (!raw.isObject() || !raw.path("version").isIntegralNumber() || raw.path("version").asInt() != 1)
      throw invalid("详细评估版本无效");
    var out = json.createObjectNode().put("version", 1);
    out.set("overall", assessmentParagraph(raw.path("overall"), findings, "overall"));
    for (String section : List.of("strengths", "improvements", "nextSteps")) {
      var paragraphs = raw.path(section);
      if (!paragraphs.isArray() || paragraphs.size() > 3) throw invalid("详细评估段落缺失或过多");
      var clean = out.putArray(section);
      for (var paragraph : paragraphs) clean.add(assessmentParagraph(paragraph, findings, section));
    }
    var improvementIds = new HashSet<String>();
    for (var paragraph : out.path("improvements"))
      for (var id : paragraph.path("questionIds")) improvementIds.add(id.asText());
    for (var paragraph : out.path("nextSteps"))
      for (var id : paragraph.path("questionIds"))
        if (!improvementIds.contains(id.asText())) throw invalid("行动未关联本场已确认改进项");
    return out;
  }

  private ObjectNode assessmentParagraph(JsonNode raw, JsonNode findings, String section) {
    if (!raw.isObject()) throw invalid("详细评估段落格式不合法");
    var out = json.createObjectNode().put("text", text(raw.path("text"), section.equals("overall") ? 700 : 650));
    var refs = raw.path("questionIds");
    if (!refs.isArray() || refs.size() > 6 || (!section.equals("overall") && refs.isEmpty()))
      throw invalid("详细评估缺少原答关联");
    var seen = new HashSet<String>();
    var clean = out.putArray("questionIds");
    for (var ref : refs) {
      String id = text(ref, 100);
      if (!seen.add(id)) throw invalid("详细评估重复引用题号");
      JsonNode finding = null;
      for (var item : findings) if (item.path("questionId").asText().equals(id)) finding = item;
      if (finding == null) throw invalid("详细评估引用未作答题目");
      if (!section.equals("overall")) {
        String required = section.equals("strengths") ? "CLEAR" : "PARTIAL";
        if (!finding.path("answerStatus").asText().equals(required) || finding.path("answerQuote").asText().isBlank())
          throw invalid("详细评估与原答观察不一致");
      }
      clean.add(id);
    }
    return out;
  }


  public static void validateModel(String stage, ObjectNode input, ObjectNode result) {
    Set<String> sources = new HashSet<>();
    for (JsonNode source : input.path("sources")) sources.add(source.path("id").asText());
    references(result, sources);
    switch (stage) {
      case "ANALYZE" -> {
        JsonNode summary = result.path("summary");
        if (!summary.isObject()) invalidModelOutput();
        for (String key :
            new String[] {"technicalStack", "responsibilities", "clarifications", "focus"})
          strings(summary.path(key));
        strings(result.path("topics"));
        for (JsonNode topic : result.path("topics"))
          if (!TOPICS.contains(topic.asText())) invalidModelOutput();
      }
      case "PLAN" -> {
        JsonNode questions = result.path("questions");
        if (!questions.isArray() || questions.size() != input.path("totalQuestions").asInt(-1))
          invalidModelOutput();
        Set<String> ids = new HashSet<>();
        for (JsonNode question : questions) {
          text(question, "id");
          text(question, "topic");
          text(question, "question");
          text(question, "evidence");
          if (!ids.add(question.path("id").asText())) invalidModelOutput();
          strings(question.path("sourceIds"));
          if (!question.path("backups").isArray() || question.path("backups").size() > 2) invalidModelOutput();
          for (JsonNode backup : question.path("backups")) {
            text(backup, "question");
            strings(backup.path("sourceIds"));
          }
        }
        new InterviewOutputValidator(new ObjectMapper()).agentQuestions(result, ((ArrayNode) questions).deepCopy(), input);
      }
      case "FOLLOWUP" -> {
        if(input.path("strategyVersion").asInt()==1)validateDiagnosis(result.path("diagnosis"),input.path("answer").asText(),result.path("action").asText());
        String action = result.path("action").asText();
        if (!Set.of("FOLLOW_UP", "NEXT").contains(action)) invalidModelOutput();
        if (action.equals("FOLLOW_UP")) {
          text(result, "question");
          if (input.path("followUpCount").asInt() >= 2) invalidModelOutput();
        }
        strings(result.path("sourceIds"));
      }
      case "REVIEW" -> {
        text(result, "summary");
        if (!result.path("findings").isArray()) invalidModelOutput();
        Set<String> answered = new HashSet<>();
        for (JsonNode message : input.path("messages")) {
          if (message.path("role").asText().equals("USER"))
            answered.add(message.path("questionId").asText());
        }
        for (JsonNode finding : result.path("findings")) {
          text(finding, "questionId");
          text(finding, "assessment");
          text(finding, "suggestion");
          if (!answered.contains(finding.path("questionId").asText())) invalidModelOutput();
          if (!Set.of("SUPPORTED", "INSUFFICIENT", "CONFLICTING")
              .contains(finding.path("status").asText())) invalidModelOutput();
          strings(finding.path("sourceIds"));
          if (finding.path("sourceIds").isEmpty())
            ((ObjectNode) finding).put("status", "INSUFFICIENT");
        }
      }
      default -> invalidModelOutput();
    }
  }

  private static void references(JsonNode node, Set<String> allowed) {
    if (node.isObject()) {
      node.fields()
          .forEachRemaining(
              entry -> {
                if (entry.getKey().equals("sourceIds")) {
                  strings(entry.getValue());
                  for (JsonNode id : entry.getValue())
                    if (!allowed.contains(id.asText())) invalidModelOutput();
                } else references(entry.getValue(), allowed);
              });
    } else if (node.isArray()) for (JsonNode child : node) references(child, allowed);
  }

  private static void strings(JsonNode node) {
    if (!node.isArray() || node.size() > 32) invalidModelOutput();
    for (JsonNode value : node)
      if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 3000)
        invalidModelOutput();
  }

  private static void text(JsonNode node, String key) {
    if (!node.path(key).isTextual()
        || node.path(key).asText().isBlank()
        || node.path(key).asText().length() > 6000) invalidModelOutput();
  }

  private static void invalidModelOutput() {
    throw new ApiException(502, "MODEL_INVALID_OUTPUT", "模型输出不符合本阶段结构或引用边界，没有保存为有效结果。");
  }
}
