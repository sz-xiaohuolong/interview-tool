package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 合成流程验收：旧反馈失效后必须逐阶段重评，不能丢失原产物或用旧反馈完成。 */
@SpringBootTest(properties={"app.llm.provider=mock","app.v2.enabled=true"})
@AutoConfigureMockMvc
class V2DesignRevisionFlowTest {
    static final Path DATA=V2TestData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    JsonNode postTo(String path,Object body)throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    JsonNode load(String id)throws Exception {
        return json.readTree(mvc.perform(get("/api/v2/design-sessions/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    Map<String,Object> request(JsonNode session){
        var body=new LinkedHashMap<String,Object>();body.put("requestId",UUID.randomUUID().toString());body.put("expectedVersion",session.path("revision").asInt());return body;
    }
    Map<String,String> artifact(JsonNode stage){
        var out=new LinkedHashMap<String,String>();stage.path("fieldDefinitions").forEach(f->out.put(f.path("id").asText(),"合成产物："+f.path("label").asText()+"；未知容量将通过测量确认。"));return out;
    }
    JsonNode submit(String id,JsonNode session,int index,boolean reuse)throws Exception {
        var body=request(session);body.put("stageId",session.path("stages").get(index).path("id").asText());
        if(reuse)body.put("reuseCurrentArtifact",true);else {
            var fields=artifact(session.path("stages").get(index));fields.put("goals","修订后的合成目标：回答附来源；不足时保留未知。");body.put("artifact",fields);
        }
        return postTo("/api/v2/design-sessions/"+id+"/submit",body);
    }
    JsonNode advance(String id,JsonNode session,int version)throws Exception {
        var body=request(session);body.put("artifactVersion",version);body.put("acknowledgeOpenIssues",true);return postTo("/api/v2/design-sessions/"+id+"/advance",body);
    }
    void rejectWithoutChanging(String id,String action,JsonNode session,Map<String,Object> body,String code)throws Exception {
        mvc.perform(post("/api/v2/design-sessions/"+id+"/"+action).contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
        assertThat(load(id)).isEqualTo(session);
    }

    @Test void revisingFirstStageRequiresReevaluationOfEveryDependentStageAndKeepsHistory()throws Exception {
        for(String scenario:List.of("ORDER_SERVICE","KNOWLEDGE_AGENT")){
            JsonNode session=postTo("/api/v2/design-sessions",Map.of("requestId",UUID.randomUUID().toString(),"scenarioId",scenario));
            String id=session.path("id").asText();var original=new ArrayList<JsonNode>();
            for(int i=0;i<5;i++){
                var body=request(session);body.put("stageId",session.path("stages").get(i).path("id").asText());body.put("artifact",artifact(session.path("stages").get(i)));
                session=postTo("/api/v2/design-sessions/"+id+"/submit",body);original.add(session.path("stages").get(i).path("currentArtifact").deepCopy());
                if(i<4)session=advance(id,session,1);
            }
            var reopen=request(session);reopen.put("stageId","CLARIFY");reopen.put("acknowledgeInvalidation",true);
            session=postTo("/api/v2/design-sessions/"+id+"/reopen",reopen);assertThat(load(id)).isEqualTo(session);
            for(int i=1;i<5;i++){
                assertThat(session.path("stages").get(i).path("state").asText()).isEqualTo("STALE");
                assertThat(session.path("stages").get(i).path("artifactVersions").get(0)).isEqualTo(original.get(i));
            }
            session=submit(id,session,0,false);
            var oldVersion=request(session);oldVersion.put("artifactVersion",1);oldVersion.put("acknowledgeOpenIssues",true);
            rejectWithoutChanging(id,"advance",session,oldVersion,"V2_VERSION_CONFLICT");
            session=advance(id,session,2);
            for(int i=1;i<5;i++){
                assertThat(session.path("currentStage").asText()).isEqualTo(session.path("stages").get(i).path("id").asText());
                var stale=request(session);stale.put("artifactVersion",1);stale.put("acknowledgeOpenIssues",true);
                rejectWithoutChanging(id,i==4?"finish":"advance",session,stale,"V2_STAGE_STALE");
                session=submit(id,session,i,true);
                var current=session.path("stages").get(i).path("currentArtifact");
                assertThat(current.path("version").asInt()).isEqualTo(2);assertThat(current.path("fields")).isEqualTo(original.get(i).path("fields"));
                assertThat(current.path("dependencies")).hasSize(i);
                for(JsonNode dep:current.path("dependencies"))assertThat(dep.path("artifactVersion").asInt()).isEqualTo(2);
                assertThat(load(id)).isEqualTo(session);
                if(i<4)session=advance(id,session,2);
            }
            var finish=request(session);finish.put("artifactVersion",2);finish.put("acknowledgeOpenIssues",true);
            session=postTo("/api/v2/design-sessions/"+id+"/finish",finish);
            assertThat(session.path("state").asText()).isEqualTo("COMPLETED");assertThat(session.path("summary").path("incompleteStages")).isEmpty();
            assertThat(session.path("summary").path("stages")).hasSize(5);
            for(int i=0;i<5;i++){
                var stage=session.path("stages").get(i);assertThat(stage.path("artifactVersions")).hasSize(2);
                assertThat(stage.path("artifactVersions").get(0)).isEqualTo(original.get(i));
                assertThat(session.path("summary").path("stages").get(i).path("artifact").path("version").asInt()).isEqualTo(2);
            }
        }
    }

    @Test void endingWithoutAnySubmittedArtifactListsAllMissingStagesAndDoesNotInventFeedback()throws Exception {
        var session=postTo("/api/v2/design-sessions",Map.of("requestId",UUID.randomUUID().toString(),"scenarioId","KNOWLEDGE_AGENT"));
        String id=session.path("id").asText();var invalid=request(session);invalid.put("artifactVersion",1);invalid.put("acknowledgeOpenIssues",true);
        rejectWithoutChanging(id,"finish",session,invalid,"V2_STAGE_INCOMPLETE");
        var early=request(session);early.put("early",true);early.put("reason","本次只查看练习，未提交任何产物。");
        var ended=postTo("/api/v2/design-sessions/"+id+"/finish",early);
        assertThat(ended.path("state").asText()).isEqualTo("ENDED_EARLY");assertThat(ended.path("summary").path("stages")).isEmpty();
        assertThat(ended.path("summary").path("incompleteStages")).hasSize(5);
        for(JsonNode stage:ended.path("stages")){assertThat(stage.path("artifactVersions")).isEmpty();assertThat(stage.has("currentArtifact")).isFalse();}
        assertThat(ended.path("summary").path("scopeNotice").asText()).contains("未完成").contains("不生成完整能力评价");
    }
}
