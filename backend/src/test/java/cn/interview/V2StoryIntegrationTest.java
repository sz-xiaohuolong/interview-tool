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

@SpringBootTest(properties={"app.llm.provider=mock","app.v2.enabled=true"})
@AutoConfigureMockMvc
class V2StoryIntegrationTest {
    static final Path DATA=V2TestData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    JsonNode call(String path,Object body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    Map<String,Object> fields(){var fields=new LinkedHashMap<String,Object>();for(String name:List.of("situation","task","action","result","reflection"))fields.put(name,Map.of("text",name.equals("action")?"我实现了订单接口的幂等校验":"本人提供的合成经历："+name,"originType","USER_ASSERTION"));return fields;}
    @Test void missingFactsStayEmptyAndPersonalActionCannotBeNotApplicable()throws Exception{
        var story=call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR"));
        assertThat(story.path("fields").path("action").path("text").asText()).isEmpty();String id=story.path("id").asText();
        mvc.perform(post("/api/v2/stories/"+id+"/confirm").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","confirm","expectedVersion",0,"storyVersion",1,"confirmations",Map.of("action",Map.of("notApplicable",true,"reason","未知")))))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v2/practice-cases").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","STORY","sourceId",id,"storyVersionId",story.path("currentVersionId").asText(),"questionTemplateId","PROJECT_CHALLENGE")))) .andExpect(status().isConflict());
    }
    @Test void confirmedVersionCanBeReusedAndEditingCreatesIndependentDraft()throws Exception{
        var story=call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR","fields",fields()));String id=story.path("id").asText();var confirmations=new HashMap<String,Object>();fields().keySet().forEach(k->confirmations.put(k,Map.of("confirmed",true)));
        story=call("/api/v2/stories/"+id+"/confirm",Map.of("requestId","confirm","expectedVersion",0,"storyVersion",1,"confirmations",confirmations));String versionId=story.path("currentVersionId").asText();
        var c=call("/api/v2/practice-cases",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","STORY","sourceId",id,"storyVersionId",versionId,"questionTemplateId","PROJECT_CHALLENGE"));
        assertThat(c.path("sourceKind").asText()).isEqualTo("STORY");assertThat(c.path("analysisExposure").asText()).isEqualTo("PROVIDED");
        var next=new LinkedHashMap<>(fields());next.put("action",Map.of("text","我增加了失败重试边界","originType","USER_ASSERTION"));
        var edited=call("/api/v2/stories/"+id+"/versions",Map.of("requestId","edit","expectedVersion",story.path("revision").asInt(),"fields",next));
        assertThat(edited.path("currentVersion").asInt()).isEqualTo(2);assertThat(edited.path("fields").path("action").path("confirmation").path("confirmed").asBoolean()).isFalse();
        mvc.perform(get("/api/v2/stories/"+id+"/versions/"+versionId)).andExpect(status().isOk()).andExpect(jsonPath("$.fields.action.text").value("我实现了订单接口的幂等校验"));
        mvc.perform(post("/api/v2/stories/"+id+"/confirm").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","old-confirm","expectedVersion",story.path("revision").asInt(),"storyVersion",1,"confirmations",confirmations)))) .andExpect(status().isConflict());
    }

    @Test void sameConfirmedStorySupportsIndependentQuestionsAndRetainsDeletedSourceAfterArchive() throws Exception {
        String materialText="合成项目来源：团队维护订单服务，个人负责接口幂等。";
        var material=call("/api/materials",Map.of("kind","PROJECT","name","合成故事来源","content",materialText));
        var story=call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR","fields",fields()));String id=story.path("id").asText();
        var preview=call("/api/v2/stories/"+id+"/input-preparations",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",story.path("revision").asInt(),"materialIds",new String[]{material.path("id").asText()}));
        story=call("/api/v2/stories/"+id+"/draft-generation",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",story.path("revision").asInt(),"manifestId",preview.path("manifestId").asText(),"manifestHash",preview.path("manifestHash").asText()));
        var confirmations=new HashMap<String,Object>();fields().keySet().forEach(k->confirmations.put(k,Map.of("confirmed",true)));
        story=call("/api/v2/stories/"+id+"/confirm",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",story.path("revision").asInt(),"storyVersion",story.path("currentVersion").asInt(),"confirmations",confirmations));
        String version=story.path("currentVersionId").asText();var attempts=new ArrayList<String>();var cases=new ArrayList<String>();
        for(String template:new String[]{"PROJECT_CHALLENGE","TEAM_CONFLICT"}) {
            var c=call("/api/v2/practice-cases",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","STORY","sourceId",id,"storyVersionId",version,"questionTemplateId",template));cases.add(c.path("id").asText());
            var attempt=call("/api/v2/practice-cases/"+c.path("id").asText()+"/attempts",Map.of("requestId",UUID.randomUUID().toString(),"exposureDeclaration","SEEN"));
            var ready=call("/api/v2/practice-attempts/"+attempt.path("id").asText()+"/submit",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",0,"answer","针对 "+template+" 的独立合成回答，个人负责幂等校验，结果没有数字证据。"));attempts.add(ready.path("id").asText());
            assertThat(ready.path("status").asText()).isEqualTo("READY");
        }
        assertThat(cases.get(0)).isNotEqualTo(cases.get(1));assertThat(attempts.get(0)).isNotEqualTo(attempts.get(1));
        mvc.perform(delete("/api/materials/"+material.path("id").asText())).andExpect(status().is2xxSuccessful());
        var detail=json.readTree(mvc.perform(get("/api/v2/stories/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("sourcesChanged").asBoolean()).isTrue();assertThat(detail.path("currentVersionDetail").path("sourceSnapshots").toString()).contains(materialText);
        var archived=call("/api/v2/stories/"+id+"/archive",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",detail.path("revision").asInt()));
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");
        mvc.perform(get("/api/v2/stories/"+id+"/versions/"+version)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        for(int i=0;i<attempts.size();i++) {
            var saved=json.readTree(mvc.perform(get("/api/v2/practice-attempts/"+attempts.get(i))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(saved.path("answerText").asText()).contains(i==0?"PROJECT_CHALLENGE":"TEAM_CONFLICT");assertThat(saved.path("status").asText()).isEqualTo("READY");
            mvc.perform(get("/api/v2/practice-cases/"+cases.get(i))).andExpect(status().isOk()).andExpect(jsonPath("$.storyVersionId").value(version));
        }
    }

}
