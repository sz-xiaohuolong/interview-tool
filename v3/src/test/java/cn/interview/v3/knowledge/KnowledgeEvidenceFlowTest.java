package cn.interview.v3.knowledge;

import cn.interview.v3.mapper.V3Store;
import cn.interview.v3.model.V3ModelGateway;
import cn.interview.v3.service.V3InterviewService;
import cn.interview.v3.validation.V3OutputValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeEvidenceFlowTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();

    @Test void publicSnapshotRetainsActualPlanningFollowupAndReviewEvidence() throws Exception {
        String body = "背景介绍。".repeat(500) + "\nRedis cache caching consistency invalidation 准备证据。".repeat(24)
                + "\n普通说明。".repeat(250) + "\nthread visibility concurrent concurrency 追问证据。".repeat(24);
        var knowledge = new KnowledgeService(json, directory,
                List.of(new KnowledgeService.Source("redis", "合成官方资料", "https://docs.example.test/cache", "测试版", "OFFICIAL", List.of("REDIS"), "合成资料", "测试许可")),
                (uri, timeout, maximum) -> new KnowledgeService.Payload(200, "text/plain", body.getBytes(StandardCharsets.UTF_8)), Clock.systemUTC(), true);
        var seen = new ConcurrentHashMap<String, JsonNode>();
        V3ModelGateway gateway = (stage, input, timeout) -> {
            seen.put(stage, input.deepCopy());
            var result = json.createObjectNode();
            if (stage.equals("ANALYZE")) {
                var summary = result.putObject("summary");
                summary.putArray("technicalStack").add("Redis 缓存一致性");
                summary.putArray("responsibilities");summary.putArray("clarifications");summary.putArray("focus").add("缓存一致性");
                result.putArray("topics").add("REDIS");
            } else if (stage.equals("PLAN")) {
                var questions = result.putArray("questions");
                for (String phase : List.of("INTRO", "PROJECT", "TECHNICAL", "QUESTIONS")) {
                    var question = questions.addObject().put("stage", phase).put("question", "请说明 Redis thread visibility concurrent concurrency 的边界").put("topic", "REDIS").put("evidence", "通用岗位知识");
                    question.putArray("sourceIds").add(input.path("sources").get(0).path("id").asText());question.putArray("backups");
                }
            } else if (stage.equals("FOLLOWUP")) {
                result.put("action", "NEXT").putArray("sourceIds");
            } else if (stage.equals("REVIEW")) {
                result.put("summary", "合成复盘，仅验证片段保存。").putArray("findings");
            }
            return result;
        };
        var service = new V3InterviewService(new V3Store(json, directory.toString()), json, null, gateway, knowledge,
                new V3OutputValidator(json), new MockEnvironment().withProperty("app.llm.provider", "mock"), 7000);
        try {
            var created = service.create(request().put("role", "Java 后端").put("level", "JUNIOR"));
            String id = created.path("id").asText();var ready = waitFor(service, id, "READY");
            assertVisible(ready, seen.get("PLAN"));
            var started = service.action(id, "confirm", action(ready));
            service.action(id, "answers", action(started).put("expectedQuestionId", started.path("messages").get(0).path("questionId").asText()).put("content", "thread visibility concurrent concurrency"));
            var completed = waitFor(service, id, "IN_PROGRESS");
            assertVisible(completed, seen.get("PLAN"));assertVisible(completed, seen.get("FOLLOWUP"));
            assertThat(seen.get("FOLLOWUP").path("sources").get(0).path("excerpt")).isNotEqualTo(seen.get("PLAN").path("sources").get(0).path("excerpt"));
            service.action(id, "finish", action(completed));var reviewed = waitFor(service, id, "REPORT_READY");
            assertVisible(reviewed, seen.get("PLAN"));assertVisible(reviewed, seen.get("FOLLOWUP"));assertVisible(reviewed, seen.get("REVIEW"));
        } finally { service.shutdown();knowledge.close(); }
    }

    private ObjectNode request() { return json.createObjectNode().put("requestId", UUID.randomUUID().toString()); }
    private ObjectNode action(JsonNode state) { return request().put("expectedRevision", state.path("revision").asInt()); }
    private JsonNode waitFor(V3InterviewService service, String id, String status) throws Exception {
        for (int iteration = 0; iteration < 250; iteration++) {
            var state = service.get(id);if (state.path("status").asText().equals(status)) return state;
            if (state.path("status").asText().equals("FAILED")) throw new AssertionError(state.toString());
            Thread.sleep(10);
        }
        throw new AssertionError("等待合成流程失败：" + service.get(id));
    }
    private void assertVisible(JsonNode snapshot, JsonNode modelInput) {
        assertThat(modelInput).isNotNull();assertThat(modelInput.path("sources")).isNotEmpty();
        for (var seenSource : modelInput.path("sources")) {
            JsonNode visible = null;
            for (var source : snapshot.path("sources")) if (source.path("id").equals(seenSource.path("id"))) visible = source;
            assertThat(visible).isNotNull();
            assertThat(visible.path("excerpt").asText()).contains(seenSource.path("excerpt").asText());
        }
    }
}
