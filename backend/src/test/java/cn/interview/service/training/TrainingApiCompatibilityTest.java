package cn.interview.service.training;

import cn.interview.testsupport.SyntheticTrainingData;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
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

@SpringBootTest(properties = {"app.llm.provider=mock", "app.training.enabled=true", "app.knowledge.network-enabled=false"})
@AutoConfigureMockMvc
class TrainingApiCompatibilityTest {
    static final Path DATA = SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) { registry.add("app.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void 新训练入口创建后旧地址读取相同记录且重复请求不另建故事() throws Exception {
        var body = json.writeValueAsBytes(Map.of("requestId", UUID.randomUUID().toString(), "templateKind", "STAR"));
        var created = json.readTree(mvc.perform(post("/api/training/stories").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = created.path("id").asText();
        var old = json.readTree(mvc.perform(get("/api/v2/stories/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var current = json.readTree(mvc.perform(get("/api/training/stories/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(old).isEqualTo(current);
        assertThat(old.path("id")).isEqualTo(created.path("id"));
        assertThat(old.path("currentVersionId")).isEqualTo(created.path("currentVersionId"));
        mvc.perform(post("/api/v2/stories").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    JsonNode postJson(String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ObjectNode input() { return json.createObjectNode().put("requestId", UUID.randomUUID().toString()); }
    JsonNode createdAndReplay(String path, ObjectNode body) throws Exception {
        var first = mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isCreated()).andReturn();
        var result = json.readTree(first.getResponse().getContentAsString());
        var alias = path.replace("/api/training", "/api/v2");
        var replay = postJson(alias, body);
        assertThat(replay).isEqualTo(result);
        mvc.perform(post(alias).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        mvc.perform(post(alias).contentType("application/json").content(json.writeValueAsBytes(body.deepCopy().put("contractChange", "不同输入"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_REQUEST_CONFLICT"));
        assertThat(java.util.Collections.list(first.getRequest().getAttributeNames())).noneMatch(name -> name.startsWith("v2.created:"));
        return result;
    }
    @Test void 所有创建入口通过显式业务结果区分首次与兼容地址重放() throws Exception {
        var story = createdAndReplay("/api/training/stories", input().put("templateKind", "STAR"));
        String storyPath = "/api/training/stories/" + story.path("id").asText();
        var versionInput = input().put("expectedVersion", story.path("revision").asInt());versionInput.putObject("fields");
        story = createdAndReplay(storyPath + "/versions", versionInput);
        createdAndReplay(storyPath + "/input-preparations", input().put("expectedVersion", story.path("revision").asInt()));
        createdAndReplay("/api/training/design-sessions", input().put("scenarioId", "ORDER_SERVICE"));

        var source = postJson("/api/sessions", Map.of("durationMinutes", 5));String sid = source.path("id").asText();
        postJson("/api/sessions/" + sid + "/answers", Map.of("content", "合成原答：通过事务回滚处理失败。", "requestId", UUID.randomUUID().toString(), "expectedMessageId", source.path("messages").get(0).path("id").asText()));
        postJson("/api/sessions/" + sid + "/finish", Map.of());
        var practice = createdAndReplay("/api/training/practice-cases", input().put("sourceKind", "SESSION").put("sourceId", sid).put("messageId", source.path("messages").get(0).path("id").asText()));
        String cid = practice.path("id").asText();
        var attempt = createdAndReplay("/api/training/practice-cases/" + cid + "/attempts", input().put("exposureDeclaration", "UNKNOWN"));
        postJson("/api/training/practice-attempts/" + attempt.path("id").asText() + "/submit", input().put("expectedVersion", attempt.path("revision").asInt()).put("answer", "合成新答：明确事务、回滚和幂等边界。"));
        createdAndReplay("/api/training/practice-cases/" + cid + "/evaluation-groups", input().put("attemptId", attempt.path("id").asText()));
        var original = read("/api/training/practice-cases/" + cid + "/attempts").path("items").get(0);
        createdAndReplay("/api/training/practice-comparisons", input().put("mode", "COMPARE").put("leftAttemptId", original.path("id").asText()).put("rightAttemptId", attempt.path("id").asText()));
        createdAndReplay("/api/training/practice-comparisons", input().put("mode", "REEVALUATE_PAIR").put("leftAttemptId", original.path("id").asText()).put("rightAttemptId", attempt.path("id").asText()));
        var preview = read("/api/training/review-import-preview?sessionId=" + sid);
        var imported = createdAndReplay("/api/training/review-imports", input().put("sessionId", sid).put("expectedReportHash", preview.path("reportHash").asText()));
        var item = imported.path("items").get(0);String reviewId = item.path("id").asText();
        var confirm = input().put("expectedVersion", item.path("revision").asInt()).put("action", "CONFIRM");confirm.putArray("skillIds").add("JAVA.TRANSACTIONS");
        postJson("/api/training/review-items/" + reviewId + "/actions", confirm);
        createdAndReplay("/api/training/training-links", input().put("sourceKind", "REVIEW").put("sourceId", reviewId));
    }

    @Test void JD清单诊断和冲刺创建保留正式地址与别名的幂等契约() throws Exception {
        var manifest=createdAndReplay("/api/training/jd-diagnostics/input-preparations",input().put("jdText","熟悉 Java 事务与幂等"));
        var diagnostic=createdAndReplay("/api/training/jd-diagnostics",input().put("manifestId",manifest.path("manifestId").asText()).put("manifestHash",manifest.path("manifestHash").asText()));
        String did=diagnostic.path("id").asText(),rid=diagnostic.path("requirements").get(0).path("id").asText();
        var confirm=input().put("expectedVersion",diagnostic.path("revision").asInt());confirm.putArray("requirementIds").add(rid);
        postJson("/api/training/jd-diagnostics/"+did+"/confirm",confirm);
        var sprint=input().put("confirmedDiagnosticId",did);
        sprint.putArray("actions").addObject().put("requirementId",rid).put("type","VERIFY_REQUIREMENT").put("purpose","核对事务边界").put("completionCondition","说明失败与重试结果");
        createdAndReplay("/api/training/sprints",sprint);
    }
}
