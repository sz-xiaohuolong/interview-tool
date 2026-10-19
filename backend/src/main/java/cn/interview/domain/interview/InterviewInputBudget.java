package cn.interview.domain.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 为单次模型调用节选长正文；保留本场完整原文以及引用标识。 */
public final class InterviewInputBudget {
  private InterviewInputBudget() {}

  private static final Set<String> LONG_FIELDS =
      Set.of("resume", "jd", "correction", "content", "excerpt", "answer");

  private record Field(ObjectNode owner, String key, int length) {}

  public static ObjectNode bound(ObjectNode original, ObjectMapper json) {
    ObjectNode out = original.deepCopy();
    boolean truncated = false;
    while (out.toString().length() >= 94_000) {
      List<Field> fields = new ArrayList<>();
      collect(out, fields);
      Field longest =
          fields.stream()
              .filter(f -> f.length() > 500)
              .max(Comparator.comparingInt(Field::length))
              .orElse(null);
      if (longest == null) throw new IllegalArgumentException("结构化输入超过本场处理预算");
      String value = longest.owner().path(longest.key()).asText();
      int keep = Math.max(400, value.length() * 2 / 3), head = keep * 3 / 4;
      longest
          .owner()
          .put(
              longest.key(),
              value.substring(0, head)
                  + "\n【中段按输入预算省略；原文保留在本场记录】\n"
                  + value.substring(value.length() - (keep - head)));
      truncated = true;
    }
    if (truncated)
      out.put("inputTruncated", true).put("inputCoverageNote", "部分正文已节选，未覆盖细节不能作为判错依据。不得补造被省略内容。");
    return out;
  }

  private static void collect(JsonNode node, List<Field> fields) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      object
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().isTextual() && LONG_FIELDS.contains(e.getKey()))
                  fields.add(new Field(object, e.getKey(), e.getValue().asText().length()));
                else collect(e.getValue(), fields);
              });
    } else if (node.isArray()) node.forEach(child -> collect(child, fields));
  }
}
