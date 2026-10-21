package cn.interview.service.source;

import cn.interview.client.source.GitHubCodeClient;
import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.mapper.SqliteStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.llm.provider=mock","app.knowledge.network-enabled=false"})
@AutoConfigureMockMvc
class SourceInterviewIntegrationTest {
    static final Path DATA;static{try{DATA=Files.createTempDirectory("source-interview-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void data(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired SqliteStore focusedStore;@Autowired InterviewSessionStore store;
    @MockitoBean GitHubCodeClient github;
    @BeforeEach void source()throws Exception {
        when(github.resolve("acme/orders")).thenAnswer(inv->json.createObjectNode().put("repository","acme/orders").put("url","https://github.com/acme/orders").put("commitSha","a".repeat(40)).put("status","READY"));
        var tree=json.createObjectNode();tree.putArray("files").addObject().put("path","src/Orders.java");
        when(github.tree(any())).thenReturn(tree);
        when(github.file(any(),any(),eq("src/Orders.java"))).thenReturn("class Orders {\n void save() { throw new IllegalStateException(); }\n}\n");
    }
    JsonNode post(String path,ObjectNode body)throws Exception {return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    JsonNode get(String path)throws Exception {return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    ObjectNode request(){return json.createObjectNode().put("requestId",UUID.randomUUID().toString());}
    JsonNode project()throws Exception{return post("/api/materials",json.createObjectNode().put("kind","PROJECT").put("name","合成订单项目").put("content","使用 Java 开发订单接口；本人负责参数校验与异常处理。").put("githubUrl","https://github.com/acme/orders"));}
    JsonNode waitFor(String id,String state)throws Exception {for(int i=0;i<200;i++){var s=get("/api/interviews/"+id);if(s.path("status").asText().equals(state))return s;Thread.sleep(20);}throw new AssertionError(get("/api/interviews/"+id));}
    @Test void 相同仓库的失败关联可通过保存恢复且项目正文保持()throws Exception {
        when(github.resolve("acme/orders")).thenThrow(GitHubCodeClient.failure("SOURCE_TOO_LARGE","GitHub 响应超过读取上限，未自动重试"))
            .thenReturn(json.createObjectNode().put("repository","acme/orders").put("url","https://github.com/acme/orders")
                .put("branch","main").put("commitSha","b".repeat(40)).put("status","READY"));
        var failed=project();
        assertThat(failed.path("repository").path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("repository").path("error").asText()).contains("读取上限").doesNotContain("超时");
        var body=json.createObjectNode().put("name",failed.path("name").asText()).put("content",failed.path("content").asText())
            .put("githubUrl",failed.path("repository").path("url").asText());
        var result=json.readTree(mvc.perform(put("/api/materials/"+failed.path("id").asText()).contentType("application/json")
            .content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("repository").path("status").asText()).isEqualTo("READY");
        assertThat(result.path("repository").path("commitSha").asText()).isEqualTo("b".repeat(40));
        assertThat(result.path("name")).isEqualTo(failed.path("name"));
        assertThat(result.path("content")).isEqualTo(failed.path("content"));
        assertThat(get("/api/materials").toString()).contains("b".repeat(40));
    }

    @Test void focusedSnapshotSurvivesUnbindingAndOnlyReportExposesCode()throws Exception {
        var project=project();String pid=project.path("id").asText();
        assertThat(project.path("repository").path("commitSha").asText()).isEqualTo("a".repeat(40));
        var s=post("/api/sessions",json.createObjectNode().put("mode","PROJECT").put("materialId",pid).put("durationMinutes",5));String id=s.path("id").asText();
        assertThat(s.path("sourceCode").path("status").asText()).isEqualTo("READY");
        assertThat(s.path("messages").get(0).path("hasCodeEvidence").asBoolean()).isTrue();
        assertThat(s.toString()).doesNotContain("IllegalStateException","snippet","codeEvidenceIds");
        mvc.perform(put("/api/materials/"+pid).contentType("application/json").content("{\"name\":\"合成订单项目\",\"content\":\"修改后的项目说明\",\"githubUrl\":\"\"}")).andExpect(status().isOk());
        post("/api/sessions/"+id+"/finish",json.createObjectNode());
        var report=get("/api/sessions/"+id+"/report");
        assertThat(report.path("sourceCode").path("commitSha").asText()).isEqualTo("a".repeat(40));
        assertThat(report.path("sourceCode").path("evidence").toString()).contains("IllegalStateException");
        assertThat(report.path("sourceQuestions").size()).isEqualTo(1);
        assertThat(new SqliteStore(DATA.toString(),json).session(id).path("sourceCode")).isEqualTo(focusedStore.session(id).path("sourceCode"));
    }
    @Test void comprehensiveUsesActualEndpointAndKeepsSourceVersionOnRefresh()throws Exception {
        var project=project();var s=post("/api/interviews",request().put("role","Java后端").put("level","JUNIOR").put("projectId",project.path("id").asText()));String id=s.path("id").asText();s=waitFor(id,"READY");
        assertThat(s.path("sourceCode").path("status").asText()).isEqualTo("READY");
        assertThat(s.toString()).doesNotContain("IllegalStateException","snippet","codeEvidenceIds");
        when(github.resolve("acme/orders")).thenReturn(json.createObjectNode().put("repository","acme/orders").put("commitSha","b".repeat(40)));
        post("/api/interviews/"+id+"/refresh",request().put("expectedRevision",s.path("revision").asInt()));s=waitFor(id,"READY");
        assertThat(s.path("sourceCode").path("commitSha").asText()).isEqualTo("a".repeat(40));
        assertThat(store.get(id).path("questions").findValues("codeEvidenceIds")).anyMatch(refs->!refs.isEmpty());
        post("/api/interviews/"+id+"/confirm",request().put("expectedRevision",s.path("revision").asInt()));s=get("/api/interviews/"+id);
        post("/api/interviews/"+id+"/finish",request().put("expectedRevision",s.path("revision").asInt()));s=waitFor(id,"REPORT_READY");
        assertThat(s.path("report").path("sourceCode").path("evidence").toString()).contains("IllegalStateException");
    }
    @Test void completeSourceFailureNeedsExplicitContinueAndRetryKeepsCommit()throws Exception {
        var project=project();when(github.tree(any())).thenThrow(new ApiException(503,"SOURCE_RATE_LIMITED","GitHub 限流"));
        var s=post("/api/interviews",request().put("role","Java后端").put("level","JUNIOR").put("projectId",project.path("id").asText()));String id=s.path("id").asText();s=waitFor(id,"FAILED");
        assertThat(s.path("sourceCode").path("status").asText()).isEqualTo("FAILED");assertThat(s.path("messages").isEmpty()).isTrue();
        assertThat(s.path("allowedActions").toString()).contains("continue-without-source");
        post("/api/interviews/"+id+"/continue-without-source",request().put("expectedRevision",s.path("revision").asInt()));s=waitFor(id,"READY");
        assertThat(s.path("sourceCode").path("status").asText()).isEqualTo("SKIPPED");
        var focused=post("/api/sessions",json.createObjectNode().put("mode","PROJECT").put("materialId",project.path("id").asText()).put("durationMinutes",5));String fid=focused.path("id").asText();
        assertThat(focused.path("status").asText()).isEqualTo("FAILED");
        focused=post("/api/sessions/"+fid+"/continue-without-source",json.createObjectNode());
        assertThat(focused.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(focused.path("sourceCode").path("commitSha").asText()).isEqualTo("a".repeat(40));
    }
}
