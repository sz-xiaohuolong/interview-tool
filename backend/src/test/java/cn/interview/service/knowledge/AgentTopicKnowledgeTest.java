package cn.interview.service.knowledge;

import cn.interview.client.transport.KnowledgeTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class AgentTopicKnowledgeTest {
  @TempDir Path directory;

  @Test void 混合岗位的六个检索名额交替覆盖普通和Agent主题() {
    var topics = List.of("JAVA_CORE", "DATABASE", "REDIS", "NETWORK", "AGENT_ARCHITECTURE",
        "AGENT_CONTEXT_MEMORY", "AGENT_RAG", "AGENT_EVALUATION");
    var sources = new ArrayList<KnowledgeService.Source>();
    for (int i = 0; i < topics.size(); i++) sources.add(new KnowledgeService.Source(
        "fixture-" + i, "合成来源", "https://example.test/" + i, "合成", "OFFICIAL",
        List.of(topics.get(i)), "合成夹具", "仅供测试"));
    var knowledge = new KnowledgeService(new ObjectMapper(), directory, sources,
        (uri, timeout, max) -> new KnowledgeTransport.Payload(200, "text/plain",
            "Agent architecture context retrieval engineering".getBytes(StandardCharsets.UTF_8)),
        Clock.systemUTC(), true);
    try {
      var selected = knowledge.retrieve(topics, false);
      assertThat(selected).hasSize(6);
      assertThat(selected.findValuesAsText("id")).anyMatch(id -> id.startsWith("fixture-6:"));
      assertThat(selected.findValuesAsText("id")).noneMatch(id -> id.startsWith("fixture-3:"));
    } finally { knowledge.close(); }
  }

  @Test void 实际选定考点的参考资料优先于目录首篇() {
    var sources = new ArrayList<KnowledgeService.Source>();
    for (int i = 0; i < 8; i++) sources.add(new KnowledgeService.Source(
        "topic-source-" + i, "合成主题资料", "https://example.test/" + i, "合成", "OFFICIAL",
        List.of("AGENT_CONTEXT_MEMORY"), "合成夹具", "仅供测试"));
    var knowledge = new KnowledgeService(new ObjectMapper(), directory, sources,
        (uri, timeout, max) -> new KnowledgeTransport.Payload(200, "text/plain",
            "Context engineering memory isolation".getBytes(StandardCharsets.UTF_8)), Clock.systemUTC(), true);
    try {
      var selected = knowledge.retrieve(List.of("AGENT_CONTEXT_MEMORY"), false, List.of("topic-source-7"));
      assertThat(selected.get(0).path("id").asText()).startsWith("topic-source-7:");
      assertThat(selected).hasSize(6);
    } finally { knowledge.close(); }
  }

  @Test void 升级主题映射后复用原正文缓存但使用当前受控分类() {
    var json = new ObjectMapper();
    var before = new KnowledgeService.Source("same-memory-doc", "记忆资料", "https://example.test/memory",
        "合成", "OFFICIAL", List.of("AI_MEMORY"), "合成", "仅供测试");
    var after = new KnowledgeService.Source("same-memory-doc", "记忆资料", "https://example.test/memory",
        "合成", "OFFICIAL", List.of("AI_MEMORY", "AGENT_CONTEXT_MEMORY"), "合成", "仅供测试");
    var initial = new KnowledgeService(json, directory, List.of(before),
        (uri, timeout, max) -> new KnowledgeTransport.Payload(200, "text/plain",
            "Context memory isolation".getBytes(StandardCharsets.UTF_8)), Clock.systemUTC(), true);
    String bodyHash;
    try { bodyHash = initial.retrieve(List.of("AI_MEMORY"), false).get(0).path("sha256").asText(); }
    finally { initial.close(); }
    var upgraded = new KnowledgeService(json, directory, List.of(after),
        (uri, timeout, max) -> { throw new AssertionError("复用有效缓存不应联网"); }, Clock.systemUTC(), false);
    try {
      var source = upgraded.retrieve(List.of("AGENT_CONTEXT_MEMORY"), false).get(0);
      assertThat(source.path("topics")).contains(json.getNodeFactory().textNode("AGENT_CONTEXT_MEMORY"));
      assertThat(source.path("sha256").asText()).isEqualTo(bodyHash);
    } finally { upgraded.close(); }
  }
}
