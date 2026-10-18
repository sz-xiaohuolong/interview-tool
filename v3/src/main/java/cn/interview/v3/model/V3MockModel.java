package cn.interview.v3.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 明确的合成测试数据，只验证软件流程，不代表模型质量或真实评分。 */
final class V3MockModel {
  private final ObjectMapper json;

  V3MockModel(ObjectMapper json) {
    this.json = json;
  }

  ObjectNode call(String stage, ObjectNode input) {
    ObjectNode result =
        json.createObjectNode().put("mock", true).put("notice", "Mock 合成测试结果，不代表真实模型质量。");
    switch (stage) {
      case "ANALYZE" -> analyze(input, result);
      case "PLAN" -> plan(input, result);
      case "FOLLOWUP" -> followup(input, result);
      case "REVIEW" -> review(input, result);
      default -> throw new IllegalArgumentException("未知测试阶段");
    }
    result
        .putObject("_modelUsage")
        .put("promptTokens", 0)
        .put("completionTokens", 0)
        .put("model", "EXPLICIT_MOCK_V3");
    return result;
  }

  private void analyze(ObjectNode input, ObjectNode result) {
    boolean agent = agent(input);
    String resume = input.path("resume").asText("");
    boolean realProject = input.path("experienceVersion").asInt() == 1
        && !input.path("mainProject").path("hypothetical").asBoolean()
        && !input.path("project").asText().isBlank();
    ObjectNode summary = result.putObject("summary");
    summary.putArray("technicalStack").add(agent ? "Mock：大模型应用 / Agent" : "Mock：Java 后端");
    ArrayNode responsibilities = summary.putArray("responsibilities");
    if (!resume.isBlank()) responsibilities.add(clip(resume, 240));
    else if (realProject) responsibilities.add(clip(input.path("project").asText(), 240));
    summary
        .putArray("clarifications")
        .add(resume.isBlank() && !realProject ? "未提供简历，本场为通用基准摸底。" : "Mock 仅摘录资料，不推断未写明的实现与成果。");
    summary.putArray("focus").add(agent ? "Mock：召回依据、工具权限与失败恢复" : "Mock：请求链路、事务与并发边界");
    if (!input.path("correction").asText().isBlank())
      summary
          .withArray("clarifications")
          .add("本场更正：" + clip(input.path("correction").asText(), 240));
    if (input.path("experienceVersion").asInt() == 1 && resume.contains("项目")) {
      result.putObject("mainProject").put("name", "简历中的主项目").put("brief", clip(resume, 1000));
    }
    ArrayNode topics = result.putArray("topics");
    for (String topic : topics(input)) topics.add(topic);
  }

  private void plan(ObjectNode input, ObjectNode result) {
    String resume = input.path("resume").asText("");
    String evidence = resume.isBlank() ? "通用岗位知识" : clip(resume, 160);
    String prefix = resume.isBlank() ? "Mock 测试：以通用场景为例，" : "Mock 测试：你写到“" + evidence + "”，";
    List<String> topics = topics(input);
    String[] questions =
        agent(input)
            ? new String[] {
              "请说明输入数据到回答的完整链路，以及你本人实现的部分？",
              "检索为空或证据冲突时，你如何决定回答与拒答？",
              "假设工具调用中断，你如何恢复并防止重复执行？",
              "你如何决定检索结果的相关性，并验证遗漏的情况？",
              "假设工具参数越权，你会在哪一层拒绝并记录原因？",
              "你用什么固定样本区分检索问题与生成问题？",
              "多轮对话中，哪些历史信息应保留，哪些需要重新核实？",
              "假设外部服务返回了与知识片段冲突的结果，你如何处理？"
            }
            : new String[] {
              "请说明请求到数据落库的完整链路，以及你本人实现的部分？",
              "出现重复请求与事务失败时，你如何保证状态正确？",
              "假设并发流量增加，你会先验证哪个瓶颈以及为什么？",
              "你如何划分参数校验、业务状态约束与数据库约束的职责？",
              "假设写入成功但响应超时，你如何确认最终状态？",
              "外部依赖故障时，你如何限定超时与恢复边界？",
              "你会记录哪些数据来定位一次失败请求，并避免泄露用户信息？",
              "假设需要更换一个组件，你会保留哪些行为作为回归依据？"
            };
    ArrayNode rows = result.putArray("questions");
    int total = input.path("totalQuestions").asInt(3);
    for (int i = 0; i < total; i++) {
      String topic = topics.get(i % topics.size());
      ObjectNode row =
          rows.addObject()
              .put("id", "q" + (i + 1))
              .put("topic", topic)
              .put("question", prefix + questions[i % questions.length])
              .put("evidence", evidence);
      ArrayNode sourceIds = sourceIds(input, topic);
      if (input.path("experienceVersion").asInt() == 1) {
        int projectEnd = 1 + Math.max(1, (total - 2) / 2);
        String stage = i == 0 ? "INTRO" : i == total - 1 ? "QUESTIONS" : i < projectEnd ? "PROJECT" : "TECHNICAL";
        row.put("stage", stage);
        if (stage.equals("INTRO")) row.put("question", "Mock 测试：请简要介绍与你目标岗位最相关的经历和个人职责？");
        if (stage.equals("PROJECT")) {
          String brief = input.path("mainProject").path("brief").asText();
          row.put("question", brief.isBlank() ? "Mock 测试：假设你负责一个订单查询系统，请说明业务、个人职责和调用链路？"
              : "Mock 测试：围绕你提供的项目“" + clip(brief, 180) + "”，" + questions[i % questions.length]);
        }
        if (stage.equals("QUESTIONS")) row.put("question", "Mock 测试：面试接近尾声，你对岗位的技术工作或工程实践有什么问题？没有也可以明确说明。");
      }
      row.set("sourceIds", sourceIds);
      ArrayNode backups = row.putArray("backups");
      for (String question : new String[] {"请说明其中一个失败分支的触发条件和处理结果？", "你会用什么可重复的步骤验证刚才的处理方式？"}) {
        ObjectNode backup =
            backups.addObject().put("question", "Mock 测试（" + topic + "）：" + question);
        backup.set("sourceIds", sourceIds.deepCopy());
      }
    }
  }

