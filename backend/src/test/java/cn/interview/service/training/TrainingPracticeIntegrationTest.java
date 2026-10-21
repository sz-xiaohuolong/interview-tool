package cn.interview.service.training;

import cn.interview.mapper.SqliteStore;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;

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

@SpringBootTest(properties={"app.llm.provider=mock", "app.training.enabled=true"})
@AutoConfigureMockMvc
class TrainingPracticeIntegrationTest {
    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired SqliteStore legacy; @Autowired TrainingStore db;
    JsonNode postJson(String path,Object body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    JsonNode getJson(String path)throws Exception{return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    JsonNode source()throws Exception {
        var s=postJson("/api/sessions",Map.of("durationMinutes",5));String sid=s.path("id").asText();
        s=postJson("/api/sessions/"+sid+"/answers",Map.of("content","原始主问题回答，不能覆盖。","requestId","answer-main","expectedMessageId",s.path("messages").get(0).path("id").asText()));
        s=postJson("/api/sessions/"+sid+"/answers",Map.of("content","原始追问回答，必须独立选择。","requestId","answer-follow","expectedMessageId",s.path("messages").get(2).path("id").asText()));
        return postJson("/api/sessions/"+sid+"/finish",Map.of());
    }
    JsonNode caseFor(JsonNode source,int message)throws Exception {return postJson("/api/v2/practice-cases",Map.of("sourceKind","SESSION","sourceId",source.path("id").asText(),"messageId",source.path("messages").get(message).path("id").asText(),"requestId",UUID.randomUUID().toString()));}
    JsonNode attempt(String caseId,String answer)throws Exception {
        var a=postJson("/api/v2/practice-cases/"+caseId+"/attempts",Map.of("requestId",UUID.randomUUID().toString(),"exposureDeclaration","NOT_SEEN"));
        return postJson("/api/v2/practice-attempts/"+a.path("id").asText()+"/submit",Map.of("requestId","submit","expectedVersion",0,"answer",answer));
    }
    @Test void 删除独立案例保留原始问答且幂等并阻止旧作答入口()throws Exception {
        var source=source();var c=caseFor(source,0);var other=caseFor(source,2);String cid=c.path("id").asText();
        var draft=postJson("/api/training/practice-cases/"+cid+"/attempts",Map.of("requestId","delete-draft","exposureDeclaration","UNKNOWN"));
        var before=legacy.session(source.path("id").asText());
        var body=Map.of("requestId","delete-case","expectedVersion",c.path("revision").asInt());
        for(int i=0;i<2;i++)mvc.perform(delete("/api/training/practice-cases/"+cid).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk());
        mvc.perform(get("/api/v2/practice-cases/"+cid)).andExpect(status().isNotFound());
        mvc.perform(get("/api/training/practice-attempts/"+draft.path("id").asText())).andExpect(status().isNotFound());
        mvc.perform(post("/api/training/practice-attempts/"+draft.path("id").asText()+"/submit").contentType("application/json")
            .content(json.writeValueAsBytes(Map.of("requestId","after-delete","expectedVersion",0,"answer","不可继续写入")))).andExpect(status().isNotFound());
        assertThat(getJson("/api/training/practice-cases?sourceId="+source.path("id").asText()).path("items").findValuesAsText("id"))
            .contains(other.path("id").asText()).doesNotContain(cid);
        assertThat(legacy.session(source.path("id").asText())).isEqualTo(before);
    }

    @Test void 删除复习整题同时隐藏相关案例且不复活已删项()throws Exception {
        var source=source();var c=caseFor(source,0);
        var preview=getJson("/api/training/review-import-preview?sessionId="+source.path("id").asText());
        var imported=postJson("/api/training/review-imports",Map.of("requestId","import-delete","sessionId",source.path("id").asText(),"expectedReportHash",preview.path("reportHash").asText()));
        var item=imported.path("items").get(0);String rid=item.path("id").asText();
        mvc.perform(delete("/api/v2/review-items/"+rid).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","delete-review","expectedVersion",item.path("revision").asInt())))).andExpect(status().isOk());
        mvc.perform(get("/api/training/review-items/"+rid)).andExpect(status().isNotFound());
        mvc.perform(get("/api/training/practice-cases/"+c.path("id").asText())).andExpect(status().isNotFound());
        var again=postJson("/api/training/review-imports",Map.of("requestId","import-after-delete","sessionId",source.path("id").asText(),"expectedReportHash",preview.path("reportHash").asText()));
        assertThat(again.path("items").findValuesAsText("id")).doesNotContain(rid);
        assertThat(getJson("/api/training/review-items?limit=100").path("items").findValuesAsText("id")).doesNotContain(rid);
    }

    @Test void 删除修订冲突或评估运行中不得移除记录()throws Exception {
        var c=caseFor(source(),0);String cid=c.path("id").asText();
        mvc.perform(delete("/api/training/practice-cases/"+cid).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","stale-delete","expectedVersion",99)))).andExpect(status().isConflict());
        var running=db.object().put("caseId",cid).put("status","RUNNING");running.putArray("attemptIds");db.atomic(()->db.insert("v2_practice_evaluation_groups",running));
        mvc.perform(delete("/api/training/practice-cases/"+cid).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","busy-delete","expectedVersion",0)))).andExpect(status().isConflict());
        assertThat(getJson("/api/training/practice-cases/"+cid).path("id").asText()).isEqualTo(cid);
    }

    @Test void 整题删除检查所有关联运行状态并隔离同ID综合来源()throws Exception {
        var source=source();var first=caseFor(source,0);var second=caseFor(source,2);
        var independent=(com.fasterxml.jackson.databind.node.ObjectNode)first.deepCopy();independent.remove("id");independent.put("familyId",UUID.randomUUID().toString());independent.put("sourceSessionKind","V3");independent.put("contextOrigin","REVIEW_GAP");
        db.atomic(()->db.insert("v2_practice_cases",(com.fasterxml.jackson.databind.node.ObjectNode)independent));
        var preview=getJson("/api/training/review-import-preview?sessionId="+source.path("id").asText());
        var item=postJson("/api/training/review-imports",Map.of("requestId","atomic-import","sessionId",source.path("id").asText(),"expectedReportHash",preview.path("reportHash").asText())).path("items").get(0);
        var running=db.object().put("caseId",second.path("id").asText()).put("status","RUNNING");running.putArray("attemptIds");
        db.atomic(()->db.insert("v2_practice_evaluation_groups",running));
        String path="/api/training/review-items/"+item.path("id").asText();
        mvc.perform(delete(path).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","atomic-busy","expectedVersion",item.path("revision").asInt())))).andExpect(status().isConflict());
        assertThat(getJson("/api/training/practice-cases/"+first.path("id").asText()).has("deletedAt")).isFalse();
        assertThat(getJson(path).has("deletedAt")).isFalse();
        running.put("status","READY");db.atomic(()->db.save("v2_practice_evaluation_groups",running,0));
        mvc.perform(delete(path).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","atomic-delete","expectedVersion",item.path("revision").asInt())))).andExpect(status().isOk());
        assertThat(getJson("/api/training/practice-cases/"+independent.path("id").asText()).path("sourceSessionKind").asText()).isEqualTo("V3");
    }

    @Test void mainAndFollowUpFreezeSeparateQuestionsAndNeverLeakTargetAnswer()throws Exception {
        var s=source();String before=legacy.session(s.path("id").asText()).toString();
        var main=caseFor(s,0);var follow=caseFor(s,2);
        assertThat(main.path("question").asText()).isEqualTo(s.path("messages").get(0).path("content").asText());
        assertThat(follow.path("priorDialogue")).hasSize(2);
        assertThat(follow.toString()).doesNotContain("原始追问回答");
        assertThat(follow.has("referenceAnalysis")).isFalse();
        assertThat(legacy.session(s.path("id").asText()).toString()).isEqualTo(before);
    }
    @Test void independentAnswersAreImmutableAndVisibleExposureCannotBeCleared()throws Exception {
        var s=source();var c=caseFor(s,0);String id=c.path("id").asText();
        var first=attempt(id,"第一次新回答：使用事务。");var second=attempt(id,"第二次新回答：说明回滚与幂等边界。");
        assertThat(first.path("id").asText()).isNotEqualTo(second.path("id").asText());
        assertThat(first.path("status").asText()).isEqualTo("READY");
        assertThat(first.path("effectiveExposure").asText()).isEqualTo("PROVIDED");
        mvc.perform(put("/api/v2/practice-attempts/"+first.path("id").asText()+"/draft").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","overwrite","expectedVersion",first.path("revision").asInt(),"answer","覆盖原答案"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_ANSWER_SEALED"));
        var original=getJson("/api/v2/practice-attempts/"+first.path("id").asText());
        assertThat(original.path("answerText").asText()).isEqualTo("第一次新回答：使用事务。");
        assertThat(original.path("evaluation").path("protocol").path("mock").asBoolean()).isTrue();
    }
    @Test void historicalOriginalNeedsExplicitPairReevaluationAndKeepsUnknownMetadata()throws Exception {
        var c=caseFor(source(),0);String id=c.path("id").asText();
        var baseline=getJson("/api/v2/practice-cases/"+id+"/attempts").path("items").get(0);
        assertThat(baseline.path("status").asText()).isEqualTo("IMPORTED");
        assertThat(baseline.path("protocolStatus").asText()).isEqualTo("UNKNOWN");
        var next=attempt(id,"新增回答，事实需要验证。");
        var compared=postJson("/api/v2/practice-comparisons",Map.of("requestId","text-only","leftAttemptId",baseline.path("id").asText(),"rightAttemptId",next.path("id").asText(),"mode","COMPARE"));
        assertThat(compared.path("comparability").asText()).isEqualTo("TEXT_ONLY");
        assertThat(compared.path("result").isMissingNode()).isTrue();
        var pair=postJson("/api/v2/practice-comparisons",Map.of("requestId","pair","leftAttemptId",baseline.path("id").asText(),"rightAttemptId",next.path("id").asText(),"mode","REEVALUATE_PAIR"));
        assertThat(pair.path("status").asText()).isEqualTo("READY");
        assertThat(pair.path("scopeNotice").asText()).contains("文本");
        assertThat(getJson("/api/v2/practice-attempts/"+baseline.path("id").asText()).path("status").asText()).isEqualTo("IMPORTED");
    }
    @Test void submittedAnswerReplayDoesNotDuplicateEvaluationOrAcceptChangedText()throws Exception {
        var c=caseFor(source(),0);var a=postJson("/api/v2/practice-cases/"+c.path("id").asText()+"/attempts",Map.of("requestId","draft","exposureDeclaration","UNKNOWN"));
        String path="/api/v2/practice-attempts/"+a.path("id").asText()+"/submit";var body=Map.of("requestId","submit","expectedVersion",0,"answer","原文必须保存。");
        var one=postJson(path,body);var two=postJson(path,body);
        assertThat(one.path("primaryGroupId").asText()).isEqualTo(two.path("primaryGroupId").asText());
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","submit","expectedVersion",0,"answer","变更正文"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_REQUEST_CONFLICT"));
    }
    @Test void attemptPaginationKeepsBaselineFirstAcrossMoreThanOnePage()throws Exception {
        var c=caseFor(source(),0);String cid=c.path("id").asText();
        for(int i=0;i<22;i++)postJson("/api/v2/practice-cases/"+cid+"/attempts",Map.of("requestId","attempt-"+i,"exposureDeclaration","UNKNOWN"));
        var page=getJson("/api/v2/practice-cases/"+cid+"/attempts?limit=20");
        assertThat(page.path("items").get(0).path("ordinal").asInt()).isZero();
        var next=getJson("/api/v2/practice-cases/"+cid+"/attempts?limit=20&cursor="+page.path("nextCursor").asText());
        assertThat(next.path("items").get(0).path("ordinal").asInt()).isEqualTo(20);
    }
    @Test void creationStatusDistinguishesNewCaseFromReplay()throws Exception {
        var source=source();var body=Map.of("sourceKind","SESSION","sourceId",source.path("id").asText(),"messageId",source.path("messages").get(0).path("id").asText(),"requestId",UUID.randomUUID().toString());
        mvc.perform(post("/api/v2/practice-cases").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isCreated());
        mvc.perform(post("/api/v2/practice-cases").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk());
    }
    @Test void protocolChangesDowngradeComparisonAndDifferentContextCannotBePairReevaluated()throws Exception {
        var s=source();var c=caseFor(s,0);String cid=c.path("id").asText();var left=attempt(cid,"左侧独立回答");var right=attempt(cid,"右侧独立回答");
        String gid=right.path("primaryGroupId").asText();var group=db.get("v2_practice_evaluation_groups",gid);var saved=group.path("protocol").deepCopy();
        for(String field:java.util.List.of("rubricVersion","promptHash","outputSchemaVersion","providerIdentity","requestedModel","returnedModel","parameters")){
            var changed=saved.deepCopy();if(field.equals("parameters"))((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("parameters")).put("reasoning_effort","different");else ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put(field,"different");
            var current=db.get("v2_practice_evaluation_groups",gid);current.set("protocol",changed);db.atomic(()->db.save("v2_practice_evaluation_groups",current,current.path("revision").asInt()));
            var result=postJson("/api/v2/practice-comparisons",Map.of("requestId",UUID.randomUUID().toString(),"mode","COMPARE","leftAttemptId",left.path("id").asText(),"rightAttemptId",right.path("id").asText()));assertThat(result.path("comparability").asText()).isEqualTo("TEXT_ONLY");assertThat(result.has("result")).isFalse();
        }
        var restored=db.get("v2_practice_evaluation_groups",gid);restored.set("protocol",saved);db.atomic(()->db.save("v2_practice_evaluation_groups",restored,restored.path("revision").asInt()));
        var other=attempt(caseFor(s,2).path("id").asText(),"不同追问题回答");
        var body=Map.of("requestId",UUID.randomUUID().toString(),"mode","COMPARE","leftAttemptId",left.path("id").asText(),"rightAttemptId",other.path("id").asText());
        assertThat(postJson("/api/v2/practice-comparisons",body).path("comparability").asText()).isEqualTo("DIFFERENT_CONTEXT");
        mvc.perform(post("/api/v2/practice-comparisons").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"mode","REEVALUATE_PAIR","leftAttemptId",left.path("id").asText(),"rightAttemptId",other.path("id").asText())))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_DIFFERENT_CONTEXT"));
    }
}
