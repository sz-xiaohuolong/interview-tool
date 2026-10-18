package cn.interview.v3.model;

import cn.interview.Application;
import cn.interview.v3.mapper.V3Store;
import cn.interview.v3.service.V3InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 只阻塞模型边界，验证真实编排、原子快照与恢复行为。 */
@SpringBootTest(classes = Application.class, properties = {
    "app.llm.provider=mock", "app.v3.enabled=true",
    "app.v3.knowledge.network-enabled=false", "app.v3.fast-deadline-ms=250"
})
@Import(V3ConcurrencyTest.GatewayConfiguration.class)
class V3ConcurrencyTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("v3-concurrency-"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", DATA::toString);
    }

    @Autowired V3InterviewService service;
    @Autowired V3Store store;
    @Autowired ObjectMapper json;
    @Autowired ControlledGateway gateway;

    @AfterEach
    void 释放测试阻塞并取消尚未结束的合成会话() throws Exception {
        gateway.releaseAll();
        for (ObjectNode session : store.list()) {
            if (!Set.of("REPORT_READY", "CANCELLED").contains(session.path("status").asText())) {
                service.action(session.path("id").asText(), "cancel", action(service.get(session.path("id").asText())));
            }
        }
    }

    @Test
    void 短截止选择备用且迟到结果不能覆盖已显示的问题() throws Exception {
        ObjectNode session = started();
        Gate delayed = gateway.delayNextFollowup();
        long submitted = System.nanoTime();
        ObjectNode thinking = answer(session, "合成回答：我先检查输入校验与事务边界。");
        assertThat(thinking.path("status").asText()).isEqualTo("ANSWERING");
        assertThat(Duration.ofNanos(System.nanoTime() - submitted)).isLessThan(Duration.ofMillis(500));
        delayed.awaitStarted();

        ObjectNode fallback = await(session, s -> s.path("metrics").path("lastFallback").asBoolean());
        assertThat(fallback.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(fallback.path("metrics").path("lastFollowupMs").asLong()).isLessThan(1500);
        assertThat(lastAssistant(fallback).path("fallback").asBoolean()).isTrue();
        assertThat(userAnswers(fallback)).isEqualTo(1);
        String savedMessages = fallback.path("messages").toString();
        int savedRevision = fallback.path("revision").asInt();

        delayed.release();
        delayed.awaitFinished();
        Thread.sleep(60);
        ObjectNode afterLate = service.get(session.path("id").asText());
        assertThat(afterLate.path("messages").toString()).isEqualTo(savedMessages);
        assertThat(afterLate.path("revision").asInt()).isGreaterThan(savedRevision);
        assertThat(afterLate.path("usage").path("reportedCalls").asInt())
            .isEqualTo(fallback.path("usage").path("reportedCalls").asInt() + 1);
    }

    @Test
    void 上轮成功后的旧截止不能提前中断新一轮回答() throws Exception {
        ObjectNode session = started();
        long firstSubmitted = System.nanoTime();
        answer(session, "合成第一轮回答：我负责接口接入。");
        session = await(session, s -> s.path("status").asText().equals("IN_PROGRESS"));
        sleepUntil(firstSubmitted + TimeUnit.MILLISECONDS.toNanos(150));

        Gate second = gateway.delayNextFollowup();
        answer(session, "合成第二轮回答：幂等键会在事务内保存。");
        second.awaitStarted();
        sleepUntil(firstSubmitted + TimeUnit.MILLISECONDS.toNanos(290));
        ObjectNode duringSecond = service.get(session.path("id").asText());
        assertThat(duringSecond.path("status").asText()).isEqualTo("ANSWERING");
        assertThat(userAnswers(duringSecond)).isEqualTo(2);

        second.release();
        second.awaitFinished();
        ObjectNode completed = await(session, s -> s.path("status").asText().equals("IN_PROGRESS"));
        assertThat(completed.path("metrics").path("lastFallback").asBoolean()).isFalse();
        assertThat(lastAssistant(completed).path("fallback").asBoolean()).isFalse();
    }

    @Test
    void 取消与模型返回竞态保留回答且不复活会话() throws Exception {
        ObjectNode session = started();
        Gate delayed = gateway.delayNextFollowup();
        answer(session, "合成回答：取消后仍须保留本回答。");
        delayed.awaitStarted();
        ObjectNode current = service.get(session.path("id").asText());
        ObjectNode cancelled = service.action(session.path("id").asText(), "cancel", action(current));
        int revision = cancelled.path("revision").asInt();
        String savedMessages = cancelled.path("messages").toString();

        delayed.release();
        delayed.awaitFinished();
        Thread.sleep(300);
        ObjectNode afterLate = service.get(session.path("id").asText());
        assertThat(afterLate.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(afterLate.path("revision").asInt()).isGreaterThan(revision);
        assertThat(afterLate.path("usage").path("reportedCalls").asInt())
            .isEqualTo(cancelled.path("usage").path("reportedCalls").asInt() + 1);
        assertThat(afterLate.path("messages").toString()).isEqualTo(savedMessages);
        assertThat(userAnswers(afterLate)).isEqualTo(1);
    }

    @Test
    void 重启恢复只标记中断且不会自动再次调用模型() throws Exception {
        ObjectNode ready = prepared();
        int calls = gateway.calls.get();
        List<String> interruptedIds = new ArrayList<>();
        for (String state : Set.of("QUEUED", "PREPARING", "ANSWERING", "REPORTING")) {
            ObjectNode synthetic = store.get(ready.path("id").asText());
            synthetic.put("id", UUID.randomUUID().toString()).put("status", state);
            synthetic.put("pendingStage", state.equals("ANSWERING") ? "FOLLOWUP" : state.equals("REPORTING") ? "REVIEW" : "PREPARE");
            store.save(synthetic);
            interruptedIds.add(synthetic.path("id").asText());
        }

        service.recover();
        Thread.sleep(100);
        assertThat(gateway.calls.get()).isEqualTo(calls);
        for (String interruptedId : interruptedIds) {
            ObjectNode recovered = service.get(interruptedId);
            assertThat(recovered.path("status").asText()).isEqualTo("INTERRUPTED");
            assertThat(recovered.path("error").asText()).contains("显式重试", "不会自动");
            assertThat(recovered.path("allowedActions").toString()).contains("retry");
        }
    }

    @Test
    void 旧三十二次预算中断可在新预算下显式恢复() throws Exception {
        var session = started();
        answer(session, "合成回答：保留已提交内容。");
        session = await(session, s -> s.path("status").asText().equals("IN_PROGRESS"));
        String id = session.path("id").asText();
        var saved = store.get(id);
        saved.put("status", "INTERRUPTED").put("pendingStage", "FOLLOWUP").put("budgetReached", true);
        ((ObjectNode) saved.path("usage")).put("calls", 32);
        store.save(saved);
        session = service.get(id);
        assertThat(session.path("allowedActions").toString()).contains("retry");
        assertThat(session.path("budgetReached").asBoolean()).isFalse();
        service.action(id, "retry", action(session));
        session = await(session, s -> s.path("status").asText().equals("IN_PROGRESS"));
        assertThat(session.path("usage").path("calls").asInt()).isEqualTo(33);
    }

    @Test
    void 原三十二次以后仍可继续且最后一次额度留给复盘() throws Exception {
        ObjectNode session = started();
        cap(session, 32, 9000);
        answer(session, "合成回答：三十二次后继续训练。");
        session = await(session, s -> s.path("status").asText().equals("IN_PROGRESS"));
        assertThat(session.path("usage").path("calls").asInt()).isEqualTo(33);
        assertThat(session.path("usage").path("maxCalls").asInt()).isEqualTo(128);
        cap(session, 127, 9000);
        answer(session, "合成回答：达到对话预算后保留复盘。");
        session = await(session, s -> s.path("status").asText().equals("INTERRUPTED"));
        assertThat(session.path("usage").path("calls").asInt()).isEqualTo(127);
        service.action(session.path("id").asText(), "finish", action(session));
        session = await(session, s -> s.path("status").asText().equals("REPORT_READY"));
        assertThat(session.path("usage").path("calls").asInt()).isEqualTo(128);
    }

    @Test
    void 调用次数耗尽后保留回答且明确停止而不切换备用题() throws Exception {
        ObjectNode session = started();
        cap(session, 128, 9000);
        int calls = gateway.calls.get();
        answer(session, "合成回答：达到次数预算也不能丢失本回答。");
        ObjectNode stopped = await(session, s -> s.path("status").asText().equals("INTERRUPTED"));
        assertBudgetStop(stopped, calls);
        assertThat(userAnswers(stopped)).isEqualTo(1);
        assertThat(stopped.path("messages").size()).isEqualTo(2);
        assertThat(stopped.path("usage").path("calls").asInt()).isEqualTo(128);
    }

    @Test
    void 输出声明预算不足时不得发送新的模型请求() throws Exception {
        ObjectNode session = started();
        cap(session, 2, 399_500);
        int calls = gateway.calls.get();
        answer(session, "合成回答：剩余输出预算不足一轮追问。");
        ObjectNode stopped = await(session, s -> s.path("status").asText().equals("INTERRUPTED"));
        assertBudgetStop(stopped, calls);
        assertThat(stopped.path("usage").path("declaredOutputLimit").asInt()).isEqualTo(399_500);
    }

    @Test
    void 重新准备时预算耗尽不能继续提示可重试() throws Exception {
        ObjectNode session = prepared();
        cap(session, 128, 9000);
        int calls = gateway.calls.get();
        service.action(session.path("id").asText(), "corrections", action(session).put("correction", "仅负责合成接口的参数校验。"));
        ObjectNode stopped = await(session, s -> !Set.of("QUEUED", "PREPARING").contains(s.path("status").asText()));
        assertBudgetStop(stopped, calls);
    }

    @Test
    void 复盘时预算耗尽不能继续提示可重试() throws Exception {
        ObjectNode session = started();
        answer(session, "合成回答：事务提交前保留幂等状态。");
        session = await(session, s -> s.path("status").asText().equals("IN_PROGRESS"));
        cap(session, 128, 9000);
        int calls = gateway.calls.get();
        service.action(session.path("id").asText(), "finish", action(session));
        ObjectNode stopped = await(session, s -> !s.path("status").asText().equals("REPORTING"));
        assertBudgetStop(stopped, calls);
        assertThat(userAnswers(stopped)).isEqualTo(1);
    }

    private void assertBudgetStop(ObjectNode stopped, int previousCalls) {
        assertThat(gateway.calls.get()).isEqualTo(previousCalls);
        assertThat(stopped.path("budgetReached").asBoolean()).isTrue();
        assertThat(stopped.path("phase").asText()).contains("预算").doesNotContain("可显式重试");
        assertThat(stopped.path("allowedActions").toString()).doesNotContain("retry");
    }

    private void cap(ObjectNode session, int calls, int outputLimit) {
        ObjectNode snapshot = store.get(session.path("id").asText());
        ((ObjectNode) snapshot.path("usage")).put("calls", calls).put("declaredOutputLimit", outputLimit);
        store.save(snapshot);
    }

    private ObjectNode prepared() throws Exception {
        ObjectNode created = service.create(json.createObjectNode().put("requestId", UUID.randomUUID().toString())
            .put("role", "Java 后端工程师").put("level", "JUNIOR").put("durationMinutes", 5));
        return await(created, s -> s.path("status").asText().equals("READY"));
    }

    private ObjectNode started() throws Exception {
        ObjectNode ready = prepared();
        return service.action(ready.path("id").asText(), "confirm", action(ready));
    }

    private ObjectNode answer(ObjectNode session, String content) {
        ObjectNode current = service.get(session.path("id").asText());
        return service.action(session.path("id").asText(), "answers", action(current)
            .put("expectedQuestionId", lastAssistant(current).path("questionId").asText()).put("content", content));
    }

    private ObjectNode action(JsonNode session) {
        return json.createObjectNode().put("requestId", UUID.randomUUID().toString())
            .put("expectedRevision", session.path("revision").asInt());
    }

    private ObjectNode await(JsonNode session, Predicate<ObjectNode> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        ObjectNode current = null;
        while (System.nanoTime() < deadline) {
            current = service.get(session.path("id").asText());
            if (condition.test(current)) return current;
            Thread.sleep(5);
        }
        throw new AssertionError("等待合成会话状态失败：" + current);
    }

    private JsonNode lastAssistant(JsonNode session) {
        JsonNode last = null;
        for (JsonNode message : session.path("messages")) if (message.path("role").asText().equals("ASSISTANT")) last = message;
        if (last == null) throw new AssertionError("合成会话中没有已问出的问题");
        return last;
    }

    private long userAnswers(JsonNode session) {
        long count = 0;
        for (JsonNode message : session.path("messages")) if (message.path("role").asText().equals("USER")) count++;
        return count;
    }

    private static void sleepUntil(long target) throws InterruptedException {
        long remaining = target - System.nanoTime();
        if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean @Primary ControlledGateway controlledGateway(ObjectMapper mapper) { return new ControlledGateway(mapper); }
    }

    static final class ControlledGateway implements V3ModelGateway {
        final AtomicInteger calls = new AtomicInteger();
        private final V3MockModel delegate;
        private final BlockingQueue<Gate> pending = new LinkedBlockingQueue<>();
        private final BlockingQueue<Gate> all = new LinkedBlockingQueue<>();

        ControlledGateway(ObjectMapper json) { delegate = new V3MockModel(json); }

        Gate delayNextFollowup() {
            Gate gate = new Gate();
            all.add(gate);
            pending.add(gate);
            return gate;
        }

        void releaseAll() {
            for (Gate gate : all) gate.release();
            pending.clear();
        }

        @Override public ObjectNode call(String stage, ObjectNode input, int timeoutSeconds) {
            calls.incrementAndGet();
            Gate gate = stage.equals("FOLLOWUP") ? pending.poll() : null;
            try {
                if (gate != null) gate.block();
                return delegate.call(stage, input);
            } finally {
                if (gate != null) gate.finished.countDown();
            }
        }
    }

    static final class Gate {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        void block() {
            started.countDown();
            boolean interrupted = false;
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (released.getCount() > 0 && System.nanoTime() < until) {
                try { released.await(10, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            // 模拟取消后仍可能返回的外部请求，状态保护须由真实服务承担。
            if (interrupted) Thread.currentThread().interrupt();
        }

        void release() { released.countDown(); }
        void awaitStarted() throws InterruptedException { assertThat(started.await(3, TimeUnit.SECONDS)).isTrue(); }
        void awaitFinished() throws InterruptedException { assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue(); }
    }
}
