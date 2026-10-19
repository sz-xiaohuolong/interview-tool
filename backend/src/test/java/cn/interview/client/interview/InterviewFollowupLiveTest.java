package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.mock.MockInterviewModel;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.dto.ModelSettings;
import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.InterviewService;
import cn.interview.service.model.ModelConfigurationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用的等待校准：只有追问真实调用，准备与复盘使用明确标识的测试替身。 */
@EnabledIfEnvironmentVariable(named = "INTERVIEW_FOLLOWUP_LIVE_TESTS", matches = "true")
@SpringBootTest(classes = Application.class, properties = {
    "app.knowledge.network-enabled=false", "app.interview.followup-deadline-ms=31000", "app.training.enabled=false"})
@Import(InterviewFollowupLiveTest.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewFollowupLiveTest {
    private static final int MAX_ATTEMPTS = 3;
    private static final Path DATA = temporaryData();
    private static final ThreadLocal<ObjectNode> ACTIVE_ATTEMPT = new ThreadLocal<>();

    @DynamicPropertySource
    static void isolatedData(DynamicPropertyRegistry properties) {
        properties.add("app.data-dir", DATA::toString);
    }

    @Autowired InterviewService service;
    @Autowired InterviewSessionStore store;
    @Autowired ObjectMapper json;
    @Autowired ModelConfigurationService settings;
    @Autowired CalibrationGateway gateway;

    @Test
    void 少量真实连续追问保存诊断并记录是否跨过六秒() throws Exception {
        assertThat(settings.connection().provider()).as("必须显式使用真实连接").isEqualTo("openai");
        assertThat(settings.connection().configured()).as("启动环境必须提供完整真实连接").isTrue();
        gateway.begin(evidenceDirectory());
        boolean passed = false;
        boolean observedSlowResponse = false;
        String sessionId = null;
        try {
            ObjectNode session = service.create(json.createObjectNode()
                .put("requestId", UUID.randomUUID().toString()).put("sessionType", "QUICK")
                .put("role", "Java 后端工程师").put("level", "JUNIOR").put("plannedQuestionCount", 3));
            sessionId = session.path("id").asText();
            session = awaitSettled(sessionId);
            assertThat(session.path("status").asText()).isEqualTo("IN_PROGRESS");
            List<String> answers = List.of(
                "这是合成练习项目。我用请求号查询幂等记录，不存在再创建订单；还没处理两个请求同时查不到的情况。",
                "我会给请求号加唯一约束，冲突时查询已保存结果；还没说明处理中记录怎样恢复，也没有验证进程崩溃。",
                "我计划记录处理状态和更新时间，由补偿任务核对超时记录；没有做过故障注入，这部分只是方案。");
            for (int index = 0; index < MAX_ATTEMPTS; index++) {
                JsonNode question = session.path("messages").get(session.path("messages").size() - 1);
                String answer = answers.get(index);
                long started = System.nanoTime();
                session = service.action(sessionId, "answers", action(session)
                    .put("expectedQuestionId", question.path("questionId").asText()).put("content", answer));
                assertThat(session.path("status").asText()).isEqualTo("ANSWERING");
                session = awaitSettled(sessionId);
                ObjectNode internal = store.get(sessionId);
                gateway.observeService(index, session, internal,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                assertThat(session.path("status").asText()).isIn("IN_PROGRESS", "COMPLETED");
                assertThat(internal.path("metrics").path("lastFallback").asBoolean()).isFalse();
                assertThat(internal.path("diagnostics")).hasSize(index + 1);
                JsonNode trace = internal.path("diagnostics").get(index);
                JsonNode submitted = internal.path("messages").get(answerIndex(internal, index));
                assertThat(trace.path("questionId").asText()).isEqualTo(question.path("questionId").asText());
                assertThat(trace.path("answerMessageId").asText()).isEqualTo(submitted.path("id").asText());
                String quote = trace.path("diagnosis").path("answerQuote").asText();
                assertThat(quote).isNotBlank();
                assertThat(answer).contains(quote);
                assertThat(trace.path("diagnosis").path("category").asText()).isIn(
                    "SUFFICIENT", "INCOMPLETE", "INCORRECT", "UNCERTAIN", "CONTRADICTORY", "NO_EVIDENCE");
                assertThat(session.has("diagnostics")).isFalse();
                assertThat(gateway.returnedModel(index)).as("记录真实返回模型，不能以缺失元数据充作成功").isNotBlank();
                gateway.acceptService(index);
                if (gateway.transportElapsedMillis(index) > 6000) {
                    observedSlowResponse = true;
                    break;
                }
                if (session.path("status").asText().equals("COMPLETED")) break;
            }
            passed = true;
        } finally {
            gateway.finish(passed, observedSlowResponse, sessionId == null ? null : store.get(sessionId));
        }
    }

    private ObjectNode action(JsonNode session) {
        return json.createObjectNode().put("requestId", UUID.randomUUID().toString())
            .put("expectedRevision", session.path("revision").asInt());
    }

    private ObjectNode awaitSettled(String id) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        ObjectNode session;
        do {
            session = service.get(id);
            if (!Set.of("QUEUED", "PREPARING", "ANSWERING").contains(session.path("status").asText())) return session;
            Thread.sleep(50);
        } while (System.nanoTime() < until);
        throw new AssertionError("隔离校准会话等待超过四十秒，停止后续调用");
    }

    private static int answerIndex(JsonNode session, int ordinal) {
        int current = 0;
        for (int i = 0; i < session.path("messages").size(); i++) {
            if (session.path("messages").get(i).path("role").asText().equals("USER") && current++ == ordinal) return i;
        }
        throw new AssertionError("没有找到本次合成回答");
    }

    private static Path temporaryData() {
        try { return Files.createTempDirectory("interview-followup-live-"); }
        catch (Exception error) { throw new IllegalStateException("无法创建隔离校准数据目录"); }
    }

    private static Path evidenceDirectory() throws Exception {
        String configured = System.getenv("INTERVIEW_FOLLOWUP_LIVE_EVIDENCE");
        if (configured == null || configured.isBlank()) throw new IllegalStateException("必须显式指定独立校准证据目录");
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("DOCUMENT_MAP.md"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("无法确定当前仓库目录");
        root = root.toRealPath();
        Path configuredPath = Path.of(configured);
        Path target = (configuredPath.isAbsolute() ? configuredPath : root.resolve(configuredPath)).normalize();
        if (!(target.startsWith(root.resolve(".evidence")) || target.startsWith(root.resolve(".runtime")))
            || target.startsWith(root.resolve(".evidence/interview-agent-eval")))
            throw new IllegalStateException("校准证据只能写入当前仓库的.runtime或.evidence独立目录，禁止使用旧评测目录");
        Path cursor = root;
        for (Path segment : root.relativize(target)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw new IllegalStateException("校准证据目录不能通过符号链接跳转");
        }
        Files.createDirectories(target);
        if (Files.exists(target.resolve("ledger.json")))
            throw new IllegalStateException("校准目录已有账本，禁止覆盖或隐式重试");
        for (int number = 1; number <= MAX_ATTEMPTS; number++)
            if (Files.exists(target.resolve("attempt-" + number + ".json")))
                throw new IllegalStateException("校准目录已有请求证据，禁止覆盖或隐式重试");
        Files.createFile(target.resolve("calibration.lock"));
        return target;
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("无法计算校准输入摘要"); }
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary RecordingTransport recordingTransport(ObjectMapper json) { return new RecordingTransport(json); }
        @Bean @Primary CalibrationGateway calibrationGateway(ObjectMapper json, InterviewModelClient client) {
            return new CalibrationGateway(json, client);
        }
    }

    /** 观察生产传输返回的正文，不复制网络协议或记录连接地址、请求头和密钥。 */
    static class RecordingTransport extends OpenAiJsonClient {
        private final ObjectMapper json;
        RecordingTransport(ObjectMapper json) { super(json); this.json = json; }

        @Override
        public Reply exchange(ModelSettings settings, String prompt, ObjectNode input, int maxTokens,
            int timeoutSeconds, boolean fast, Consumer<ObjectNode> reportUsage) {
            ObjectNode attempt = ACTIVE_ATTEMPT.get();
            if (attempt == null) throw ApiException.bad("真实传输没有预先登记的校准请求");
            ObjectNode parameters = attempt.putObject("parameters")
                .put("model", fast && !settings.fastModel().isBlank() ? settings.fastModel() : settings.model())
                .put("reasoningEffort", settings.effort(fast)).put("temperature", 0.4)
                .put("maxTokens", maxTokens).put("httpTimeoutSeconds", timeoutSeconds)
                .put("serviceDeadlineMillis", 31000).put("provider", settings.provider()).put("preset", settings.preset());
            parameters.set("extraBody", json.valueToTree(settings.extraBody(fast)));
            attempt.put("promptSha256", hash(prompt));
            if (!fast || maxTokens != 800 || timeoutSeconds != 30)
                throw ApiException.bad("校准请求没有使用三十秒等待与八百输出上限，未发送请求");
            Reply reply;
            long started = System.nanoTime();
            try {
                reply = super.exchange(settings, prompt, input, maxTokens, timeoutSeconds, fast, reportUsage);
            } finally {
                attempt.put("transportElapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            attempt.put("rawOutputJson", reply.text()).put("finishReason", reply.finishReason())
                .put("returnedModel", reply.model()).put("systemFingerprint", reply.fingerprint());
            attempt.put("rawOutputScope", "模型message.content原文；不包含上游HTTP外层和请求头");
            return reply;
        }
    }

    /** 同一Service中的混合校准；只有FOLLOWUP允许真实请求，失败或达到上限后拒绝继续。 */
    static class CalibrationGateway implements InterviewModelGateway {
        private final ObjectMapper json;
        private final InterviewModelClient real;
        private final MockInterviewModel mock;
        private final ObjectNode ledger;
        private final ArrayNode attempts;
        private Path evidence;
        private volatile boolean stopped;

        CalibrationGateway(ObjectMapper json, InterviewModelClient real) {
            this.json = json; this.real = real; this.mock = new MockInterviewModel(json);
            ledger = json.createObjectNode().put("kind", "独立追问等待校准")
                .put("runId", UUID.randomUUID().toString()).put("maxRealAttempts", MAX_ATTEMPTS)
                .put("automaticRetries", 0).put("synthetic", true).put("dataDirectory", DATA.toString())
                .put("scope", "只有FOLLOWUP真实；PLAN与REVIEW使用显式Mock，不构成完整真实面试或专业性人工验收")
                .put("status", "PREPARED");
            ledger.putObject("mockCalls").put("PLAN", 0).put("REVIEW", 0);
            attempts = ledger.putArray("attempts");
        }

        synchronized void begin(Path directory) {
            evidence = directory;
            ledger.put("startedAt", Instant.now().toString());
            persist();
        }

        @Override
        public ObjectNode call(String stage, ObjectNode input, int timeoutSeconds) {
            ObjectNode usage = json.createObjectNode();
            ObjectNode result = call(stage, input, timeoutSeconds, usage::setAll);
            if (!usage.isEmpty()) result.set("_modelUsage", usage);
            return result;
        }

        @Override
        public ObjectNode call(String stage, ObjectNode input, int timeoutSeconds, Consumer<ObjectNode> reportUsage) {
            if (!stage.equals("FOLLOWUP")) {
                if (!Set.of("PLAN", "REVIEW").contains(stage)) throw ApiException.bad("校准禁止未声明的模型阶段");
                ObjectNode result = mock.call(stage, input);
                if (stage.equals("PLAN")) {
                    List<String> questions = List.of("假设订单接口收到重复和并发请求，你如何避免重复创建订单？",
                        "如果请求已写入处理中状态，进程随后崩溃，你如何恢复？", "你怎样验证幂等方案在失败和补偿场景下的行为？");
                    for (int i = 0; i < result.path("questions").size(); i++)
                        ((ObjectNode) result.path("questions").get(i)).put("question", questions.get(i));
                }
                if (result.remove("_modelUsage") instanceof ObjectNode usage) reportUsage.accept(usage);
                synchronized (this) {
                    ObjectNode calls = (ObjectNode) ledger.path("mockCalls");
                    calls.put(stage, calls.path(stage).asInt() + 1); persist();
                }
                return result;
            }
            ObjectNode attempt = reserve(input, timeoutSeconds);
            long started = System.nanoTime();
            ACTIVE_ATTEMPT.set(attempt);
            try {
                ObjectNode output = real.call(stage, input, timeoutSeconds, usage -> {
                    attempt.set("usage", usage.deepCopy());
                    reportUsage.accept(usage);
                });
                attempt.set("validatedOutput", output.deepCopy());
                attempt.put("status", "CLIENT_VALIDATED");
                return output;
            } catch (ApiException error) {
                attempt.put("status", "FAILED").put("errorCode", error.code);
                stopped = true;
                throw error;
            } catch (Exception error) {
                attempt.put("status", "FAILED").put("errorCode", "CALIBRATION_UNEXPECTED_ERROR");
                stopped = true;
                throw new ApiException(502, "CALIBRATION_UNEXPECTED_ERROR", "真实校准调用失败，已停止后续请求");
            } finally {
                ACTIVE_ATTEMPT.remove();
                attempt.put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .put("finishedAt", Instant.now().toString());
                attempt.put("usageIncomplete", !attempt.path("usage").path("promptTokens").isIntegralNumber()
                    || !attempt.path("usage").path("completionTokens").isIntegralNumber());
                synchronized (this) { persist(); }
            }
        }

        private synchronized ObjectNode reserve(ObjectNode input, int timeoutSeconds) {
            if (evidence == null || stopped || attempts.size() >= MAX_ATTEMPTS)
                throw ApiException.bad("校准未初始化、已失败或已达到三次真实请求上限");
            int number = attempts.size() + 1;
            ObjectNode attempt = attempts.addObject().put("number", number)
                .put("stage", "FOLLOWUP").put("status", "RESERVED").put("reservedAt", Instant.now().toString())
                .put("observedTimeoutSeconds", timeoutSeconds).put("inputSha256", hash(input.toString()));
            attempt.set("input", input.deepCopy());
            ledger.put("status", "RUNNING");
            persist();
            return attempt;
        }

        synchronized void observeService(int index, ObjectNode publicSession, ObjectNode internal, long elapsed) {
            ObjectNode attempt = (ObjectNode) attempts.get(index);
            attempt.put("serviceElapsedMillis", elapsed);
            attempt.set("serviceMetrics", internal.path("metrics").deepCopy());
            attempt.set("diagnostics", internal.path("diagnostics").deepCopy());
            attempt.put("serviceStatus", internal.path("status").asText())
                .put("publicDiagnosticsHidden", !publicSession.has("diagnostics"));
            persist();
        }

        synchronized void acceptService(int index) {
            ((ObjectNode) attempts.get(index)).put("status", "SERVICE_VALIDATED");
            persist();
        }

        synchronized long transportElapsedMillis(int index) { return attempts.get(index).path("transportElapsedMillis").asLong(); }
        synchronized String returnedModel(int index) { return attempts.get(index).path("returnedModel").asText(""); }

        synchronized void finish(boolean passed, boolean observedSlowResponse, ObjectNode finalSession) {
            ledger.put("status", passed ? "PASSED" : "FAILED").put("finishedAt", Instant.now().toString())
                .put("realAttemptCount", attempts.size()).put("overSixSecondsVerified", observedSlowResponse)
                .put("conclusion", observedSlowResponse ? "已观察到超过六秒的真实追问，经生产校验后被Service保留"
                    : "没有观察到超过六秒的成功真实响应，不能据此声称慢响应路径已获真实验证");
            if (finalSession != null) ledger.set("finalSyntheticSession", finalSession.deepCopy());
            persist();
        }

        private void persist() {
            if (evidence == null) throw new IllegalStateException("校准证据目录尚未初始化");
            try {
                write(evidence.resolve("ledger.json"), ledger);
                for (JsonNode attempt : attempts)
                    write(evidence.resolve("attempt-" + attempt.path("number").asInt() + ".json"), attempt);
            } catch (Exception error) {
                stopped = true;
                throw new IllegalStateException("校准证据无法保存，已停止后续请求");
            }
        }

        private void write(Path path, JsonNode value) throws Exception {
            Path temporary = Files.createTempFile(evidence, "followup-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }
}
