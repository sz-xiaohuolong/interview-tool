package cn.interview.v3.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 显式访问固定公开文章，使用生产抓取器；不创建面试或调用模型。 */
@EnabledIfEnvironmentVariable(named = "V3_LIVE_KNOWLEDGE", matches = "true")
class XiaolinKnowledgeLiveTest {
  @TempDir Path directory;

  @ParameterizedTest
  @CsvSource({"DATABASE,xiaolin-mysql-index,索引", "REDIS,xiaolin-redis-data-structures,数据结构", "NETWORK,xiaolin-tcp,TCP"})
  void productionTransportFetchesArticleBodyAndMakesItSelectable(String topic, String expectedId, String keyword) {
    var service = new KnowledgeService(new ObjectMapper(), directory.toString(), true);
    long started = System.nanoTime();
    try {
      var sources = service.retrieve(topic.equals("REDIS") ? List.of("REDIS", "DISTRIBUTED") : List.of(topic), true);
      assertThat(sources).as("预期来源 %s 未获取；检索提示：%s", expectedId, service.warnings())
          .anyMatch(s -> s.path("id").asText().startsWith(expectedId + ":"));
      var source = java.util.stream.StreamSupport.stream(sources.spliterator(), false)
          .filter(s -> s.path("id").asText().startsWith(expectedId + ":")).findFirst().orElseThrow();
      assertThat(source.path("kind").asText()).isEqualTo("INTERVIEW");
      assertThat(source.path("url").asText()).startsWith("https://xiaolincoding.com/").endsWith(".html");
      assertThat(source.path("sha256").asText()).matches("[a-f0-9]{64}");
      assertThat(source.path("stale").asBoolean()).isFalse();
      assertThat(source.path("excerpt").asText()).contains(keyword).doesNotContain("<script", "<nav");
      assertThat(source.has("rawText")).isFalse();
      assertThat(service.select(sources, keyword, 6)).anyMatch(s -> s.path("id").asText().startsWith(expectedId + ":"));
      System.out.println("小林公开来源实测：主题=" + topic + "，来源=" + expectedId + "，耗时毫秒=" + (System.nanoTime() - started) / 1_000_000 + "，正文与片段检索通过");
    } finally { service.close(); }
  }
}
