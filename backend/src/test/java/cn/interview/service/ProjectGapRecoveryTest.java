package cn.interview.service;

import cn.interview.mapper.SqliteStore;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.llm.provider=openai", "app.llm.api-key=synthetic-test-key", "app.llm.model=synthetic-model", "app.training.enabled=true"})
@AutoConfigureMockMvc
class ProjectGapRecoveryTest {
    static final Path DATA = SyntheticTrainingData.migrated();
    static final AtomicInteger REQUESTS = new AtomicInteger();
    static final HttpServer SERVER;
    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/chat/completions", exchange -> {
                try {
                    if (REQUESTS.incrementAndGet() == 1) { exchange.sendResponseHeaders(429, -1); return; }
                    var json = new ObjectMapper();
                    String text = json.writeValueAsString(Map.of("question", "合成订单服务如何压测重复订单请求，并核对唯一键冲突和接口延迟？", "checkpointId", "PERFORMANCE", "projectQuote", "订单唯一键"));
                    byte[] response = json.writeValueAsBytes(Map.of("model", "synthetic-model", "choices", new Object[]{Map.of("finish_reason", "stop", "message", Map.of("content", text))}));
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            SERVER.start();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("app.data-dir", DATA::toString);
        r.add("app.llm.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }
    @AfterAll static void stop() { SERVER.stop(0); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SqliteStore legacy;
    @Autowired TrainingStore db;

    JsonNode getJson(String path) throws Exception { return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    JsonNode postJson(String path, Object body) throws Exception { return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()); }

    @Test void failedGenerationRestoresTheSameCaseAndOnlyExplicitRetryCallsModelAgain() throws Exception {
        var source = db.object().put("id", "project-gap-recovery").put("experienceVersion", 1).put("mode", "PROJECT").put("role", "Java 后端").put("level", "JUNIOR").put("status", "COMPLETED").put("reportStatus", "READY").put("mock", false);
        var project = source.putObject("mainProject").put("id", "p1").put("name", "合成订单服务").put("brief", "我负责订单唯一键与重复请求处理。").put("hypothetical", false);
        source.putArray("messages"); legacy.saveSession(source);
        var report = db.object().put("summary", "合成恢复夹具"); report.set("matrix", new ProjectEvidenceService(json).uncovered(project)); legacy.saveReport(source.path("id").asText(), report);
        String before = legacy.report(source.path("id").asText()).toString();
        String path = "/api/project-evidence/SESSION/project-gap-recovery/gaps/PERFORMANCE";
        var failure = json.readTree(mvc.perform(post(path).contentType("application/json").content("{\"requestId\":\"first\"}"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString());
        String operationId = failure.path("operationId").asText(); assertThat(operationId).isNotBlank();
        assertThat(REQUESTS.get()).isEqualTo(1);
        var restored = getJson(path); assertThat(restored.path("status").asText()).isEqualTo("FAILED");
        String caseId = restored.path("caseId").asText();
        assertThat(postJson(path, Map.of("requestId", "another-tab")).path("caseId").asText()).isEqualTo(caseId);
        assertThat(REQUESTS.get()).isEqualTo(1);
        mvc.perform(post("/api/v2/practice-cases/" + caseId + "/attempts").contentType("application/json").content("{\"requestId\":\"too-early\"}"))
                .andExpect(status().isConflict());
        postJson("/api/v2/operations/" + operationId + "/retry", Map.of("requestId", "explicit-retry", "expectedGeneration", 0));
        assertThat(REQUESTS.get()).isEqualTo(2);
        assertThat(getJson(path).path("status").asText()).isEqualTo("READY");
        assertThat(getJson(path).path("caseId").asText()).isEqualTo(caseId);
        assertThat(legacy.report(source.path("id").asText()).toString()).isEqualTo(before);
        assertThat(db.ids("v2_practice_cases", "source_id", source.path("id").asText())).hasSize(1);
    }
}