  private void followup(ObjectNode input, ObjectNode result) {
    if (input.path("experienceVersion").asInt() == 1 && input.path("stage").asText().equals("QUESTIONS")) {
      result.put("action", "NEXT").put("question", "").put("reply", "Mock 测试：感谢你的提问。一般可向招聘方确认技术职责与协作方式；具体公司内部信息无法获知。本场面试已结束。").putArray("sourceIds");
      return;
    }
    if (input.path("followUpCount").asInt() >= 2) result.put("action", "NEXT").put("question", "");
    else
      result
          .put("action", "FOLLOW_UP")
          .put(
              "question",
              "Mock 测试：你提到“" + clip(input.path("answer").asText(), 180) + "”，请说明一个失败边界与实际验证步骤？");
    result.putArray("sourceIds");
  }

  private void review(ObjectNode input, ObjectNode result) {
    result.put("summary", "Mock 合成复盘：仅核对已保存回答，不能据此判断真实技术能力。");
    ArrayNode findings = result.putArray("findings");
    Set<String> answered = new LinkedHashSet<>();
    for (JsonNode message : input.path("messages")) {
      if (message.path("role").asText().equals("USER")
          && !message.path("questionId").asText().isBlank())
        answered.add(message.path("questionId").asText());
    }
    for (String questionId : answered) {
      ObjectNode finding =
          findings
              .addObject()
              .put("questionId", questionId)
              .put("status", "INSUFFICIENT")
              .put("assessment", "Mock 测试已保存该题回答；没有进行真实模型或技术事实判断。")
              .put("sourceQuote", "")
              .put("suggestion", "请在真实模式下结合对应版本官方文档核对，并补充本人实际验证步骤。");
      finding.putArray("sourceIds");
      if (input.path("reviewVersion").asInt() == 2) {
        String answer = "";
        for (var m : input.path("messages")) if (m.path("role").asText().equals("USER") && m.path("questionId").asText().equals(questionId)) answer = m.path("content").asText();
        finding.put("answerStatus", "PARTIAL").put("answerQuote", answer.substring(0, Math.min(120, answer.length())))
            .put("headline", "合成观察：补充验证边界").put("observation", "Mock只验证内容与来源分离的展示，不代表真实内容判断。")
            .put("priority", 1).putArray("terms");
      }
    }
    if (input.path("assessmentVersion").asInt() == 1) {
      var detail = result.putObject("detailedAssessment").put("version", 1);
      detail.putObject("overall").put("text", "合成复盘：本场已保存回答；Mock只验证四部分展示，不能评价真实面试质量。")
          .putArray("questionIds");
      detail.putArray("strengths");
      var improvements = detail.putArray("improvements");
      var actions = detail.putArray("nextSteps");
      if (!result.path("findings").isEmpty()) {
        String qid = result.path("findings").get(0).path("questionId").asText();
        improvements.addObject().put("text", "合成内容观察：请结合完整原答检查验证边界；此文字不代表真实质量判断。")
            .putArray("questionIds").add(qid);
        actions.addObject().put("text", "合成行动：列出异常恢复步骤，整理为状态图，并通过一次重复请求测试核对结果。")
            .putArray("questionIds").add(qid);
      }
    }
    if (input.path("experienceVersion").asInt() == 1 && !input.path("mainProject").path("hypothetical").asBoolean()
        && !input.path("mainProject").path("brief").asText().isBlank()) {
      ArrayNode items = result.putObject("matrix").putArray("items");
      for (String checkpoint : List.of("BUSINESS", "RESPONSIBILITY", "ARCHITECTURE", "TECH_CHOICE", "FAILURE", "PERFORMANCE", "TRADEOFF")) {
        items.addObject().put("checkpointId", checkpoint).put("status", "UNCOVERED")
            .put("reason", "Mock仅验证矩阵格式，不作真实经历证据判定。").putArray("evidence");
      }
    }
  }

  private ArrayNode sourceIds(ObjectNode input, String topic) {
    ArrayNode result = json.createArrayNode();
    for (JsonNode source : input.path("sources")) {
      if (!source.path("id").asText().isBlank()) {
        for (JsonNode value : source.path("topics"))
          if (value.asText().equals(topic)) {
            result.add(source.path("id").asText());
            break;
          }
      }
      if (result.size() >= 3) break;
    }
    return result;
  }

  private List<String> topics(ObjectNode input) {
    return agent(input)
        ? List.of("AI_RAG", "AI_TOOLS", "AI_EVALUATION")
        : List.of("JAVA_CORE", "SPRING", "DATABASE");
  }

  private boolean agent(ObjectNode input) {
    String role = input.path("role").asText().toLowerCase(java.util.Locale.ROOT);
    return role.contains("agent") || role.contains("大模型") || role.contains("人工智能");
  }

  private String clip(String text, int limit) {
    return text.substring(0, Math.min(text.length(), limit));
  }
}
