package cn.interview.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 独立原题索引只提供出题素材，不承担技术答案或能力目录职责。 */
public final class SeedQuestionCatalog {
  private final List<ObjectNode> questions = new ArrayList<>();

  public SeedQuestionCatalog(ObjectMapper json) {
    try (var input = getClass().getResourceAsStream("/knowledge/questions/javabetter-agent.json")) {
      var root = json.readTree(input);
      var catalog = new AgentTopicCatalog(json);
      if (root.path("schemaVersion").asInt() != 1 || !root.path("questions").isArray()) throw new IllegalArgumentException();
      var ids = new HashSet<String>();
      var urls = new HashSet<String>();
      for (var node : root.path("questions")) {
        for (String key : List.of("id", "title", "originalTitle", "url", "difficulty", "source", "verifiedAt"))
          if (!node.path(key).isTextual() || node.path(key).asText().isBlank()) throw new IllegalArgumentException();
        var uri = URI.create(node.path("url").asText());
        if (!ids.add(node.path("id").asText()) || !urls.add(uri.toString()) || !"https".equals(uri.getScheme())
            || !"javabetter.cn".equals(uri.getHost()) || !uri.getPath().startsWith("/ai/video/")
            || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null
            || !Set.of("EASY", "MEDIUM", "HARD").contains(node.path("difficulty").asText())
            || !node.path("pointIds").isArray() || node.path("pointIds").isEmpty() || node.has("answer")) throw new IllegalArgumentException();
        for (var point : node.path("pointIds")) if (catalog.point(point.asText()) == null) throw new IllegalArgumentException();
        questions.add(((ObjectNode) node).deepCopy());
      }
      if (questions.size() < 50 || questions.size() > 100) throw new IllegalArgumentException();
    } catch (Exception error) {
      throw new IllegalStateException("独立题目素材目录无效，请检查题目身份、考点映射、难度与原题链接", error);
    }
  }

  public ObjectNode choose(String pointId, String level, Set<String> used) {
    int target = switch (level) { case "SENIOR" -> 2; case "INTERN" -> 0; default -> 1; };
    return questions.stream().filter(q -> !used.contains(q.path("id").asText()))
        .filter(q -> { for (var id : q.path("pointIds")) if (id.asText().equals(pointId)) return true; return false; })
        .min(Comparator.comparingInt(q -> Math.abs(List.of("EASY", "MEDIUM", "HARD").indexOf(q.path("difficulty").asText()) - target)))
        .map(ObjectNode::deepCopy).orElse(null);
  }

  public ObjectNode question(String id) {
    return questions.stream().filter(q -> q.path("id").asText().equals(id)).findFirst().map(ObjectNode::deepCopy).orElse(null);
  }
}
