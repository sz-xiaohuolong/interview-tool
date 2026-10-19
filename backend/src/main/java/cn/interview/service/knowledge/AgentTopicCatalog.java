package cn.interview.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本地考点目录只保存考查方向与参考边界，不将社区建议当作技术事实认证。 */
public final class AgentTopicCatalog {
  public static final List<String> THEME_IDS = List.of(
      "AGENT_ARCHITECTURE", "AGENT_CONTEXT_MEMORY", "AGENT_HARNESS_TOOL",
      "AGENT_RAG", "AGENT_EVALUATION", "AGENT_FRAMEWORK");
  private final ObjectMapper json;
  private final ObjectNode document;
  private final Map<String, ObjectNode> points = new LinkedHashMap<>();

  public AgentTopicCatalog(ObjectMapper json) {
    this(json, load(json));
  }

  public AgentTopicCatalog(ObjectMapper json, JsonNode document) {
    this.json = json;
    try {
      if (!document.isObject() || !document.path("schemaVersion").isIntegralNumber()
          || document.path("schemaVersion").asInt() != 1) throw invalid();
      this.document = ((ObjectNode) document).deepCopy();
      var themes = new LinkedHashSet<String>();
      for (var theme : array(document, "themes")) {
        if (!theme.isObject() || !themes.add(text(theme, "id"))) throw invalid();
        text(theme, "label");
      }
      if (!themes.equals(new LinkedHashSet<>(THEME_IDS))) throw invalid();
      var references = new LinkedHashSet<String>();
      for (var reference : array(document, "references")) {
        if (!reference.isObject() || !references.add(text(reference, "id"))) throw invalid();
        for (String key : List.of("title", "kind", "scope")) text(reference, key);
        url(text(reference, "url"));
      }
      for (var point : array(document, "points")) {
        if (!point.isObject()) throw invalid();
        String id = text(point, "id");
        if (points.containsKey(id) || !themes.contains(text(point, "theme"))) throw invalid();
        for (String key : List.of("title", "capability", "scenario", "evaluationBoundary")) text(point, key);
        var directions = strings(point, "followupDirections");
        var referenceIds = strings(point, "referenceIds");
        if (directions.isEmpty() || referenceIds.isEmpty() || !references.containsAll(referenceIds)) throw invalid();
        for (String discovery : strings(point, "discoveryReferences")) url(discovery);
        points.put(id, ((ObjectNode) point).deepCopy());
      }
      if (points.isEmpty()) throw invalid();
    } catch (IllegalStateException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new IllegalStateException("Agent考点目录格式无效，请检查本地目录资源", error);
    }
  }

  private static JsonNode load(ObjectMapper json) {
    try (var stream = AgentTopicCatalog.class.getResourceAsStream("/knowledge/agent-interview-topics.json")) {
      if (stream == null) throw invalid();
      return json.readTree(stream);
    } catch (Exception error) {
      throw new IllegalStateException("Agent考点目录无法读取，请检查本地资源", error);
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Agent考点目录格式无效，请检查主题、考点与参考关系");
  }

  private static JsonNode array(JsonNode value, String field) {
    var array = value.path(field);
    if (!array.isArray()) throw invalid();
    return array;
  }

  private static String text(JsonNode value, String field) {
    if (!value.path(field).isTextual() || value.path(field).asText().isBlank()) throw invalid();
    return value.path(field).asText();
  }

  private static List<String> strings(JsonNode value, String field) {
    var values = new ArrayList<String>();
    for (var item : array(value, field)) {
      if (!item.isTextual() || item.asText().isBlank()) throw invalid();
      values.add(item.asText());
    }
    return values;
  }

  private static void url(String value) {
    var uri = URI.create(value);
    if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
      throw invalid();
  }

  public List<ObjectNode> points() {
    return points.values().stream().map(ObjectNode::deepCopy).toList();
  }

  public ObjectNode point(String id) {
    var point = points.get(id);
    return point == null ? null : point.deepCopy();
  }

  /** 配置页只需要主题选项和数量，不公开待考题目或内部考查边界。 */
  public ObjectNode publicView() {
    var view = json.createObjectNode().put("schemaVersion", 1);
    var themes = view.putArray("themes");
    for (var theme : document.path("themes")) {
      var row = ((ObjectNode) theme).deepCopy();
      row.put("pointCount", points.values().stream().filter(point -> point.path("theme").equals(theme.path("id"))).count());
      themes.add(row);
    }
    return view;
  }
}
