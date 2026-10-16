package cn.interview.service;

import cn.interview.client.V2ModelCapacity;
import cn.interview.mapper.Store;
import cn.interview.testsupport.V2TestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.llm.provider=mock","app.v2.enabled=true"})
@AutoConfigureMockMvc
class V2ModelFlowTest {
    static final Path DATA=V2TestData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired V2ModelCapacity capacity;@Autowired Store store;
    JsonNode call(String path,Object body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    JsonNode draft()throws Exception{
        var s=call("/api/sessions",Map.of("durationMinutes",5));String sid=s.path("id").asText();
        call("/api/sessions/"+sid+"/answers",Map.of("content","用于测试的原始回答","requestId","answer","expectedMessageId",s.path("messages").get(0).path("id").asText()));
        call("/api/sessions/"+sid+"/finish",Map.of());
        var c=call("/api/v2/practice-cases",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","SESSION","sourceId",sid,"messageId",s.path("messages").get(0).path("id").asText()));
        return call("/api/v2/practice-cases/"+c.path("id").asText()+"/attempts",Map.of("requestId","draft","exposureDeclaration","UNKNOWN"));
    }
    @Test void capacityRejectionHappensBeforeSealingAnswer()throws Exception{
        var a=draft();String id=a.path("id").asText();
        try(var lease=capacity.acquire()){
            mvc.perform(post("/api/v2/practice-attempts/"+id+"/submit").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","submit","expectedVersion",0,"answer","保留输入"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_CAPACITY_BUSY"));
        }
        mvc.perform(get("/api/v2/practice-attempts/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }
    @Test void completedOperationCannotBeRetriedOrExposeInternalInputs()throws Exception{
        var a=draft();var ready=call("/api/v2/practice-attempts/"+a.path("id").asText()+"/submit",Map.of("requestId","submit","expectedVersion",0,"answer","已封存的测试正文"));
        String op=ready.path("evaluation").path("operationId").asText();
        assertThat(op).isNotBlank();
        String statusBody=mvc.perform(get("/api/v2/operations/"+op)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(statusBody).doesNotContain("已封存的测试正文","systemPrompt","baseUrl");
        mvc.perform(post("/api/v2/operations/"+op+"/retry").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","retry","expectedGeneration",0))))
            .andExpect(status().isConflict());
    }
}
