package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.dto.ModelSettings;
import cn.interview.exception.ApiException;
import cn.interview.service.model.ModelConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定历史的真实模型回归：执行生产客户端及校验，不冒充完整连续场次。 */
@EnabledIfEnvironmentVariable(named = "INTERVIEW_PRIORITY_LIVE_TESTS", matches = "true")
@SpringBootTest(classes = Application.class, properties = {
    "app.knowledge.network-enabled=false", "app.training.enabled=false"})
@Import(InterviewFollowupPriorityLiveTest.Configuration.class)
class InterviewFollowupPriorityLiveTest {
    static final Path DATA = temporaryData();
    @DynamicPropertySource static void data(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
    @Autowired ObjectMapper json;
    @Autowired InterviewModelClient client;
    @Autowired ModelConfigurationService settings;
    @Autowired RecordingTransport transport;

    @Test void 真实固定历史优先核实未解风险并保留正常深入与硬约束() throws Exception {
        assertThat(settings.connection().configured()).isTrue();
        assertThat(settings.connection().provider()).isEqualTo("openai");
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("DOCUMENT_MAP.md"))) root = root.getParent();
        Path assets = root.resolve("evals/interview-agent/multiturn");
        Map<String, JsonNode> cases = new LinkedHashMap<>();
        for (var c : json.readTree(assets.resolve("cases.json").toFile()).path("cases")) cases.put(c.path("id").asText(), c);
        Map<String, ObjectNode> inputs = new LinkedHashMap<>();
        JsonNode sources = json.readTree(assets.resolve("../sources.json").toFile()).path("sources");
        for (var entry : cases.entrySet()) inputs.put(entry.getKey(), input(entry.getValue().path("dialogue"), sources));
        for (var control : json.readTree(assets.resolve("priority-controls.json").toFile()).path("controls")) {
            var dialogue = (ObjectNode) cases.get(control.path("baseCaseId").asText()).path("dialogue").deepCopy();
            control.path("answerReplacements").fields().forEachRemaining(e ->
                ((ObjectNode) dialogue.path("turns").get(Integer.parseInt(e.getKey()) - 1)).put("answer", e.getValue().asText()));
            if (control.has("questionReplacement")) ((ObjectNode) dialogue.path("turns").get(4)).set("question", control.get("questionReplacement"));
            ObjectNode payload = input(dialogue, sources);
            for (String k : List.of("assessmentPoint", "followUpCount", "reduceFollowups"))
                if (control.has(k)) payload.set(k, control.get(k).deepCopy());
            inputs.put(control.path("id").asText(), payload);
        }
        String selectedCases = System.getenv("INTERVIEW_PRIORITY_CASE_IDS");
        if (selectedCases != null && !selectedCases.isBlank()) {
            Set<String> selected = Set.of(selectedCases.split(","));
            assertThat(inputs.keySet()).containsAll(selected);
            inputs.keySet().retainAll(selected);
        }
        Path evidence = Path.of(System.getenv("INTERVIEW_PRIORITY_EVIDENCE")).toAbsolutePath().normalize();
        assertThat(evidence.startsWith(root.resolve(".evidence"))).isTrue();
        assertThat(Files.exists(evidence)).as("每次运行独立目录，不能覆盖原证据").isFalse();
        Files.createDirectories(evidence);
        var ledger = json.createObjectNode().put("kind", "未解风险优先级固定历史真实回归")
            .put("startedAt", Instant.now().toString()).put("productionClientExecuted", true)
            .put("productionServiceExecuted", false).put("confirmedByHuman", false)
            .put("status", "RUNNING").put("expectedRequests", inputs.size());
        var sourceHashes = ledger.putObject("sourceHashes");
        for (String path : List.of(
                "backend/src/main/resources/prompts/interview/followup.txt",
                "backend/src/main/java/cn/interview/client/interview/InterviewModelClient.java",
                "backend/src/main/java/cn/interview/client/transport/OpenAiJsonClient.java",
                "backend/src/main/java/cn/interview/validation/InterviewOutputValidator.java",
                "backend/src/test/java/cn/interview/client/interview/InterviewFollowupPriorityLiveTest.java",
                "evals/interview-agent/multiturn/cases.json",
                "evals/interview-agent/multiturn/priority-controls.json",
                "evals/interview-agent/sources.json")) {
            String content = Files.readString(root.resolve(path));
            sourceHashes.put(path, hash(content));
            Path snapshot = evidence.resolve("sources").resolve(path);
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, content);
        }
        transport.expectedPromptHash = sourceHashes.path("backend/src/main/resources/prompts/interview/followup.txt").asText();
        transport.persist = () -> {
            try {
                Path pending = evidence.resolve("ledger.pending.json");
                json.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(), ledger);
                Files.move(pending, evidence.resolve("ledger.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (Exception error) { throw new IllegalStateException("无法保存真实回归记录，未继续发送", error); }
        };
        var attempts = ledger.putArray("attempts");
        Map<String, ObjectNode> outputs = new LinkedHashMap<>();
        for (var entry : inputs.entrySet()) {
            var record = attempts.addObject().put("caseId", entry.getKey()).put("status", "RESERVED")
                .put("startedAt", Instant.now().toString()).put("inputHash", hash(entry.getValue().toString()));
            record.set("input", entry.getValue());
            transport.persist.run();
            transport.record = record;
            long start = System.nanoTime();
            try {
                var output = client.call("FOLLOWUP", entry.getValue(), 30, usage -> record.set("usage", usage.deepCopy()));
                record.set("output", output.deepCopy());
                record.put("status", "PRODUCTION_CLIENT_VALIDATED");
                outputs.put(entry.getKey(), output);
            } catch (RuntimeException error) {
                record.put("status", "FAILED").put("errorCode", error instanceof ApiException api ? api.code : error.getClass().getSimpleName())
                    .put("rawOutputObtained", record.has("outputText"))
                    .put("failureStage", record.has("outputText") ? "OUTPUT_VALIDATION" : "TRANSPORT_OR_PRECHECK");
                ledger.put("status", "ABORTED").put("finishedAt", Instant.now().toString());
                throw error;
            } finally {
                record.put("elapsedMs", (System.nanoTime() - start) / 1_000_000).put("finishedAt", Instant.now().toString());
                transport.persist.run();
            }
        }
        // 这些只是语义烟测；完整原文仍须逐例核对，不能以关键词替代专业性结论。
        var smoke = ledger.putObject("semanticSmoke").put("humanReviewReplaced", false);
        var failures = smoke.putArray("failedCaseIds");
        Map<String, List<String>> requiredWords = Map.of(
            "CONT-C02", List.of("远端", "仓储", "发货"),
            "CONT-C04", List.of("此前", "之前", "先前", "最初", "第一", "前面", "早先", "一开始"),
            "PRIORITY-C04-SAME-PATH", List.of("此前", "之前", "先前", "最初", "第一", "前面", "早先", "一开始"),
            "CONT-Q03", List.of("回滚", "事务", "异常"),
            "CONT-Q04", List.of("时间", "条件", "入口", "记录", "配置", "核实", "核对"));
        for (var entry : outputs.entrySet()) {
            String id = entry.getKey(), question = entry.getValue().path("question").asText();
            if (requiredWords.containsKey(id) && requiredWords.get(id).stream().noneMatch(question::contains)) failures.add(id);
            if (id.startsWith("PRIORITY-C04-UNRELATED") && List.of("租户", "索引", "检索").stream().anyMatch(question::contains)) failures.add(id);
            if (Set.of("PRIORITY-C04-LIMIT", "PRIORITY-C04-REDUCE").contains(id)
                    && !entry.getValue().path("action").asText().equals("NEXT")) failures.add(id);
        }
        smoke.put("status", failures.isEmpty() ? "PASSED" : "FAILED");
        ledger.put("status", "CALLS_COMPLETE").put("finishedAt", Instant.now().toString());
        transport.persist.run();
        assertThat(failures).as("固定历史语义烟测失败项；完整结论另须逐例评阅").isEmpty();
    }

    private ObjectNode input(JsonNode dialogue, JsonNode sources) {
        var out = json.createObjectNode();
        for (String k : List.of("role", "level", "sessionType")) out.set(k, dialogue.get(k));
        out.putObject("summary"); out.put("jd", "").put("followUpCount", 0).put("strategyVersion", 1);
        out.putArray("diagnostics");
        var messages = out.putArray("messages");
        boolean comprehensive = dialogue.path("sessionType").asText().equals("COMPREHENSIVE");
        for (var t : dialogue.path("turns")) for (String role : List.of("ASSISTANT", "USER")) {
            var m = messages.addObject().put("role", role);
            boolean question = role.equals("ASSISTANT");
            m.set("content", t.get(question ? "question" : "answer"));
            m.set("id", t.get(question ? "questionMessageId" : "answerMessageId"));
            for (String k : List.of("questionId", "rootQuestionId", "questionKind")) m.set(k, t.get(k));
            if (comprehensive) m.put("stage", "TECHNICAL");
        }
        out.set("currentQuestion", messages.get(messages.size() - 2).deepCopy());
        out.set("answer", messages.get(messages.size() - 1).get("content"));
        var selected = out.putArray("sources");
        for (var id : dialogue.path("sourceIds")) for (var source : sources)
            if (source.path("id").equals(id)) {
                var clean = selected.addObject();
                for (String k : List.of("id", "title", "url", "kind", "stale", "fetchedAt", "excerpt")) clean.set(k, source.get(k));
            }
        if (comprehensive) {
            out.put("experienceVersion", 1).put("stage", "TECHNICAL").put("reduceFollowups", false);
            out.putObject("mainProject").put("hypothetical", true).put("brief", "");
        }
        return out;
    }

    static Path temporaryData() {
        try { return Files.createTempDirectory("interview-priority-live-"); }
        catch (Exception error) { throw new IllegalStateException("无法创建隔离测试目录", error); }
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @TestConfiguration static class Configuration {
        @Bean @org.springframework.context.annotation.Primary RecordingTransport priorityTransport(ObjectMapper json) { return new RecordingTransport(json); }
    }
    static class RecordingTransport extends OpenAiJsonClient {
        ObjectNode record;
        String expectedPromptHash;
        Runnable persist;
        RecordingTransport(ObjectMapper json) { super(json); }
        @Override public Reply exchange(ModelSettings settings, String prompt, ObjectNode input, int maxTokens,
                int timeoutSeconds, boolean fast, Consumer<ObjectNode> usage) {
            record.put("promptHash", hash(prompt)).put("requestedModel", settings.fastModel().isBlank() ? settings.model() : settings.fastModel())
                .put("reasoningEffort", settings.effort(fast)).put("maxTokens", maxTokens).put("timeoutSeconds", timeoutSeconds)
                .put("temperature", 0.4).put("provider", settings.provider())
                .put("endpointHash", hash(settings.baseUrl().strip().replaceAll("/+$", "")));
            record.set("extraBody", new ObjectMapper().valueToTree(settings.extraBody(fast)));
            if (!hash(prompt).equals(expectedPromptHash)) throw new IllegalStateException("执行提示词与冻结快照不符，未发请求");
            persist.run();
            Reply reply = super.exchange(settings, prompt, input, maxTokens, timeoutSeconds, fast, usage);
            record.put("outputText", reply.text()).put("returnedModel", reply.model()).put("finishReason", reply.finishReason())
                .put("fingerprint", reply.fingerprint());
            return reply;
        }
    }
}
