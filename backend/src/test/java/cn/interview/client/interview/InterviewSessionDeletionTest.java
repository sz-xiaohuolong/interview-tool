package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.mock.MockInterviewModel;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.interview.InterviewService;
import cn.interview.testsupport.SyntheticTrainingData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = Application.class, properties = {
    "app.llm.provider=mock", "app.training.enabled=true",  "app.knowledge.network-enabled=false"
})
@AutoConfigureMockMvc
@Import(InterviewSessionDeletionTest.GatewayConfiguration.class)
class InterviewSessionDeletionTest {
    static final Path DATA = SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) { registry.add("app.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InterviewSessionStore store;
    @Autowired InterviewService service;
    @Autowired BlockingGateway gateway;

    @AfterEach void 释放合成模型阻塞() { gateway.release.countDown(); }

    ObjectNode request() { return json.createObjectNode().put("requestId", UUID.randomUUID().toString()); }
    ObjectNode action(JsonNode session) { return request().put("expectedRevision", session.path("revision").asInt()); }
    ObjectNode postJson(String path, JsonNode body) throws Exception {
        return (ObjectNode) json.readTree(mvc.perform(post(path).contentType("application/json").content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ObjectNode create(ObjectNode config) throws Exception { return postJson("/api/v3/sessions", config); }
    ObjectNode create() throws Exception { return create(request().put("role", "合成删除面试").put("level", "JUNIOR")); }
    ObjectNode await(String id, String state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var session = service.get(id);
            if (session.path("status").asText().equals(state)) return session;
            Thread.sleep(10);
        }
        throw new AssertionError("等待合成场次状态超时：" + state);
    }

    @Test void 新版完成记录可重复删除且所有公开入口不可再读写() throws Exception {
        var session = create(); String id = session.path("id").asText();
        session = await(id, "READY");
        session = postJson("/api/v3/sessions/" + id + "/confirm", action(session));
        session = postJson("/api/v3/sessions/" + id + "/answers", action(session)
            .put("expectedQuestionId", session.path("messages").get(0).path("questionId").asText()).put("content", "合成回答：我负责接口开发与事务处理。"));
        session = await(id, "IN_PROGRESS");
        postJson("/api/v3/sessions/" + id + "/finish", action(session));
        await(id, "REPORT_READY");
        var original = store.get(id);
        String questionId = original.path("report").path("findings").get(0).path("questionId").asText();
        var practice = postJson("/api/review-gaps/V3/" + id + "/" + questionId, request());
        String caseId = practice.path("caseId").asText();
        mvc.perform(delete("/api/v3/sessions/" + id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v3/sessions/" + id)).andExpect(status().isNoContent());
        assertThat(service.list()).noneMatch(row -> row.path("id").asText().equals(id));
        mvc.perform(get("/api/v3/sessions/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/review-gaps/V3/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v2/practice-cases/" + caseId)).andExpect(status().isOk());
        for (String operation : new String[]{"confirm", "corrections", "answers", "next", "skip-stage", "finish", "retry", "cancel", "refresh", "transcription-wait", "transcription-resume"})
            mvc.perform(post("/api/v3/sessions/" + id + "/" + operation).contentType("application/json").content(action(original).toString()))
                .andExpect(status().isNotFound());
        var deleted = store.get(id);
        assertThat(deleted.path("deletedAt").asText()).isNotBlank();
        assertThat(deleted.path("messages")).isEqualTo(original.path("messages"));
        assertThat(deleted.path("report")).isEqualTo(original.path("report"));
        assertThat(deleted.path("generation").asInt()).isEqualTo(original.path("generation").asInt() + 1);
        assertThat(deleted.path("revision").asInt()).isEqualTo(original.path("revision").asInt() + 1);
    }

    @Test void 删除持久化且恢复与原创建幂等请求不能复活记录() throws Exception {
        var config = request().put("role", "合成准备场次").put("level", "INTERN");
        var session = create(config); String id = session.path("id").asText();
        await(id, "READY");
        mvc.perform(delete("/api/v3/sessions/" + id)).andExpect(status().isNoContent());
        var deleted = store.get(id);
        var reopened = new InterviewSessionStore(json, DATA.toString());
        assertThat(reopened.get(id).path("deletedAt")).isEqualTo(deleted.path("deletedAt"));
        service.recover();
        assertThat(store.get(id)).isEqualTo(deleted);
        mvc.perform(post("/api/v3/sessions").contentType("application/json").content(config.toString())).andExpect(status().isNotFound());
        assertThat(service.list()).noneMatch(row -> row.path("id").asText().equals(id));
    }

    @Test void 删除准备中场次后迟到模型结果不会复活或继续下一次调用() throws Exception {
        gateway.blockNextAnalysis();
        var session = create(); String id = session.path("id").asText();
        assertThat(gateway.entered.await(3, TimeUnit.SECONDS)).isTrue();
        mvc.perform(delete("/api/v3/sessions/" + id)).andExpect(status().isNoContent());
        var deleted = store.get(id);
        gateway.release.countDown();
        // 慢任务只有一个执行线程；下一场准备完成证明前一场迟到任务已退出。
        var kept = create(); await(kept.path("id").asText(), "READY");
        var after = store.get(id);
        // 已返回的用量仍可累计，原始内容、删除标记和任务代次必须保持。
        var afterContent = after.deepCopy(); afterContent.remove(List.of("usage", "revision", "updatedAt"));
        var beforeContent = deleted.deepCopy(); beforeContent.remove(List.of("usage", "revision", "updatedAt"));
        assertThat(afterContent).isEqualTo(beforeContent);
        assertThat(after.path("usage").path("reportedCalls").asInt()).isEqualTo(1);
        assertThat(service.list()).noneMatch(row -> row.path("id").asText().equals(id));
        assertThat(store.get(id).path("usage").path("calls").asInt()).isEqualTo(1);
        mvc.perform(get("/api/v3/sessions/" + id)).andExpect(status().isNotFound());
    }

    @Test void 不存在与跨源删除不影响其他面试或资料() throws Exception {
        mvc.perform(delete("/api/v3/sessions/" + UUID.randomUUID())).andExpect(status().isNotFound());
        var session = create(); String id = session.path("id").asText(); await(id, "READY");
        mvc.perform(delete("/api/v3/sessions/" + id).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v3/sessions/" + id)).andExpect(status().isOk());
        var material = postJson("/api/materials", json.createObjectNode().put("kind", "PROJECT").put("name", "合成保留项目").put("content", "接口开发与事务处理。"));
        var legacy = postJson("/api/sessions", json.createObjectNode().put("mode", "QUICK").put("durationMinutes", 5));
        mvc.perform(delete("/api/v3/sessions/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/" + legacy.path("id").asText())).andExpect(status().isOk());
        var materials = json.readTree(mvc.perform(get("/api/materials")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(materials).anyMatch(row -> row.path("id").asText().equals(material.path("id").asText()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean @Primary BlockingGateway deletionGateway(ObjectMapper json) { return new BlockingGateway(json); }
    }
    static final class BlockingGateway implements InterviewModelGateway {
        final MockInterviewModel delegate;
        final AtomicReference<Boolean> next = new AtomicReference<>(false);
        volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
        BlockingGateway(ObjectMapper json) { delegate = new MockInterviewModel(json); }
        void blockNextAnalysis() { entered = new CountDownLatch(1); release = new CountDownLatch(1); next.set(true); }
        @Override public ObjectNode call(String stage, ObjectNode input, int timeout) {
            if (stage.equals("ANALYZE") && next.getAndSet(false)) {
                entered.countDown();
                boolean interrupted = false;
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (release.getCount() > 0 && System.nanoTime() < deadline) {
                    try { release.await(10, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return delegate.call(stage, input);
        }
    }
}
