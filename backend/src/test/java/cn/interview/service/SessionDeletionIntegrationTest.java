package cn.interview.service;

import cn.interview.client.FocusedInterviewModelClient;
import cn.interview.mapper.SqliteStore;
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

@SpringBootTest(properties={"app.llm.provider=mock", "app.training.enabled=true"})
@AutoConfigureMockMvc
class SessionDeletionIntegrationTest {
    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SqliteStore store;
    JsonNode postJson(String path,Object body)throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    JsonNode getJson(String path)throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void deletedSessionStaysAbsentAfterRestartAndCannotBeContinued()throws Exception {
        String id=postJson("/api/sessions",Map.of("durationMinutes",5)).path("id").asText();
        var kept=postJson("/api/sessions",Map.of("durationMinutes",5));
        mvc.perform(delete("/api/sessions/"+id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/sessions/"+id)).andExpect(status().isNoContent());
        assertThat(getJson("/api/sessions")).noneMatch(s->s.path("id").asText().equals(id));
        mvc.perform(get("/api/sessions/"+id)).andExpect(status().isNotFound());
        for(String action:new String[]{"answers","retry","finish","report"})
            mvc.perform(post("/api/sessions/"+id+"/"+action).contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound());
        mvc.perform(get("/api/sessions/"+kept.path("id").asText())).andExpect(status().isOk());
        var reopened=new SqliteStore(DATA.toString(),json);
        var recovered=new FocusedInterviewService(reopened,new MaterialService(reopened),new FocusedInterviewModelClient(json,"mock","","","",1,""),json);
        recovered.recover();
        assertThat(recovered.list()).noneMatch(s->s.path("id").asText().equals(id));
    }
    @Test void deletingCompletedHistoryKeepsMaterialsReviewAndPracticeSnapshots()throws Exception {
        var material=postJson("/api/materials",Map.of("kind","PROJECT","name","合成测试项目","content","订单接口和事务回滚。"));
        var s=postJson("/api/sessions",Map.of("mode","PROJECT","materialId",material.path("id").asText(),"durationMinutes",5));
        String id=s.path("id").asText();String messageId=s.path("messages").get(0).path("id").asText();
        postJson("/api/sessions/"+id+"/answers",Map.of("content","我负责使用事务保存订单。","requestId","answer","expectedMessageId",messageId));
        postJson("/api/sessions/"+id+"/finish",Map.of());
        var preview=getJson("/api/v2/review-import-preview?sessionId="+id);
        var review=postJson("/api/v2/review-imports",Map.of("sessionId",id,"expectedReportHash",preview.path("reportHash").asText(),"requestId","import"));
        var practice=postJson("/api/v2/practice-cases",Map.of("sourceKind","SESSION","sourceId",id,"messageId",messageId,"requestId","case"));
        String reportBefore=store.report(id).toString();
        mvc.perform(delete("/api/sessions/"+id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/"+id+"/report")).andExpect(status().isNotFound());
        assertThat(store.material(material.path("id").asText()).name()).isEqualTo("合成测试项目");
        assertThat(getJson("/api/v2/review-items/"+review.path("items").get(0).path("id").asText()).path("sourceSessionId").asText()).isEqualTo(id);
        assertThat(getJson("/api/v2/practice-cases/"+practice.path("id").asText()).path("question").asText()).isEqualTo(practice.path("question").asText());
        postJson("/api/v2/practice-cases/"+practice.path("id").asText()+"/attempts",Map.of("requestId","draft","exposureDeclaration","UNKNOWN"));
        assertThat(store.report(id).toString()).isEqualTo(reportBefore);
    }
    @Test void missingSessionAndCrossOriginDeletionDoNotRemoveAnotherRecord()throws Exception {
        mvc.perform(delete("/api/sessions/missing-session")).andExpect(status().isNotFound());
        var s=postJson("/api/sessions",Map.of("durationMinutes",5));
        String id=s.path("id").asText();
        mvc.perform(delete("/api/sessions/"+id).header("Origin","https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/sessions/"+id)).andExpect(status().isOk());
    }
}
