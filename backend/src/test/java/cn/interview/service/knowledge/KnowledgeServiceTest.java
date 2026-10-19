package cn.interview.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> content = new AtomicReference<>("<html><nav>无关导航</nav><script>泄漏脚本</script><main><h1>Redis 缓存</h1><p>Redis cache invalidation handles stale entries.</p><p>缓存一致性需要考虑失效窗口和数据库更新。</p></main></html>");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private HttpServer server;
    private final MutableClock clock = new MutableClock();

    @BeforeEach void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cache", exchange -> {
            calls.incrementAndGet();
            byte[] body = content.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stopFixture() { server.stop(0); }

    private KnowledgeService service(boolean enabled) {
        return new KnowledgeService(mapper, directory,
                List.of(new KnowledgeService.Source("redis", "Redis 缓存官方说明", "https://docs.example.test/cache",
                        "测试版", "OFFICIAL", List.of("REDIS"), "测试资料", "测试许可")),
                (uri, timeout, maximum) -> {
                    requestedUri.set(uri.toString());
                    var response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + uri.getPath())).timeout(timeout).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    return new cn.interview.client.transport.KnowledgeTransport.Payload(response.statusCode(), response.headers().firstValue("content-type").orElse(""), response.body());
                }, clock, enabled);
    }

    @Test void fetchesOnlyFixedUrlAndRemovesExecutableAndNavigationText() {
        var result = service(true).retrieve(List.of("REDIS"), false);
        assertThat(result).hasSize(1);
        assertThat(requestedUri.get()).isEqualTo("https://docs.example.test/cache");
        assertThat(result.get(0).path("excerpt").asText()).contains("Redis cache invalidation").doesNotContain("无关导航", "泄漏脚本");
        assertThat(result.get(0).path("sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(result.get(0).path("fetchedAt").asText()).isEqualTo("2026-10-17T00:00:00Z");
        assertThat(result.get(0).has("rawText")).isFalse();
    }

    @Test void cachesFreshSourceAndRefreshReallyFetchesNewContent() {
        var service = service(true);
        var first = service.retrieve(List.of("REDIS"), false);
        assertThat(first).hasSize(1);
        content.set("<main>Redis 缓存新版本：一致性使用显式失效机制。</main>");
        assertThat(service.retrieve(List.of("REDIS"), false).get(0).path("sha256")).isEqualTo(first.get(0).path("sha256"));
        assertThat(calls).hasValue(1);
        var refreshed = service.retrieve(List.of("REDIS"), true);
        assertThat(refreshed.get(0).path("sha256")).isNotEqualTo(first.get(0).path("sha256"));
        assertThat(calls).hasValue(2);
    }

    @Test void expiredCacheRemainsUsableButClearlyStaleWhenNetworkFails() {
        var service = service(true);
        service.retrieve(List.of("REDIS"), false);
        clock.instant = clock.instant.plusSeconds(49 * 3600);
        status.set(503);
        var result = service.retrieve(List.of("REDIS"), false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("stale").asBoolean()).isTrue();
        assertThat(result.get(0).path("warning").asText()).contains("过期");
        assertThat(calls).hasValue(2);
    }

    @Test void unknownTopicsNeverBecomeNetworkQueriesAndOfflineModeNeverInventsSources() {
        var service = service(true);
        assertThat(service.retrieve(List.of("某公司内部项目 Redis"), false)).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(service(false).retrieve(List.of("REDIS"), false)).isEmpty();
        service.retrieve(List.of("REDIS"), false);
        assertThat(service(false).retrieve(List.of("REDIS"), false)).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    @Test void selectsRelevantPassageAndDoesNotTreatUnrelatedSourceAsEvidence() {
        ArrayNode sources = mapper.createArrayNode();
        sources.addObject().put("id", "redis").put("title", "Redis 缓存").put("excerpt", "Redis cache invalidation must consider stale entries. 缓存一致性取决于更新与失效顺序。").putArray("topics").add("REDIS");
        sources.addObject().put("id", "jvm").put("title", "JVM 垃圾回收").put("excerpt", "Garbage collection reclaims unused heap memory.").putArray("topics").add("JVM");
        var service = service(false);
        var selected = service.select(sources, "Redis 缓存一致性", 2);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).path("id").asText()).isEqualTo("redis");
        assertThat(service.select(sources, "数据库事务隔离", 2)).isEmpty();
        assertThat(service.select(sources, "Redis", 0)).isEmpty();
    }

    @Test void rejectsRedirectsOversizedBodiesAndEmptyHtmlRatherThanRecordingFalseEvidence() {
        status.set(302);
        assertThat(service(true).retrieve(List.of("REDIS"), true)).isEmpty();
        status.set(200);
        content.set("x".repeat(2 * 1024 * 1024 + 1));
        assertThat(service(true).retrieve(List.of("REDIS"), true)).isEmpty();
        content.set("<html><nav>只有导航</nav><script>脚本</script></html>");
        assertThat(service(true).retrieve(List.of("REDIS"), true)).isEmpty();
    }

    @Test void failedSourceProvidesWarningInsteadOfPretendingVerificationSucceeded() {
        status.set(503);
        var service = service(true);
        assertThat(service.retrieve(List.of("REDIS"), false)).isEmpty();
        assertThat(service.warnings()).anyMatch(warning -> warning.contains("来源未获取"));
    }

    @Test void mapsChineseTechnicalConceptsToEnglishOfficialPassagesWithoutSubstringFalseMatches() {
        ArrayNode sources = mapper.createArrayNode();
        sources.addObject().put("id", "mysql").put("excerpt", "Transaction isolation controls the visibility of changes made by concurrent transactions.");
        sources.addObject().put("id", "irrelevant").put("excerpt", "Fragment processing reads a document section.");
        var service = service(false);
        assertThat(service.select(sources, "数据库事务隔离", 2)).hasSize(1);
        assertThat(service.select(sources, "RAG", 2)).isEmpty();
    }

    @Test void mixedRolePrefetchDoesNotLetJavaCatalogueOrderingStarveAgentSources() {
        List<KnowledgeService.Source> catalogue = new java.util.ArrayList<>();
        for (int index = 0; index < 7; index++) catalogue.add(new KnowledgeService.Source("java-" + index, "Java 考点", "https://docs.example.test/cache", "测试版", "OFFICIAL", List.of("JAVA_CORE"), "测试", "测试"));
        catalogue.add(new KnowledgeService.Source("agent", "Agent 工具调用", "https://docs.example.test/cache", "测试版", "OFFICIAL", List.of("AI_TOOLS"), "测试", "测试"));
        var service = new KnowledgeService(mapper, directory, catalogue,
                (uri, timeout, maximum) -> new cn.interview.client.transport.KnowledgeTransport.Payload(200, "text/plain", "Java 与 Agent tool 技术说明".getBytes(StandardCharsets.UTF_8)), clock, true);
        var result = service.retrieve(List.of("JAVA_CORE", "AI_TOOLS"), false);
        assertThat(result).hasSizeLessThanOrEqualTo(6);
        assertThat(result).anyMatch(source -> source.path("id").asText().startsWith("agent"));
    }

    @Test void changingSourceBodyCreatesNewCitationIdAndKeepsOldSnapshotSelectable() {
        var service = service(true);
        var first = service.retrieve(List.of("REDIS"), false);
        assertThat(first).hasSize(1);
        content.set("<main>Redis 新资料：缓存双写引入额外失败窗口。</main>");
        var changed = service.retrieve(List.of("REDIS"), true);
        assertThat(changed.get(0).path("id")).isNotEqualTo(first.get(0).path("id"));
        assertThat(changed.get(0).path("id").asText()).matches("redis:[a-f0-9]{12}");
        assertThat(service.select(first, "Redis", 1).get(0).path("excerpt").asText()).contains("Redis cache invalidation").doesNotContain("新资料");
    }

    @Test void unchangedRefreshKeepsCitationIdButRenewsFetchedTime() {
        var service = service(true);
        var first = service.retrieve(List.of("REDIS"), false);
        clock.instant = clock.instant.plusSeconds(60);
        var refreshed = service.retrieve(List.of("REDIS"), true);
        assertThat(refreshed.get(0).path("id")).isEqualTo(first.get(0).path("id"));
        assertThat(refreshed.get(0).path("fetchedAt").asText()).isEqualTo("2026-10-17T00:01:00Z");
    }

    @Test void mergesEveryModelSeenPassageWithoutLosingOlderCitationVersions() {
        ArrayNode prepared = mapper.createArrayNode();
        prepared.addObject().put("id", "redis:111111111111").put("fetchedAt", "2026-10-17T00:00:00Z").put("excerpt", "准备阶段实际看到的缓存背景。").put("stale", true);
        ArrayNode followup = mapper.createArrayNode();
        followup.addObject().put("id", "redis:111111111111").put("fetchedAt", "2026-10-17T00:01:00Z").put("excerpt", "追问阶段实际看到的更新失败窗口。").put("stale", false);
        followup.addObject().put("id", "redis:222222222222").put("fetchedAt", "2026-10-17T00:02:00Z").put("excerpt", "复盘刷新后的另一正文版本。").put("stale", false);
        clock.instant = Instant.parse("2026-10-17T00:02:00Z");
        var service = service(false);
        var merged = service.mergeSources(prepared, followup);
        assertThat(merged).hasSize(2);
        var originalVersion = merged.get(0);
        assertThat(originalVersion.path("excerpt").asText()).contains("准备阶段实际看到的缓存背景。", "追问阶段实际看到的更新失败窗口。");
        assertThat(originalVersion.path("excerpts")).hasSize(2);
        assertThat(originalVersion.path("stale").asBoolean()).isFalse();
        assertThat(originalVersion.path("fetchedAt").asText()).isEqualTo("2026-10-17T00:01:00Z");
        assertThat(service.mergeSources(merged, followup).get(0).path("excerpts")).hasSize(2);
        assertThat(prepared.get(0).path("excerpt").asText()).isEqualTo("准备阶段实际看到的缓存背景。");
    }

    @Test void rechecksSnapshotAgeWhenMergingHistoricalEvidenceForReview() {
        ArrayNode historical = mapper.createArrayNode();
        historical.addObject().put("id", "redis:111111111111").put("fetchedAt", "2026-10-17T00:00:00Z").put("excerpt", "历史依据仍须保留，但不能作为当前查证成功。").put("stale", false);
        clock.instant = clock.instant.plusSeconds(49 * 3600);
        var result = service(false).mergeSources(historical, mapper.createArrayNode());
        assertThat(result.get(0).path("stale").asBoolean()).isTrue();
        assertThat(result.get(0).path("warning").asText()).contains("48小时");
    }

    @Test void selectionDoesNotSendEntirePreviouslyUsedExcerptCollectionToFastModel() {
        ArrayNode sources = mapper.createArrayNode();
        sources.addObject().put("id", "unknown-version").put("excerpt", "Redis cache invalidation 当前相关证据。").putArray("excerpts").add("此前其他问题使用过的长段内容。");
        var selected = service(false).select(sources, "Redis", 1);
        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).has("excerpts")).isFalse();
    }


    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-17T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
