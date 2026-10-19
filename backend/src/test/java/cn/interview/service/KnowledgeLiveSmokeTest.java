package cn.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用时访问公开固定来源，不发送个人资料，也不调用付费模型。 */
@EnabledIfEnvironmentVariable(named = "KNOWLEDGE_LIVE_TESTS", matches = "true")
class KnowledgeLiveSmokeTest {
    @TempDir Path directory;

    @Test void retrievesRealPublicDocumentsThroughProductionTransport() {
        var service = new KnowledgeService(new ObjectMapper(), directory.toString(), true);
        try {
            for (var topics : List.of(List.of("JAVA_CORE", "JAVA_CONCURRENCY", "JVM"),
                    List.of("SPRING", "DATABASE", "REDIS"),
                    List.of("AI_RAG", "AI_TOOLS", "AI_MEMORY", "AI_EVALUATION"))) {
                long started = System.nanoTime();
                var sources = service.retrieve(topics, true);
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                System.out.println("公开来源实测：主题=" + topics + "，可用数量=" + sources.size() + "，耗时毫秒=" + elapsed + "，缺口=" + service.warnings());
                assertThat(sources).isNotEmpty();
                assertThat(sources).anyMatch(source -> source.path("kind").asText().equals("OFFICIAL"));
                for (var source : sources) {
                    assertThat(source.path("url").asText()).startsWith("https://");
                    assertThat(source.path("sha256").asText()).matches("[a-f0-9]{64}");
                    assertThat(source.path("id").asText()).matches("[a-z0-9-]+:[a-f0-9]{12}");
                    assertThat(source.path("excerpt").asText()).isNotBlank();
                    assertThat(source.path("stale").asBoolean()).isFalse();
                    assertThat(source.has("rawText")).isFalse();
                }
            }
        } finally { service.close(); }
    }

    @Test void cacheAsideAndDistributedLockSourcesSupplyRealTechnicalPassages() {
        var service = new KnowledgeService(new ObjectMapper(), directory.toString(), true);
        try {
            long started = System.nanoTime();
            var sources = service.retrieve(List.of("REDIS", "DISTRIBUTED"), true);
            assertThat(sources).hasSizeLessThanOrEqualTo(6);
            assertThat(sources).anyMatch(source -> source.path("id").asText().startsWith("microsoft-cache-aside:"));
            assertThat(sources).anyMatch(source -> source.path("id").asText().startsWith("javaguide-redis-cache:"));
            assertThat(sources).anyMatch(source -> source.path("id").asText().startsWith("javaguide-redis-questions:"));
            assertThat(sources).anyMatch(source -> source.path("id").asText().startsWith("redis-distributed-locks:"));
            var selected = service.select(sources, "缓存一致性 数据库更新 缓存失效", 6);
            com.fasterxml.jackson.databind.JsonNode cacheAside = null;
            for (var source : selected) if (source.path("id").asText().startsWith("microsoft-cache-aside:")) cacheAside = source;
            assertThat(cacheAside).isNotNull();
            assertThat(cacheAside.path("kind").asText()).isEqualTo("OFFICIAL");
            assertThat(cacheAside.path("excerpt").asText()).contains("Cache-Aside", "data store").doesNotContain("YamlMime", "Microsoft Learn navigation");
            int cacheAsideIndex = -1, clientSideIndex = -1;
            for (int index = 0; index < sources.size(); index++) {
                if (sources.get(index).path("id").asText().startsWith("microsoft-cache-aside:")) cacheAsideIndex = index;
                if (sources.get(index).path("id").asText().startsWith("redis-client-cache:")) clientSideIndex = index;
            }
            if (clientSideIndex >= 0) assertThat(cacheAsideIndex).isLessThan(clientSideIndex);
            System.out.println("服务端缓存知识实测：可用数量=" + sources.size() + "，耗时毫秒=" + (System.nanoTime() - started) / 1_000_000 + "，中文检索命中官方Cache-Aside正文，缺口=" + service.warnings());
        } finally { service.close(); }
    }
}
