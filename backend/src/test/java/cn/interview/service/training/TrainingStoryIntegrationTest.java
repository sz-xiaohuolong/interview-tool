package cn.interview.service.training;

import cn.interview.mapper.SqliteStore;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;

import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class TrainingStoryIntegrationTest {
    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired TrainingStore db;
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

    JsonNode deleteStory(String id,Map<String,Object> body)throws Exception {
        return json.readTree(mvc.perform(delete("/api/v2/stories/"+id).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode page(String query)throws Exception {
        return json.readTree(mvc.perform(get("/api/v2/stories"+query)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @ParameterizedTest @CsvSource({"STAR,false", "INTRO,true"})
    void draftAndArchivedStoriesStayDeletedAfterReopen(String kind,boolean archived)throws Exception {
        var story=call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind",kind));String id=story.path("id").asText();
        if(archived)story=call("/api/v2/stories/"+id+"/archive",Map.of("requestId","archive","expectedVersion",0));
        var body=Map.<String,Object>of("requestId","delete","expectedVersion",story.path("revision").asInt());
        var deleted=deleteStory(id,body);
        assertThat(deleteStory(id,body)).isEqualTo(deleted);
        assertThat(page("?limit=100").path("items")).noneMatch(row->row.path("id").asText().equals(id));
        for(String suffix:List.of("", "/versions", "/versions/"+story.path("currentVersionId").asText()))
            mvc.perform(get("/api/v2/stories/"+id+suffix)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v2/stories/"+id+"/archive").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","restore","expectedVersion",deleted.path("revision").asInt(),"restore",true))))
                .andExpect(status().isNotFound());
        var reopened=new TrainingStore(new SqliteStore(DATA.toString(),json),new MockEnvironment().withProperty("app.training.enabled","true"));
        assertThat(reopened.page("v2_stories",null,100,null).path("items")).noneMatch(row->row.path("id").asText().equals(id));
    }
    @Test void deletingStoryPreservesConfirmedVersionMaterialsAndExistingPractice()throws Exception {
        var material=call("/api/materials",Map.of("kind","PROJECT","name","合成项目资料","content","事务与幂等边界。"));
        var story=call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR","fields",fields(),"materialIds",List.of(material.path("id").asText())));
        String id=story.path("id").asText();var confirmations=new HashMap<String,Object>();fields().keySet().forEach(k->confirmations.put(k,Map.of("confirmed",true)));
        story=call("/api/v2/stories/"+id+"/confirm",Map.of("requestId","confirm","expectedVersion",0,"storyVersion",1,"confirmations",confirmations));
        String version=story.path("currentVersionId").asText();var snapshot=db.get("v2_story_versions",version).deepCopy();
        var practiceBody=Map.of("requestId","practice","sourceKind","STORY","sourceId",id,"storyVersionId",version,"questionTemplateId","PROJECT_CHALLENGE");
        var practice=call("/api/v2/practice-cases",practiceBody);String cid=practice.path("id").asText();
        deleteStory(id,Map.of("requestId","delete","expectedVersion",story.path("revision").asInt()));
        assertThat(db.get("v2_story_versions",version)).isEqualTo(snapshot);
        assertThat(db.database().material(material.path("id").asText()).content()).isEqualTo("事务与幂等边界。");
        mvc.perform(get("/api/v2/practice-cases/"+cid)).andExpect(status().isOk()).andExpect(jsonPath("$.storyVersionId").value(version));
        var attempt=call("/api/v2/practice-cases/"+cid+"/attempts",Map.of("requestId","attempt","exposureDeclaration","UNKNOWN"));
        var ready=call("/api/v2/practice-attempts/"+attempt.path("id").asText()+"/submit",Map.of("requestId","submit","expectedVersion",0,"answer","我通过唯一约束和事务处理重复请求。"));
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        mvc.perform(post("/api/v2/practice-cases").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","new-practice","sourceKind","STORY","sourceId",id,"storyVersionId",version,"questionTemplateId","TEAM_CONFLICT"))))
                .andExpect(status().isNotFound());
    }
    @Test void staleVersionAndRunningGenerationDoNotDeleteStory()throws Exception {
        var story=call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR"));String id=story.path("id").asText();
        mvc.perform(delete("/api/v2/stories/"+id).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","stale","expectedVersion",99))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_VERSION_CONFLICT"));
        var running=db.save("v2_stories",db.get("v2_stories",id).put("generationState","RUNNING"),0);
        mvc.perform(delete("/api/v2/stories/"+id).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","busy","expectedVersion",running.path("revision").asInt()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_RESOURCE_BUSY"));
        assertThat(db.get("v2_stories",id).has("deletedAt")).isFalse();
        db.save("v2_stories",running.put("generationState","NONE"),running.path("revision").asInt());
    }
    @Test void deletedStoriesAreExcludedBeforePaginationLimit()throws Exception {
        for(int i=0;i<5;i++)call("/api/v2/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","INTRO"));
        var first=page("?limit=2");var deletedIds=new HashSet<String>();
        for(JsonNode story:first.path("items")){String id=story.path("id").asText();deletedIds.add(id);deleteStory(id,Map.of("requestId","delete-page","expectedVersion",story.path("revision").asInt()));}
        var remaining=page("?limit=2");assertThat(remaining.path("items")).hasSize(2);
        assertThat(remaining.path("items")).noneMatch(row->deletedIds.contains(row.path("id").asText()));
        assertThat(remaining.hasNonNull("nextCursor")).isTrue();
        String cursor=java.net.URLEncoder.encode(remaining.path("nextCursor").asText(),java.nio.charset.StandardCharsets.UTF_8);
        var next=page("?limit=2&cursor="+cursor);
        var firstIds=new HashSet<String>();remaining.path("items").forEach(row->firstIds.add(row.path("id").asText()));
        assertThat(next.path("items")).noneMatch(row->deletedIds.contains(row.path("id").asText())||firstIds.contains(row.path("id").asText()));
    }

    @ParameterizedTest
    @CsvSource({"STAR,action,task,MATERIAL_QUOTE", "INTRO,contribution,background,MATERIAL_QUOTE", "STAR,action,task,USER_ASSERTION"})
    void editedMaterialQuoteSavesAsUserTextAndPreservesOriginalVersion(String kind,String editedKey,String unchangedKey,String submittedOrigin)throws Exception {
        var raw=json.createObjectNode();
        for(String key:StoryService.FIELDS.get(kind))raw.putObject(key).put("text","合成事实："+key).put("originType","USER_ASSERTION");
        var created=call("/api/training/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind",kind,"fields",raw));
        String id=created.path("id").asText();
        var story=db.get("v2_stories",id);var version=db.get("v2_story_versions",story.path("currentVersionId").asText());
        // 模拟已通过生成校验并保存的材料引用，只在隔离测试库准备快照。
        var snapshots=version.putArray("sourceSnapshots");
        for(String key:List.of(editedKey,unchangedKey)) {
            var field=(com.fasterxml.jackson.databind.node.ObjectNode)version.path("fields").path(key);
            field.put("originType","MATERIAL_QUOTE");
            field.putArray("sourceRefs").addObject().put("kind","QUOTE").put("snapshotId","material:"+key).put("quote",field.path("text").asText());
            snapshots.addObject().put("id","material:"+key).put("purpose","MATERIAL").put("text",field.path("text").asText());
        }
        db.updatePayload("v2_story_versions",version);story.set("fields",version.path("fields").deepCopy());
        db.save("v2_stories",story,story.path("revision").asInt());
        var confirms=new HashMap<String,Object>();StoryService.FIELDS.get(kind).forEach(k->confirms.put(k,Map.of("confirmed",true)));
        var confirmed=call("/api/training/stories/"+id+"/confirm",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",story.path("revision").asInt(),"storyVersion",1,"confirmations",confirms));
        var original=db.get("v2_story_versions",confirmed.path("currentVersionId").asText()).deepCopy();
        var fields=raw.deepCopy();
        for(String key:List.of(editedKey,unchangedKey))((com.fasterxml.jackson.databind.node.ObjectNode)fields.path(key)).put("originType","MATERIAL_QUOTE");
        ((com.fasterxml.jackson.databind.node.ObjectNode)fields.path(editedKey)).put("text","用户补充的边界与方案").put("originType",submittedOrigin);
        var saved=call("/api/training/stories/"+id+"/versions",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",confirmed.path("revision").asInt(),"fields",fields));
        assertThat(saved.path("status").asText()).isEqualTo("DRAFT");
        assertThat(saved.path("fields").path(editedKey).path("originType").asText()).isEqualTo("USER_ASSERTION");
        assertThat(saved.path("fields").path(editedKey).path("sourceRefs")).isEmpty();
        assertThat(saved.path("fields").path(editedKey).path("confirmation").path("confirmed").asBoolean()).isFalse();
        assertThat(saved.path("fields").path(unchangedKey).path("sourceRefs")).isEqualTo(original.path("fields").path(unchangedKey).path("sourceRefs"));
        assertThat(db.get("v2_story_versions",saved.path("currentVersionId").asText()).path("sourceSnapshots")).isEqualTo(original.path("sourceSnapshots"));
        assertThat(db.get("v2_story_versions",confirmed.path("currentVersionId").asText())).isEqualTo(original);
    }
    @Test void forgedMaterialQuoteStillRejected()throws Exception {
        var forged=new LinkedHashMap<>(fields());forged.put("action",Map.of("text","没有引用依据的文字","originType","MATERIAL_QUOTE"));
        mvc.perform(post("/api/training/stories").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR","fields",forged))))
                .andExpect(status().isBadRequest());
        var story=call("/api/training/stories",Map.of("requestId",UUID.randomUUID().toString(),"templateKind","STAR","fields",fields()));
        mvc.perform(post("/api/training/stories/"+story.path("id").asText()+"/versions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",0,"fields",forged))))
                .andExpect(status().isBadRequest());
    }

}
