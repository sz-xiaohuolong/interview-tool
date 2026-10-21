package cn.interview.service.knowledge;

import cn.interview.client.transport.FixedHttpTransport;
import cn.interview.client.transport.KnowledgeTransport;
import cn.interview.client.transport.KnowledgeTransport.Payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只访问受控公开资料，并在本机缓存与选择可回查的片段。 */
@Component
public class KnowledgeService {
  record Source(
      String id,
      String title,
      String url,
      String version,
      String kind,
      List<String> topics,
      String attribution,
      String license) {}

  static final int MAX_BYTES = 2 * 1024 * 1024;
  private static final int MAX_TEXT = 10_000;
  private static final Duration TTL = Duration.ofHours(48);
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Pattern WORDS = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+");
  private static final Set<String> STOP_WORDS =
      Set.of(
          "如何", "什么", "为何", "怎么", "项目", "使用", "实现", "设计", "你的", "技术", "场景", "问题", "考虑", "保证", "一个",
          "是否", "哪些", "中的", "进行", "以及", "说明", "一下", "针对");
  private static final Map<String, List<String>> CONCEPTS =
      Map.ofEntries(
          Map.entry("数据库", List.of("database", "mysql")), Map.entry("事务", List.of("transaction")),
          Map.entry("隔离", List.of("isolation")), Map.entry("缓存", List.of("cache", "caching")),
          Map.entry("一致性", List.of("consistency", "invalidation")),
              Map.entry("并发", List.of("concurrent", "concurrency")),
          Map.entry("线程", List.of("thread")),
              Map.entry("垃圾回收", List.of("garbage", "collector", "collection")),
          Map.entry("作用域", List.of("scope")), Map.entry("检索", List.of("retrieval", "retrieve")),
          Map.entry("向量", List.of("vector", "embedding")), Map.entry("工具", List.of("tool")),
          Map.entry("记忆", List.of("memory")), Map.entry("评估", List.of("evaluation", "evaluator")),
          Map.entry("幻觉", List.of("hallucination")), Map.entry("智能体", List.of("agent")));
  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("JAVA", "JAVA_CORE"),
          Map.entry("CONCURRENCY", "JAVA_CONCURRENCY"),
          Map.entry("MYSQL", "DATABASE"),
          Map.entry("RAG", "AI_RAG"),
          Map.entry("AGENT", "AI_TOOLS"),
          Map.entry("MCP", "MCP"),
          Map.entry("EVALUATION", "AI_EVALUATION"));
  private final ObjectMapper mapper;
  private final Path cacheDirectory;
  private final List<Source> catalogue;
  private final KnowledgeTransport transport;
  private final Clock clock;
  private final boolean enabled;
  private final ThreadLocal<ArrayNode> retrievalDetails = ThreadLocal.withInitial(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance::arrayNode);
  private record Retrieval(ObjectNode source, ObjectNode detail) {}

  private final ThreadLocal<List<String>> retrievalWarnings =
      ThreadLocal.withInitial(ArrayList::new);
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          6,
          runnable -> {
            Thread thread = new Thread(runnable, "v3-knowledge");
            thread.setDaemon(true);
            return thread;
          });

  @Autowired
  public KnowledgeService(
      ObjectMapper mapper,
      @Value("${app.data-dir}") String directory,
      @Value("${app.knowledge.network-enabled:true}") boolean enabled) {
    this(
        mapper,
        Path.of(directory),
        readCatalogue(mapper),
        new FixedHttpTransport(),
        Clock.systemUTC(),
        enabled);
  }

  KnowledgeService(
      ObjectMapper mapper,
      Path directory,
      List<Source> sources,
      KnowledgeTransport transport,
      Clock clock,
      boolean enabled) {
    this.mapper = mapper;
    this.cacheDirectory = directory.resolve("v3/knowledge");
    this.catalogue = List.copyOf(sources);
    this.transport = transport;
    this.clock = clock;
    this.enabled = enabled;
  }

  public ArrayNode retrieve(List<String> topics, boolean refresh) {
    return retrieve(topics, refresh, List.of());
  }

  public ArrayNode retrieve(List<String> topics, boolean refresh, List<String> preferredSourceIds) {
    retrievalWarnings.set(new ArrayList<>());
    retrievalDetails.set(mapper.createArrayNode());
    Set<String> normalized = new LinkedHashSet<>();
    if (topics != null)
      for (String topic : topics) {
        if (topic == null) continue;
        String key = topic.strip().toUpperCase(Locale.ROOT);
        normalized.add(ALIASES.getOrDefault(key, key));
      }
    List<Source> candidates =
        catalogue.stream()
            .filter(source -> source.topics().stream().anyMatch(normalized::contains))
            .sorted(Comparator.comparingInt(source -> {
              int index = preferredSourceIds.indexOf(source.id());
              return index < 0 ? Integer.MAX_VALUE : index;
            }))
            .toList();
    List<Source> chosen = new ArrayList<>();
    candidates.stream()
        .filter(source -> source.kind().equals("INTERVIEW"))
        .findFirst()
        .ifPresent(chosen::add);
    List<String> ordinary =
        normalized.stream()
            .filter(topic -> !aiTopic(topic))
            .toList();
    List<String> ai =
        normalized.stream()
            .filter(KnowledgeService::aiTopic)
            .toList();
    List<String> ordered = new ArrayList<>();
    for (int index = 0; index < Math.max(ordinary.size(), ai.size()); index++) {
      if (index < ordinary.size()) ordered.add(ordinary.get(index));
      if (index < ai.size()) ordered.add(ai.get(index));
    }
    for (String topic : ordered) {
      if (chosen.size() >= 6) break;
      candidates.stream()
          .filter(source -> source.kind().equals("OFFICIAL") && source.topics().contains(topic))
          .filter(source -> !chosen.contains(source))
          .findFirst()
          // 当前主题没有尚未选取的官方来源时，补充考点线索；复盘仍按来源分级校验。
          .or(() -> candidates.stream()
              .filter(source -> source.kind().equals("INTERVIEW") && source.topics().contains(topic))
              .filter(source -> !chosen.contains(source))
              .findFirst())
          .ifPresent(chosen::add);
    }
    for (Source source : candidates) {
      if (chosen.size() >= 6) break;
      if (!chosen.contains(source)) chosen.add(source);
    }
    List<CompletableFuture<Retrieval>> tasks =
        chosen.stream()
            .map(
                source ->
                    CompletableFuture.supplyAsync(() -> load(source, refresh), executor)
                        .orTimeout(11, TimeUnit.SECONDS)
                        .exceptionally(error -> failed(source, readCache(source), error, true)))
            .toList();
    ArrayNode result = mapper.createArrayNode();
    for (int index = 0; index < tasks.size(); index++) {
      Retrieval retrieval = tasks.get(index).join();
      retrievalDetails.get().add(retrieval.detail());
      ObjectNode source = retrieval.source();
      if (source != null) result.add(source);
      if (source == null)
        retrievalWarnings.get().add("本次未获取到参考文档：" + chosen.get(index).title() + "；相关技术依据不足");
      else if (source.hasNonNull("warning"))
        retrievalWarnings
            .get()
            .add(source.path("title").asText() + "：" + source.path("warning").asText());
    }
    Set<String> covered = new HashSet<>();
    for (JsonNode source : result)
      source.path("topics").forEach(topic -> covered.add(topic.asText()));
    for (String topic : normalized) {
      if (!covered.contains(topic)) retrievalWarnings.get().add("本场受控来源未覆盖全部知识主题，相关技术结论需保留待核实");
    }
    return result;
  }

  private static boolean aiTopic(String topic) {
    return topic.startsWith("AI_") || topic.startsWith("AGENT_") || topic.equals("MCP");
  }

  /** 返回当前调用线程最近一次检索的缺口，避免不同会话互相覆盖。 */
  public List<String> warnings() {
    return List.copyOf(new LinkedHashSet<>(retrievalWarnings.get()));
  }

  /** 包含没有可用正文的失败来源，不能用空正文伪造引用。 */
  public ArrayNode retrievalDetails() { return retrievalDetails.get().deepCopy(); }

  /** 同一正文版本保留每次实际使用的片段，引用新版本不覆盖旧版本。 */
  public ArrayNode mergeSources(JsonNode original, JsonNode updates) {
    Map<String, ObjectNode> merged = new LinkedHashMap<>();
    for (JsonNode group :
        List.of(
            original == null ? mapper.createArrayNode() : original,
            updates == null ? mapper.createArrayNode() : updates)) {
      if (!group.isArray()) continue;
      for (JsonNode value : group) {
        if (!value.isObject() || value.path("id").asText().isBlank()) continue;
        String id = value.path("id").asText();
        ObjectNode previous = merged.get(id);
        LinkedHashSet<String> excerpts = new LinkedHashSet<>();
        collectExcerpts(previous, excerpts);
        collectExcerpts(value, excerpts);
        ObjectNode metadata = ((ObjectNode) value).deepCopy();
        if (previous != null) {
          try {
            if (Instant.parse(previous.path("fetchedAt").asText())
                .isAfter(Instant.parse(value.path("fetchedAt").asText())))
              metadata = previous.deepCopy();
          } catch (RuntimeException ignored) {
          }
        }
        metadata.remove("rawText");
        metadata.put("stale", metadata.path("stale").asBoolean() || expired(metadata));
        if (expired(metadata) && !metadata.hasNonNull("warning"))
          metadata.put("warning", "来源快照已超过48小时，不能直接据此判错");
        ArrayNode pieces = metadata.putArray("excerpts");
        excerpts.forEach(pieces::add);
        metadata.put("excerpt", String.join("\n\n---来源片段分隔---\n\n", excerpts));
        merged.put(id, metadata);
      }
    }
    ArrayNode result = mapper.createArrayNode();
    merged.values().forEach(result::add);
    return result;
  }

  private static void collectExcerpts(JsonNode source, Set<String> target) {
    if (source == null) return;
    LinkedHashSet<String> stored = new LinkedHashSet<>();
    for (JsonNode piece : source.path("excerpts"))
      if (piece.isTextual() && !piece.asText().isBlank()) stored.add(piece.asText());
    target.addAll(stored);
    String excerpt = source.path("excerpt").asText();
    if (!excerpt.isBlank() && !excerpt.equals(String.join("\n\n---来源片段分隔---\n\n", stored)))
      target.add(excerpt);
  }

  public ArrayNode select(JsonNode sources, String query, int limit) {
    ArrayNode result = mapper.createArrayNode();
    if (sources == null || !sources.isArray() || query == null || query.isBlank() || limit <= 0)
      return result;
    Set<String> terms = terms(query);
    if (terms.isEmpty()) return result;
    record Match(ObjectNode source, int score) {}
    List<Match> matches = new ArrayList<>();
    for (JsonNode item : sources) {
      if (!item.isObject()) continue;
      String text = item.path("excerpt").asText("");
      String publicId = item.path("id").asText();
      String sourceKey =
          publicId.contains(":") ? publicId.substring(0, publicId.indexOf(':')) : publicId;
      Source known =
          catalogue.stream()
              .filter(source -> source.id().equals(sourceKey))
              .findFirst()
              .orElse(null);
      ObjectNode cached = known == null ? null : readCache(known);
      if (cached != null && cached.path("sha256").equals(item.path("sha256")))
        text = cached.path("rawText").asText(text);
      String best = "";
      int bestScore = 0;
      for (String passage : passages(text)) {
        String lower = passage.toLowerCase(Locale.ROOT);
        int score =
            terms.stream()
                .filter(term -> containsTerm(lower, term))
                .mapToInt(term -> term.length() > 2 ? 2 : 1)
                .sum();
        if (score > bestScore) {
          bestScore = score;
          best = passage;
        }
      }
      if (bestScore > 0) {
        ObjectNode copy = ((ObjectNode) item).deepCopy();
        copy.remove(List.of("rawText", "excerpts"));
        copy.put("excerpt", best);
        matches.add(new Match(copy, bestScore));
      }
    }
    matches.stream()
        .sorted(Comparator.comparingInt(Match::score).reversed())
        .limit(Math.min(limit, 6))
        .forEach(match -> result.add(match.source()));
    return result;
  }

  private Retrieval load(Source source, boolean refresh) {
    ObjectNode cached = readCache(source);
    boolean stale = expired(cached);
    if (cached != null && !refresh && !stale)
      return retrieved(source, publicSource(cached, false, ""), "CACHED", "复用48小时内有效缓存", 0, true);
    if (!enabled) return fallback(source, cached, "NETWORK_DISABLED", "联网获取已关闭", 0);
    try {
      Payload payload = transport.fetch(URI.create(source.url()), TIMEOUT, MAX_BYTES);
      if (payload.status() != 200) return fallback(source, cached, "HTTP_ERROR", "参考文档返回HTTP " + payload.status(), payload.status());
      if (payload.body().length > MAX_BYTES) return fallback(source, cached, "LIMIT_EXCEEDED", "参考文档超过2MiB限制", 200);
      String text = extract(new String(payload.body(), StandardCharsets.UTF_8), payload.contentType(), source.url());
      if (text.isBlank()) return fallback(source, cached, "INVALID_CONTENT", "参考文档没有可用正文", 200);
      text = text.substring(0, Math.min(text.length(), MAX_TEXT));
      ObjectNode node = mapper.valueToTree(source);
      node.put("fetchedAt", clock.instant().toString());
      node.put("sha256", java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
      node.put("rawText", text).put("excerpt", text.substring(0, Math.min(text.length(), 2400)));
      writeCache(source, node);
      return retrieved(source, publicSource(node, false, ""), "FETCHED", "本次已获取参考文档", 200, false);
    } catch (Exception error) {
      return failed(source, cached, error, false);
    }
  }

  private Retrieval failed(Source source, ObjectNode cached, Throwable error, boolean groupDeadline) {
    // HttpClient 的异步失败可能由 ExecutionException、IOException 等多层包装。
    Throwable cause = error;
    for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
      if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
        return fallback(source, cached, "TIMEOUT", groupDeadline ? "整组获取达到11秒上限" : "参考文档请求超时（单请求上限10秒）", 0);
      if ("公开资料超过大小限制".equals(cause.getMessage()))
        return fallback(source, cached, "LIMIT_EXCEEDED", "参考文档超过2MiB限制", 0);
    }
    return fallback(source, cached, "NETWORK_ERROR", "参考文档网络读取失败", 0);
  }

  private Retrieval fallback(Source source, ObjectNode cached, String status, String message, int httpStatus) {
    boolean stale = expired(cached);
    var value = cached == null ? null : publicSource(cached, stale,
        stale ? "已使用本地缓存；缓存已过期，技术依据需复核" : "已使用本地缓存");
    return retrieved(source, value, status, message, httpStatus, cached != null);
  }

  private Retrieval retrieved(Source source, ObjectNode value, String status, String message, int httpStatus, boolean cacheUsed) {
    var detail = mapper.createObjectNode().put("sourceId", source.id()).put("title", source.title()).put("url", source.url())
        .put("status", status).put("message", message).put("cacheUsed", cacheUsed)
        .put("stale", value != null && value.path("stale").asBoolean()).put("attemptedAt", clock.instant().toString());
    if (httpStatus > 0) detail.put("httpStatus", httpStatus);
    if (value != null) value.set("retrieval", detail.deepCopy());
    return new Retrieval(value, detail);
  }

  private boolean expired(ObjectNode cached) {
    if (cached == null) return true;
    try {
      Instant fetched = Instant.parse(cached.path("fetchedAt").asText());
      return fetched.isAfter(clock.instant()) || !fetched.plus(TTL).isAfter(clock.instant());
    } catch (RuntimeException error) {
      return true;
    }
  }

  private ObjectNode publicSource(ObjectNode cached, boolean stale, String warning) {
    ObjectNode copy = cached.deepCopy();
    copy.remove("rawText");
    copy.put(
        "id", cached.path("id").asText() + ":" + cached.path("sha256").asText().substring(0, 12));
    copy.put("stale", stale);
    if (!warning.isBlank()) copy.put("warning", warning);
    return copy;
  }

  private ObjectNode readCache(Source source) {
    if (!source.id().matches("[a-z0-9-]+")) return null;
    Path file = cacheDirectory.resolve(source.id() + ".json");
    try {
      if (!Files.isRegularFile(file) || Files.size(file) > 150_000) return null;
      JsonNode value = mapper.readTree(file.toFile());
      if (!value.isObject()
          || !value.path("url").asText().equals(source.url())
          || !value.path("id").asText().equals(source.id())
          || !value.path("sha256").asText().matches("[a-f0-9]{64}")) return null;
      var cached = (ObjectNode) value;
      // 主题是当前受控分类，正文版本与抓取时间仍沿用真实缓存。
      cached.set("topics", mapper.valueToTree(source.topics()));
      return cached;
    } catch (Exception error) {
      return null;
    }
  }

  private void writeCache(Source source, ObjectNode node) throws Exception {
    Files.createDirectories(cacheDirectory);
    Path temporary = Files.createTempFile(cacheDirectory, source.id() + "-", ".tmp");
    try {
      mapper.writeValue(temporary.toFile(), node);
      Files.move(
          temporary,
          cacheDirectory.resolve(source.id() + ".json"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String extract(String input, String contentType, String url) {
    if (contentType.toLowerCase(Locale.ROOT).contains("html")
        || input.stripLeading().startsWith("<!DOCTYPE")
        || input.stripLeading().startsWith("<html")) {
      Document document = Jsoup.parse(input, url);
      document.select("script,style,nav,header,footer,aside,form,noscript,svg,template").remove();
      Element body = document.selectFirst("main,article,[role=main],#main-content,.doc");
      return (body == null ? document.body() : body)
          .wholeText()
          .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
          .replaceAll("\\n{3,}", "\n\n")
          .strip();
    }
    return input
        .replaceFirst("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "")
        .replaceAll("(?s)<!--.*?-->", "")
        .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
        .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
        .strip();
  }

  private static List<String> passages(String text) {
    List<String> result = new ArrayList<>();
    if (text == null) return result;
    String bounded = text.substring(0, Math.min(text.length(), MAX_TEXT));
    for (int position = 0; position < bounded.length(); position += 900) {
      result.add(bounded.substring(position, Math.min(bounded.length(), position + 1400)));
    }
    return result;
  }

  private static Set<String> terms(String query) {
    Set<String> result = new HashSet<>();
    CONCEPTS.forEach(
        (concept, equivalents) -> {
          if (query.contains(concept)) result.addAll(equivalents);
        });
    var matcher = WORDS.matcher(query.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String word = matcher.group();
      if (word.codePoints()
          .allMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)) {
        for (int index = 0; index + 1 < word.length(); index++) {
          String term = word.substring(index, index + 2);
          if (!STOP_WORDS.contains(term)) result.add(term);
        }
      } else if (word.length() > 1) result.add(word);
    }
    return result;
  }

  private static boolean containsTerm(String passage, String term) {
    if (term.matches("[a-z0-9_]+"))
      return Pattern.compile("\\b" + Pattern.quote(term) + "s?\\b").matcher(passage).find();
    return passage.contains(term);
  }

  private static List<Source> readCatalogue(ObjectMapper mapper) {
    try (InputStream input = KnowledgeService.class.getResourceAsStream("/knowledge/sources.json")) {
      if (input == null) throw new IllegalStateException("缺少公开来源清单");
      List<Source> sources =
          mapper.readValue(
              input, mapper.getTypeFactory().constructCollectionType(List.class, Source.class));
      for (Source source : sources) FixedHttpTransport.validatePublicUrl(URI.create(source.url()));
      return sources;
    } catch (Exception error) {
      throw new IllegalStateException("公开来源清单无效", error);
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
