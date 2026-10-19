package cn.interview.service;

import cn.interview.testsupport.SyntheticTrainingData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
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
@SpringBootTest(properties={"app.llm.provider=mock","app.training.enabled=true"})
@AutoConfigureMockMvc
class TrainingDesignIntegrationTest {
    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    JsonNode call(String path,Object body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    Map<String,String> artifact(JsonNode stage){var out=new LinkedHashMap<String,String>();stage.path("fieldDefinitions").forEach(f->out.put(f.path("id").asText(),"合成方案："+f.path("label").asText()+"；未知数值需通过压测验证。"));return out;}
    @Test void bothScenariosCompleteFiveStagesOnlyByExplicitServerTransitions()throws Exception{
        for(String scenario:List.of("ORDER_SERVICE","KNOWLEDGE_AGENT")){
            JsonNode s=call("/api/v2/design-sessions",Map.of("requestId",UUID.randomUUID().toString(),"scenarioId",scenario,"scenarioVersion","v2.1","constraints","练习用合成约束"));String id=s.path("id").asText();
            for(int i=0;i<5;i++){
                var stage=s.path("stages").get(i);String stageId=stage.path("id").asText();
                s=call("/api/v2/design-sessions/"+id+"/submit",Map.of("requestId","stage-"+i,"expectedVersion",s.path("revision").asInt(),"stageId",stageId,"artifact",artifact(stage)));
                assertThat(s.path("currentStage").asText()).isEqualTo(stageId);assertThat(s.path("stages").get(i).path("state").asText()).isEqualTo("FEEDBACK_READY");
                if(i<4)s=call("/api/v2/design-sessions/"+id+"/advance",Map.of("requestId","advance-"+i,"expectedVersion",s.path("revision").asInt(),"artifactVersion",1,"acknowledgeOpenIssues",true));
            }
            s=call("/api/v2/design-sessions/"+id+"/finish",Map.of("requestId","finish","expectedVersion",s.path("revision").asInt(),"artifactVersion",1,"acknowledgeOpenIssues",true));assertThat(s.path("state").asText()).isEqualTo("COMPLETED");assertThat(s.path("summary").path("stages")).hasSize(5);
        }
    }
    @Test void missingFieldsCannotSubmitAndReopenMakesFollowingFeedbackStale()throws Exception{
        var s=call("/api/v2/design-sessions",Map.of("requestId",UUID.randomUUID().toString(),"scenarioId","ORDER_SERVICE","scenarioVersion","v2.1"));String id=s.path("id").asText();
        mvc.perform(post("/api/v2/design-sessions/"+id+"/submit").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","empty","expectedVersion",0,"stageId","CLARIFY","artifact",Map.of())))).andExpect(status().isBadRequest());
        s=call("/api/v2/design-sessions/"+id+"/submit",Map.of("requestId","first","expectedVersion",0,"stageId","CLARIFY","artifact",artifact(s.path("stages").get(0))));
        s=call("/api/v2/design-sessions/"+id+"/advance",Map.of("requestId","advance","expectedVersion",s.path("revision").asInt(),"artifactVersion",1,"acknowledgeOpenIssues",true));
        s=call("/api/v2/design-sessions/"+id+"/submit",Map.of("requestId","second","expectedVersion",s.path("revision").asInt(),"stageId","CONSTRAINTS","artifact",artifact(s.path("stages").get(1))));
        s=call("/api/v2/design-sessions/"+id+"/reopen",Map.of("requestId","reopen","expectedVersion",s.path("revision").asInt(),"stageId","CLARIFY","acknowledgeInvalidation",true));
        assertThat(s.path("stages").get(1).path("state").asText()).isEqualTo("STALE");assertThat(s.path("stages").get(1).path("artifactVersions")).hasSize(1);
        mvc.perform(post("/api/v2/design-sessions/"+id+"/finish").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","invalid-finish","expectedVersion",s.path("revision").asInt(),"artifactVersion",1,"acknowledgeOpenIssues",true)))) .andExpect(status().isConflict());
        var ended=call("/api/v2/design-sessions/"+id+"/finish",Map.of("requestId","early","expectedVersion",s.path("revision").asInt(),"early",true,"reason","先保存当前练习"));assertThat(ended.path("state").asText()).isEqualTo("ENDED_EARLY");assertThat(ended.path("summary").path("incompleteStages").size()).isGreaterThan(0);
    }
}
