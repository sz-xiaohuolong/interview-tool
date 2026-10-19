package cn.interview.client.interview;

import cn.interview.client.mock.MockInterviewModel;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.InterviewService;
import cn.interview.service.knowledge.KnowledgeService;
import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 读取实际配置文件并捕获模型边界参数，全程使用合成资料和本机测试替身。 */
class InterviewFollowupTimeoutConfigurationTest {
    private static final String DEADLINE = "app.interview.followup-deadline-ms";
    private static final String LEGACY_DEADLINE = "app.v3.fast-deadline-ms";
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path directory;

    @Test void 默认配置向追问模型传递三十秒等待() throws Exception {
        verifyTimeout(Map.of(), 31_000, 30);
    }

    @Test void 一百二十一秒总截止为追问模型保留一秒余量() throws Exception {
        verifyTimeout(Map.of(DEADLINE, "121000"), 121_000, 120);
    }

    @Test void 超大总截止仍限制为一百二十一秒() throws Exception {
        verifyTimeout(Map.of(DEADLINE, Long.toString(Long.MAX_VALUE)), 121_000, 120);
    }

    @Test void 旧配置别名仍可设置总截止() throws Exception {
        verifyTimeout(Map.of(LEGACY_DEADLINE, "16000"), 16_000, 15);
    }

    @Test void 新旧配置同时存在时新配置优先() throws Exception {
        verifyTimeout(Map.of(DEADLINE, "41000", LEGACY_DEADLINE, "121000"), 41_000, 40);
    }

    @Test void 新环境变量优先于旧配置别名() throws Exception {
        verifyTimeout(Map.of("INTERVIEW_FOLLOWUP_DEADLINE_MS", "51000", LEGACY_DEADLINE, "121000"), 51_000, 50);
    }

    @Test void 短截止仍允许用于并发与降级回归() throws Exception {
        verifyTimeout(Map.of(DEADLINE, "250"), 250, 1);
    }

    private void verifyTimeout(Map<String, String> overrides, long expectedDeadline, int expectedModelTimeout) throws Exception {
        var env = new MockEnvironment().withProperty("app.llm.provider", "mock");
        env.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        overrides.forEach(env::withProperty);
        long configuredDeadline = env.getRequiredProperty(DEADLINE, Long.class);
        var receivedTimeout = new AtomicInteger();
        var mock = new MockInterviewModel(json);
        InterviewModelGateway gateway = (stage, input, timeout) -> {
            if (stage.equals("FOLLOWUP")) receivedTimeout.set(timeout);
            return mock.call(stage, input);
        };
        var knowledge = new KnowledgeService(json, directory.toString(), false);
        var service = new InterviewService(new InterviewSessionStore(json, directory.toString()), json, null,
            gateway, knowledge, new InterviewOutputValidator(json), env, configuredDeadline);
        try {
            assertThat(ReflectionTestUtils.getField(service, "fastDeadline")).isEqualTo(expectedDeadline);
            var created = service.create(json.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("sessionType", "QUICK").put("role", "Java 后端工程师").put("plannedQuestionCount", 3));
            String id = created.path("id").asText();
            var active = awaitStatus(service, id, "IN_PROGRESS");
            service.action(id, "answers", json.createObjectNode().put("requestId", UUID.randomUUID().toString())
                .put("expectedRevision", active.path("revision").asInt())
                .put("expectedQuestionId", active.path("messages").get(0).path("questionId").asText())
                .put("content", "合成回答：我负责请求校验，用唯一请求编号检查重复提交。"));
            awaitStatus(service, id, "IN_PROGRESS");
            assertThat(receivedTimeout).hasValue(expectedModelTimeout);
        } finally {
            service.shutdown();
            knowledge.close();
        }
    }

    private ObjectNode awaitStatus(InterviewService service, String id, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        ObjectNode current = null;
        while (System.nanoTime() < deadline) {
            current = service.get(id);
            if (current.path("status").asText().equals(expected)) return current;
            if (current.path("status").asText().equals("FAILED"))
                throw new AssertionError("合成会话处理失败：" + current);
            Thread.sleep(5);
        }
        throw new AssertionError("等待合成会话状态超时：" + current);
    }
}
