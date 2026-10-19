package cn.interview.service;

import cn.interview.client.FixedHttpTransport;
import static org.assertj.core.api.Assertions.*;

import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class XiaolinKnowledgeTest {
  @TempDir Path directory;
  private final ObjectMapper json = new ObjectMapper();

  private List<KnowledgeService.Source> catalogue() throws Exception {
    try (var input = getClass().getResourceAsStream("/knowledge/sources.json")) {
      return json.readValue(input, json.getTypeFactory().constructCollectionType(List.class, KnowledgeService.Source.class));
    }
  }

  @Test void catalogueKeepsXiaolinAsAttributedInterviewHintsAndUsesKnownTopics() throws Exception {
    var sources = catalogue().stream().filter(s -> URI.create(s.url()).getHost().equals("xiaolincoding.com")).toList();
    assertThat(sources).hasSize(3);
    for (var source : sources) {
      assertThat(source.kind()).isEqualTo("INTERVIEW");
      assertThat(source.attribution()).contains("小林");
      assertThat(source.license()).isNotBlank();
      assertThat(source.version()).contains("哈希");
      assertThat(source.url()).endsWith(".html");
      assertThat(source.topics()).isNotEmpty().allMatch(InterviewOutputValidator.TOPICS::contains);
    }
  }

  @Test void permitsExactPublicHostWithoutWeakeningUrlRestrictions() {
    assertThatCode(() -> FixedHttpTransport.validatePublicUrl(URI.create("https://xiaolincoding.com/mysql/index/index_interview.html"))).doesNotThrowAnyException();
    for (String invalid : List.of(
        "http://xiaolincoding.com/mysql/", "https://www.xiaolincoding.com/mysql/",
        "https://xiaolincoding.com.evil.test/mysql/", "https://xiaolincoding.com@127.0.0.1/mysql/",
        "https://xiaolincoding.com:8443/mysql/", "https://xiaolincoding.com/mysql/?private=value",
        "https://xiaolincoding.com/mysql/#fragment")) {
      assertThatThrownBy(() -> FixedHttpTransport.validatePublicUrl(URI.create(invalid))).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @CsvSource({"DATABASE,xiaolin-mysql-index", "REDIS,xiaolin-redis-data-structures", "NETWORK,xiaolin-tcp", "MIXED_NETWORK,xiaolin-tcp"})
  void productionCatalogueActuallyPrefetchesAndSelectsConfiguredArticle(String topic, String expectedId) throws Exception {
    Set<String> requested = ConcurrentHashMap.newKeySet();
    var service = new KnowledgeService(json, directory, catalogue(), (uri, timeout, maximum) -> {
      requested.add(uri.toString());
      String html = "<html><nav>合成导航</nav><script>合成脚本</script><main class='page'><h1>合成技术正文</h1><p>MySQL B+树索引、Redis 数据结构与 TCP 连接边界。</p></main></html>";
      return new cn.interview.client.KnowledgeTransport.Payload(200, "text/html", html.getBytes(StandardCharsets.UTF_8));
    }, Clock.systemUTC(), true);
    try {
      var topics = switch (topic) {
        case "REDIS" -> List.of("REDIS", "DISTRIBUTED");
        case "MIXED_NETWORK" -> List.of("JAVA_CORE", "SPRING", "DATABASE", "REDIS", "NETWORK");
        default -> List.of(topic);
      };
      var sources = service.retrieve(topics, false);
      assertThat(sources).hasSizeLessThanOrEqualTo(6);
      var source = java.util.stream.StreamSupport.stream(sources.spliterator(), false)
          .filter(s -> s.path("id").asText().startsWith(expectedId + ":")).findFirst().orElseThrow();
      assertThat(requested).contains(source.path("url").asText());
      assertThat(source.path("kind").asText()).isEqualTo("INTERVIEW");
      assertThat(source.path("sha256").asText()).matches("[a-f0-9]{64}");
      assertThat(source.path("excerpt").asText()).contains("合成技术正文").doesNotContain("合成导航", "合成脚本");
      assertThat(source.has("rawText")).isFalse();
      assertThat(service.select(sources, "B+树 Redis TCP", 6)).anyMatch(s -> s.path("id").asText().startsWith(expectedId + ":"));
      if (topic.equals("REDIS")) {
        assertThat(sources).anyMatch(s -> s.path("id").asText().startsWith("microsoft-cache-aside:"));
        assertThat(sources).anyMatch(s -> s.path("id").asText().startsWith("redis-distributed-locks:"));
        assertThat(sources).anyMatch(s -> s.path("id").asText().startsWith("javaguide-redis-questions:"));
      }
    } finally { service.close(); }
  }
}
