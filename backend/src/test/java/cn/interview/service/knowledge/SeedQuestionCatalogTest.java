package cn.interview.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SeedQuestionCatalogTest {
  final ObjectMapper json = new ObjectMapper();

  @Test void 保留六十考点并验证独立题单身份原题链接难度与映射() throws Exception {
    var points = new AgentTopicCatalog(json);
    assertThat(points.points()).hasSize(60);
    try (var stream = getClass().getResourceAsStream("/knowledge/questions/javabetter-agent.json")) {
      assertThat(stream).as("独立题目素材目录").isNotNull();
      var document = json.readTree(stream);
      var ids = new HashSet<String>(); var urls = new HashSet<String>();
      assertThat(document.path("questions").size()).isBetween(50, 100);
      for (var q : document.path("questions")) {
        assertThat(ids.add(q.path("id").asText())).isTrue();
        assertThat(urls.add(q.path("url").asText())).isTrue();
        assertThat(q.path("url").asText()).startsWith("https://javabetter.cn/ai/video/");
        assertThat(q.path("difficulty").asText()).isIn("EASY", "MEDIUM", "HARD");
        assertThat(q.path("title").asText()).isNotBlank();
        assertThat(q.has("answer")).isFalse();
        assertThat(q.path("pointIds")).isNotEmpty();
        for (var id : q.path("pointIds")) assertThat(points.point(id.asText())).isNotNull();
      }
    }
  }
}
