package cn.interview.v3.model;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/** 模型边界检查；业务版本、问题状态与预算仍由会话服务约束。 */
final class V3OutputChecks {
  static final Set<String> TOPICS =
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

  private V3OutputChecks() {}

  static void validate(String stage, ObjectNode input, ObjectNode result) {
    Set<String> sources = new HashSet<>();
    for (JsonNode source : input.path("sources")) sources.add(source.path("id").asText());
    references(result, sources);
    switch (stage) {
      case "ANALYZE" -> {
        JsonNode summary = result.path("summary");
        if (!summary.isObject()) invalid();
        for (String key :
            new String[] {"technicalStack", "responsibilities", "clarifications", "focus"})
          strings(summary.path(key));
        strings(result.path("topics"));
        for (JsonNode topic : result.path("topics"))
          if (!TOPICS.contains(topic.asText())) invalid();
      }
      case "PLAN" -> {
        JsonNode questions = result.path("questions");
        if (!questions.isArray() || questions.size() != input.path("totalQuestions").asInt(-1))
          invalid();
        Set<String> ids = new HashSet<>();
        for (JsonNode question : questions) {
          text(question, "id");
          text(question, "topic");
          text(question, "question");
          text(question, "evidence");
          if (!ids.add(question.path("id").asText())) invalid();
          strings(question.path("sourceIds"));
          if (!question.path("backups").isArray() || question.path("backups").size() > 2) invalid();
          for (JsonNode backup : question.path("backups")) {
            text(backup, "question");
            strings(backup.path("sourceIds"));
          }
        }
      }
      case "FOLLOWUP" -> {
        String action = result.path("action").asText();
        if (!Set.of("FOLLOW_UP", "NEXT").contains(action)) invalid();
        if (action.equals("FOLLOW_UP")) {
          text(result, "question");
          if (input.path("followUpCount").asInt() >= 2) invalid();
        }
        strings(result.path("sourceIds"));
      }
      case "REVIEW" -> {
        text(result, "summary");
        if (!result.path("findings").isArray()) invalid();
        Set<String> answered = new HashSet<>();
        for (JsonNode message : input.path("messages")) {
          if (message.path("role").asText().equals("USER"))
            answered.add(message.path("questionId").asText());
        }
        for (JsonNode finding : result.path("findings")) {
          text(finding, "questionId");
          text(finding, "assessment");
          text(finding, "suggestion");
          if (!answered.contains(finding.path("questionId").asText())) invalid();
          if (!Set.of("SUPPORTED", "INSUFFICIENT", "CONFLICTING")
              .contains(finding.path("status").asText())) invalid();
          strings(finding.path("sourceIds"));
          if (finding.path("sourceIds").isEmpty())
            ((ObjectNode) finding).put("status", "INSUFFICIENT");
        }
      }
      default -> invalid();
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
                    if (!allowed.contains(id.asText())) invalid();
                } else references(entry.getValue(), allowed);
              });
    } else if (node.isArray()) for (JsonNode child : node) references(child, allowed);
  }

  private static void strings(JsonNode node) {
    if (!node.isArray() || node.size() > 32) invalid();
    for (JsonNode value : node)
      if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 3000)
        invalid();
  }

  private static void text(JsonNode node, String key) {
    if (!node.path(key).isTextual()
        || node.path(key).asText().isBlank()
        || node.path(key).asText().length() > 6000) invalid();
  }

  private static void invalid() {
    throw new ApiException(502, "MODEL_INVALID_OUTPUT", "模型输出不符合本阶段结构或引用边界，没有保存为有效结果。");
  }
}
