package cn.interview.service.material;

import cn.interview.mapper.SqliteStore;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
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

@SpringBootTest(properties = {"app.llm.provider=mock", "app.training.enabled=true"})
@AutoConfigureMockMvc
class ProjectEvidenceIntegrationTest {
    static final Path DATA = SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SqliteStore store;
    @Autowired TrainingStore db;

    JsonNode postJson(String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode projectSession() throws Exception {
        var p = postJson("/api/materials", Map.of("kind", "PROJECT", "name", "合成订单服务", "content", "我负责订单幂等接口，订单使用唯一键避免重复创建。"));
        return postJson("/api/sessions", Map.of("mode", "PROJECT", "materialId", p.path("id").asText(), "durationMinutes", 5));
    }
    JsonNode completed() throws Exception {
        var s = projectSession();
        postJson("/api/sessions/" + s.path("id").asText() + "/answers", Map.of("content", "我负责订单幂等接口，使用订单号唯一索引；重复请求查询已创建的订单返回。", "requestId", "answer", "expectedMessageId", s.path("messages").get(0).path("id").asText()));
        return postJson("/api/sessions/" + s.path("id").asText() + "/finish", Map.of());
    }

    @Test void newProjectKeepsOneProjectSnapshotAndProducesSevenEvidenceItems() throws Exception {
        var s = completed();
        assertThat(s.path("experienceVersion").asInt()).isEqualTo(1);
        assertThat(s.path("mainProject").path("name").asText()).isEqualTo("合成订单服务");
        var matrix = getJson("/api/sessions/" + s.path("id").asText() + "/report").path("matrix");
        assertThat(matrix.path("items")).hasSize(7);
        assertThat(matrix.path("items").get(0).path("title").asText()).isEqualTo("业务背景与指标");
        assertThat(matrix.toString()).doesNotContain("SUPPORTED");
        for (var item : matrix.path("items")) for (var evidence : item.path("evidence")) {
            assertThat(evidence.path("questionMessageId").asText()).isNotBlank();
            assertThat(s.path("messages").toString()).contains(evidence.path("answerQuote").asText());
        }
    }

    @Test void unansweredProjectItemsAreUncoveredWithoutInventingAbilityJudgment() throws Exception {
        var s = projectSession();
        postJson("/api/sessions/" + s.path("id").asText() + "/finish", Map.of());
        var items = getJson("/api/sessions/" + s.path("id").asText() + "/report").path("matrix").path("items");
        assertThat(items).hasSize(7);
        for (var item : items) {
            assertThat(item.path("status").asText()).isEqualTo("UNCOVERED");
            assertThat(item.path("evidence")).isEmpty();
        }
    }

    @Test void unversionedHistoryIsReadWithoutAddingMatrixOrChangingStoredReport() throws Exception {
        var s = completed(); String sid = s.path("id").asText();
        var saved = store.session(sid); saved.remove("experienceVersion"); saved.remove("mainProject"); store.saveSession(saved);
        var report = store.report(sid); report.remove("matrix"); store.saveReport(sid, report);
        String beforeSession = store.session(sid).toString(), beforeReport = store.report(sid).toString();
        assertThat(getJson("/api/sessions/" + sid + "/report").has("matrix")).isFalse();
        assertThat(getJson("/api/sessions/" + sid).has("experienceVersion")).isFalse();
        assertThat(store.session(sid).toString()).isEqualTo(beforeSession);
        assertThat(store.report(sid).toString()).isEqualTo(beforeReport);
    }

    @Test void gapCreatesOnlyOneNewPracticeCaseAndKeepsOriginalReportImmutable() throws Exception {
        var s = completed(); String sid = s.path("id").asText();
        String before = store.report(sid).toString();
        String path = "/api/project-evidence/SESSION/" + sid + "/gaps/PERFORMANCE";
        var first = postJson(path, Map.of("requestId", "gap-one"));
        var duplicate = postJson(path, Map.of("requestId", "gap-two"));
        assertThat(first.path("caseId").asText()).isNotBlank().isEqualTo(duplicate.path("caseId").asText());
        var restored = getJson(path);
        assertThat(restored.path("caseId").asText()).isEqualTo(first.path("caseId").asText());
        var practice = getJson("/api/v2/practice-cases/" + first.path("caseId").asText());
        assertThat(practice.path("contextOrigin").asText()).isEqualTo("PROJECT_GAP");
        assertThat(practice.path("checkpointId").asText()).isEqualTo("PERFORMANCE");
        assertThat(practice.path("question").asText()).contains("合成订单服务");
        assertThat(practice.path("context").asText()).contains("订单幂等接口");
        assertThat(practice.path("generationProtocol").has("promptText")).isFalse();
        var listed = getJson("/api/v2/practice-cases?sourceId=" + sid).path("items").get(0);
        assertThat(listed.has("generationProtocol")).isFalse();
        assertThat(listed.has("mainProject")).isFalse();
        assertThat(store.report(sid).toString()).isEqualTo(before);
        var attempt = postJson("/api/v2/practice-cases/" + first.path("caseId").asText() + "/attempts", Map.of("requestId", "draft", "exposureDeclaration", "NOT_SEEN"));
        postJson("/api/v2/practice-attempts/" + attempt.path("id").asText() + "/submit", Map.of("requestId", "submit", "expectedVersion", 0, "answer", "先测重复请求比例，再对比唯一索引冲突率与接口分位延迟。"));
        assertThat(getJson(path).path("latestAttempt").path("status").asText()).isEqualTo("READY");
        assertThat(store.report(sid).toString()).isEqualTo(before);
    }

    @Test void unversionedAndHypotheticalSourcesCannotTriggerHiddenBackfill() throws Exception {
        var s = completed(); String sid = s.path("id").asText();
        var saved = store.session(sid); saved.remove("experienceVersion"); store.saveSession(saved);
        mvc.perform(post("/api/project-evidence/SESSION/" + sid + "/gaps/PERFORMANCE").contentType("application/json").content("{\"requestId\":\"old\"}"))
                .andExpect(status().isConflict());
        saved.put("experienceVersion", 1); ((com.fasterxml.jackson.databind.node.ObjectNode) saved.path("mainProject")).put("hypothetical", true); store.saveSession(saved);
        mvc.perform(post("/api/project-evidence/SESSION/" + sid + "/gaps/PERFORMANCE").contentType("application/json").content("{\"requestId\":\"hypothetical\"}"))
                .andExpect(status().isConflict());
        assertThat(db.ids("v2_practice_cases", "source_id", sid)).isEmpty();
    }

    @Test void sharedGapWritesKeepTheLocalOriginBoundary() throws Exception {
        var s = completed();
        mvc.perform(post("/api/project-evidence/SESSION/" + s.path("id").asText() + "/gaps/PERFORMANCE").header("Origin", "https://untrusted.example")
                .contentType("application/json").content("{\"requestId\":\"cross-origin\"}"))
                .andExpect(status().isForbidden());
        assertThat(db.ids("v2_practice_cases", "source_id", s.path("id").asText())).isEmpty();
    }
}
