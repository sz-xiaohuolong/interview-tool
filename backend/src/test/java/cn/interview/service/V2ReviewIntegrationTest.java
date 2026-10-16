package cn.interview.service;

import cn.interview.mapper.Store;
import cn.interview.mapper.StoreTestAccess;
import cn.interview.testsupport.V2TestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.llm.provider=mock", "app.v2.enabled=true"})
@AutoConfigureMockMvc
class V2ReviewIntegrationTest {
    static final Path DATA = V2TestData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store legacy;

    JsonNode post(String path, Object body) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
    JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    String session() throws Exception {
        JsonNode s=post("/api/sessions", Map.of("durationMinutes",5));
        String id=s.path("id").asText();
        post("/api/sessions/"+id+"/answers",Map.of("content","我用事务保存订单，并在失败时回滚。", "requestId",UUID.randomUUID().toString(),"expectedMessageId",s.path("messages").get(0).path("id").asText()));
        post("/api/sessions/"+id+"/finish",Map.of());
        return id;
    }
    JsonNode importReport(String id, String key) throws Exception {
        var preview=getJson("/api/v2/review-import-preview?sessionId="+id);
        return post("/api/v2/review-imports", Map.of("sessionId",id,"expectedReportHash",preview.path("reportHash").asText(),"requestId",key));
    }

    @Test void importIsIdempotentAndNeverRewritesOriginalEvidence() throws Exception {
        String id=session(); String before=legacy.report(id).toString();
        var first=importReport(id,"import-one");
        String reviewId=first.path("items").get(0).path("id").asText();
        assertThat(first.path("items").get(0).path("sourcePrecision").asText()).isEqualTo("QUESTION_ONLY");
        assertThat(importReport(id,"import-one").path("items").get(0).path("id").asText()).isEqualTo(reviewId);
        assertThat(importReport(id,"import-two").path("items").get(0).path("id").asText()).isEqualTo(reviewId);
        assertThat(legacy.report(id).toString()).isEqualTo(before);
        var item=getJson("/api/v2/review-items/"+reviewId);
        assertThat(item.path("findings")).hasSize(2);
        assertThat(item.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test void sameKeyReplaysBeforeVersionButChangedInputConflicts() throws Exception {
        var item=importReport(session(),"import-"+UUID.randomUUID()).path("items").get(0);
        String path="/api/v2/review-items/"+item.path("id").asText()+"/actions";
        var body=Map.of("requestId","confirm","expectedVersion",0,"action","CONFIRM","skillIds",new String[]{"JAVA.TRANSACTIONS"});
        var confirmed=post(path,body);
        assertThat(confirmed.path("status").asText()).isEqualTo("DUE");
        assertThat(post(path,body).path("revision").asInt()).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("requestId","different","expectedVersion",0,"action","START"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentRevision").value(1));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("requestId","confirm","expectedVersion",0,"action","ARCHIVE"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_REQUEST_CONFLICT"));
    }

    @Test void unknownOnlyDoesNotBecomeWrongAndDisputeKeepsOriginal() throws Exception {
        String id=session(); ObjectNode report=legacy.report(id);
        ((ObjectNode)report.path("questions").get(0)).putArray("missing"); legacy.saveReport(id,report);
        var item=importReport(id,"unknown").path("items").get(0);
        String path="/api/v2/review-items/"+item.path("id").asText()+"/actions";
        var confirmed=post(path,Map.of("requestId","confirm","expectedVersion",0,"action","CONFIRM","skillIds",new String[]{}));
        assertThat(confirmed.path("status").asText()).isEqualTo("WAITING_EVIDENCE");
        assertThat(confirmed.path("nextReviewAt").isNull()).isTrue();
        var disputed=post(path,Map.of("requestId","dispute","expectedVersion",1,"action","DISPUTE","findingIds",new String[]{confirmed.path("findings").get(0).path("id").asText()},"reason","该项仍无法判断"));
        assertThat(disputed.path("findings").get(0).path("text").asText()).contains("Mock");
        assertThat(disputed.path("findings").get(0).path("disputed").asBoolean()).isTrue();
    }

    @Test void linkedPracticeUsesOriginalPlannerAndReplaysOnlyOneSession() throws Exception {
        var item=importReport(session(),"source").path("items").get(0);
        String id=item.path("id").asText();
        post("/api/v2/review-items/"+id+"/actions",Map.of("requestId","confirm","expectedVersion",0,"action","CONFIRM","skillIds",new String[]{}));
        var link=post("/api/v2/training-links",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","REVIEW","sourceId",id));
        var body=Map.of("requestId","linked-create","trainingLinkId",link.path("id").asText(),"role","Java 后端","level","JUNIOR","durationMinutes",5,"focus","事务回滚");
        var one=post("/api/sessions",body); var two=post("/api/sessions",body);
        assertThat(two.path("id").asText()).isEqualTo(one.path("id").asText());
        assertThat(one.path("messages").size()).isEqualTo(1);
        assertThat(legacy.session(one.path("id").asText()).path("focus").asText()).isEqualTo("事务回滚");
    }

    @Test void selectiveConfirmationDoesNotAcceptOtherFindings() throws Exception {
        var item=importReport(session(),"selective").path("items").get(0);
        String unknown=item.path("findings").get(1).path("id").asText();
        var confirmed=post("/api/v2/review-items/"+item.path("id").asText()+"/actions",Map.of("requestId","selected","expectedVersion",0,"action","CONFIRM","findingIds",new String[]{unknown},"skillIds",new String[]{}));
        assertThat(confirmed.path("findings").get(0).path("confirmed").asBoolean()).isFalse();
        assertThat(confirmed.path("status").asText()).isEqualTo("WAITING_EVIDENCE");
    }

    @Test void queueOrdersByDueTimeRatherThanNewRecordTime() throws Exception {
        var early=importReport(session(),"early").path("items").get(0);
        var late=importReport(session(),"late").path("items").get(0);
        StoreTestAccess.jdbc(legacy).update("UPDATE v2_review_items SET status='DUE',next_review_at='1999-01-01T00:00:00Z' WHERE id=?",early.path("id").asText());
        StoreTestAccess.jdbc(legacy).update("UPDATE v2_review_items SET status='DUE',next_review_at='2000-01-01T00:00:00Z' WHERE id=?",late.path("id").asText());
        var page=getJson("/api/v2/review-items?status=DUE&limit=1");
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(early.path("id").asText());
        var next=getJson("/api/v2/review-items?status=DUE&limit=1&cursor="+page.path("nextCursor").asText());
        assertThat(next.path("items").get(0).path("id").asText()).isEqualTo(late.path("id").asText());
    }

    @Test void skillFilteringHappensBeforePagination() throws Exception {
        var own=post("/api/v2/skills/AGENT.RETRIEVAL/self-ratings",Map.of("requestId","own","value","NEEDS_PRACTICE"));
        for(int i=0;i<22;i++)post("/api/v2/skills/JAVA.CONCURRENCY/self-ratings",Map.of("requestId","other-"+i,"value","UNRATED"));
        var page=getJson("/api/v2/skills/AGENT.RETRIEVAL/events?limit=20");
        assertThat(page.path("items").findValuesAsText("id")).contains(own.path("id").asText());
    }

    @Test void selectedTrainingScopeAndDisputeAreEnforced() throws Exception {
        var item=importReport(session(),"training-scope").path("items").get(0);String id=item.path("id").asText();
        post("/api/v2/review-items/"+id+"/actions",Map.of("requestId","confirm","expectedVersion",0,"action","CONFIRM","skillIds",new String[]{}));
        String finding=item.path("findings").get(1).path("id").asText();
        var link=post("/api/v2/training-links",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","REVIEW","sourceId",id,"findingIds",new String[]{finding}));
        assertThat(link.path("findingIds")).hasSize(1);
        post("/api/v2/review-items/"+id+"/actions",Map.of("requestId","dispute","expectedVersion",1,"action","DISPUTE","findingIds",new String[]{finding},"reason","需要复核"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/sessions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","create","trainingLinkId",link.path("id").asText()))))
            .andExpect(status().isConflict());
    }

    @Test void operationStatusDoesNotExposeEvidence() throws Exception {
        String id=session();importReport(id,"private-status");
        String op=StoreTestAccess.jdbc(legacy).queryForObject("SELECT id FROM v2_operations WHERE kind='REVIEW_IMPORT' AND request_id='private-status'",String.class);
        var status=getJson("/api/v2/operations/"+op);
        assertThat(status.has("result")).isFalse();
        assertThat(status.toString()).doesNotContain("事务保存订单");
    }

    @Test void unrelatedOrUnboundEvidenceCannotResolveUnknownFindings() throws Exception {
        String sid=session();var report=legacy.report(sid);((ObjectNode)report.path("questions").get(0)).putArray("missing");legacy.saveReport(sid,report);
        var item=importReport(sid,"evidence-source").path("items").get(0);String id=item.path("id").asText();
        post("/api/v2/review-items/"+id+"/actions",Map.of("requestId","confirm","expectedVersion",0,"action","CONFIRM","skillIds",new String[]{}));
        var link=post("/api/v2/training-links",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","REVIEW","sourceId",id));
        var training=post("/api/sessions",Map.of("requestId","create","trainingLinkId",link.path("id").asText()));String tid=training.path("id").asText();
        post("/api/sessions/"+tid+"/answers",Map.of("content","新的合成回答","requestId","answer","expectedMessageId",training.path("messages").get(0).path("id").asText()));
        post("/api/sessions/"+tid+"/finish",Map.of());
        var evidence=legacy.report(tid);var q=(ObjectNode)evidence.path("questions").get(0);q.putArray("missing");q.putArray("incorrect");q.putArray("uncertain");q.putArray("correct").add("合成正确结论，需明确绑定");legacy.saveReport(tid,evidence);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/review-items/"+id+"/actions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","resolve","expectedVersion",1,"action","RESOLVE_EVIDENCE","trainingLinkId",link.path("id").asText(),"evidenceQuestionIndex",0,"findingIds",new String[]{"unrelated"}))))
            .andExpect(status().isBadRequest());
        assertThat(getJson("/api/v2/review-items/"+id).path("status").asText()).isEqualTo("WAITING_EVIDENCE");
    }

    JsonNode reviewAction(JsonNode item, String action, Map<String,Object> extra) throws Exception {
        var body=new java.util.HashMap<String,Object>(extra);
        body.put("action",action);body.put("expectedVersion",item.path("revision").asInt());body.put("requestId",UUID.randomUUID().toString());
        return post("/api/v2/review-items/"+item.path("id").asText()+"/actions",body);
    }

    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void changedReportKeepsOldVersionAndRequiresConfirmationAfterArchiveRestore(boolean archived, boolean noFindings) throws Exception {
        String sid=session();var report=legacy.report(sid);var question=(ObjectNode)report.path("questions").get(0);
        question.putArray("incorrect").add("合成错误：把本地事务当作外部调用恰好一次保证");
        question.putArray("missing").add("合成遗漏：没有说明唯一约束");
        question.putArray("uncertain").add("合成未知：没有容量证据");legacy.saveReport(sid,report);
        var item=importReport(sid,"mixed-"+UUID.randomUUID()).path("items").get(0);String id=item.path("id").asText();
        assertThat(item.path("findings").findValuesAsText("kind")).containsExactly("incorrect","missing","uncertain");
        var old=reviewAction(item,"CONFIRM",Map.of("skillIds",new String[]{"事务","transaction"}));
        if(archived)item=reviewAction(old,"ARCHIVE",Map.of());
        question.putArray("missing").add("合成新版遗漏：需要说明超时后的状态核对");
        if(noFindings)for(String kind:new String[]{"incorrect","missing","uncertain"})question.putArray(kind);
        legacy.saveReport(sid,report);
        var imported=importReport(sid,"changed-"+UUID.randomUUID());
        assertThat(imported.path("items")).hasSize(1);
        var updated=imported.path("items").get(0);
        assertThat(updated.path("id").asText()).isEqualTo(id);assertThat(updated.path("sourceVersions")).hasSize(1);
        assertThat(updated.path("sourceVersions").get(0).path("reportHash")).isEqualTo(old.path("reportHash"));
        assertThat(updated.path("sourceVersions").get(0).path("findings")).isEqualTo(old.path("findings"));
        if(noFindings)assertThat(updated.path("findings")).isEmpty();else assertThat(updated.path("findings").findValuesAsText("text")).contains("合成新版遗漏：需要说明超时后的状态核对");
        for(JsonNode finding:updated.path("findings"))assertThat(finding.path("confirmed").asBoolean()).isFalse();
        if(archived) {assertThat(updated.path("status").asText()).isEqualTo("ARCHIVED");updated=reviewAction(updated,"RESTORE",Map.of());}
        assertThat(updated.path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(updated.path("nextReviewAt").isNull()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/review-items/"+id+"/actions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",updated.path("revision").asInt(),"action","START"))))
            .andExpect(status().isConflict());
        if(noFindings) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/review-items/"+id+"/actions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",updated.path("revision").asInt(),"action","CONFIRM","skillIds",new String[]{}))))
                .andExpect(status().isConflict());
        } else {
            var confirmed=reviewAction(updated,"CONFIRM",Map.of("skillIds",new String[]{}));
            var restored=reviewAction(reviewAction(confirmed,"ARCHIVE",Map.of()),"RESTORE",Map.of());
            assertThat(restored.path("status")).isEqualTo(confirmed.path("status"));assertThat(restored.path("nextReviewAt")).isEqualTo(confirmed.path("nextReviewAt"));
        }
        assertThat(legacy.report(sid)).isEqualTo(report);
    }

    @Test void aliasesAndReclassificationRetainIndependentSourcesAndHistoricalTags() throws Exception {
        String sid=session(),otherSid=session();String report=legacy.report(sid).toString();
        var item=importReport(sid,"tags-"+UUID.randomUUID()).path("items").get(0);
        var other=importReport(otherSid,"tags-other-"+UUID.randomUUID()).path("items").get(0);
        assertThat(other.path("id")).isNotEqualTo(item.path("id"));
        item=reviewAction(item,"CONFIRM",Map.of("skillIds",new String[]{"事务","transaction","JAVA.TRANSACTIONS"}));
        assertThat(item.path("skillIds").get(0).asText()).isEqualTo("JAVA.TRANSACTIONS");assertThat(item.path("skillIds")).hasSize(1);
        String id=item.path("id").asText();var findings=item.path("findings").deepCopy();
        item=reviewAction(item,"CLASSIFY",Map.of("skillIds",new String[]{"RAG"}));
        assertThat(item.path("skillIds").get(0).asText()).isEqualTo("AGENT.RETRIEVAL");
        item=reviewAction(item,"CLASSIFY",Map.of("skillIds",new String[]{}));assertThat(item.path("skillIds")).isEmpty();assertThat(item.path("findings")).isEqualTo(findings);
        var history=getJson("/api/v2/review-items/"+id+"/history?limit=100");
        var tags=new java.util.HashSet<String>();
        for(JsonNode h:history.path("items"))tags.add(getJson("/api/v2/review-items/"+id+"/history/"+h.path("id").asText()).path("skillIds").toString());
        assertThat(tags).contains("[\"JAVA.TRANSACTIONS\"]","[\"AGENT.RETRIEVAL\"]","[]");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/review-items/"+id+"/actions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",item.path("revision").asInt(),"action","CLASSIFY","skillIds",new String[]{"模型随意创建的标签"}))))
            .andExpect(status().isBadRequest());
        assertThat(getJson("/api/v2/review-items/"+id)).isEqualTo(item);
        assertThat(getJson("/api/v2/review-items/"+id+"/history?limit=100")).isEqualTo(history);
        assertThat(getJson("/api/v2/skills/transaction/events?limit=100")).isEqualTo(getJson("/api/v2/skills/JAVA.TRANSACTIONS/events?limit=100"));
        assertThat(legacy.report(sid).toString()).isEqualTo(report);
    }

    @Test void confirmedFeedbackCanScheduleEndAndReopenRoundWithoutClaimingMastery() throws Exception {
        var item=importReport(session(),"round-"+UUID.randomUUID()).path("items").get(0);
        String finding=item.path("findings").get(0).path("id").asText();
        item=reviewAction(item,"CONFIRM",Map.of("findingIds",new String[]{finding},"skillIds",new String[]{"JAVA.TRANSACTIONS"}));
        var link=post("/api/v2/training-links",Map.of("requestId",UUID.randomUUID().toString(),"sourceKind","REVIEW","sourceId",item.path("id").asText(),"findingIds",new String[]{finding}));
        var training=post("/api/sessions",Map.of("requestId",UUID.randomUUID().toString(),"trainingLinkId",link.path("id").asText(),"focus","事务边界","role","Java 后端","level","JUNIOR","durationMinutes",5));
        String sid=training.path("id").asText();
        assertThat(training.path("role").asText()).isEqualTo("Java 后端");assertThat(training.path("level").asText()).isEqualTo("JUNIOR");
        post("/api/sessions/"+sid+"/answers",Map.of("content","合成专项回答：用事务管理提交和回滚。","requestId",UUID.randomUUID().toString(),"expectedMessageId",training.path("messages").get(0).path("id").asText()));
        post("/api/sessions/"+sid+"/finish",Map.of());
        var syntheticReport=legacy.report(sid);
        var question=(ObjectNode)syntheticReport.path("questions").get(0);
        question.putArray("correct").add("合成反馈夹具：已解释事务原子提交和失败回滚。");
        question.putArray("missing").add("合成反馈夹具：尚缺重复请求的边界处理。");
        legacy.saveReport(sid,syntheticReport);
        var supported=Map.<String,Object>of("findingIds",new String[]{finding},"trainingLinkId",link.path("id").asText(),"evidenceQuestionIndex",0,"bindings",new Object[]{Map.of("findingId",finding,"reportKind","correct","reportText",question.path("correct").get(0).asText(),"coverageConfirmed",true,"reason","仅确认该报告结论覆盖这一发现，不推断整体能力。")});
        for(int days:new int[]{1,3}) {
            item=reviewAction(item,"START",Map.of());
            var body=new java.util.HashMap<String,Object>(supported);body.put("intervalDays",days);
            var earliest=java.time.Instant.now().plus(days,java.time.temporal.ChronoUnit.DAYS).minusSeconds(1);
            item=reviewAction(item,"RESCHEDULE",body);
            assertThat(item.path("status").asText()).isEqualTo("DUE");
            assertThat(java.time.Instant.parse(item.path("nextReviewAt").asText())).isBetween(earliest,java.time.Instant.now().plus(days,java.time.temporal.ChronoUnit.DAYS).plusSeconds(1));
            assertThat(item.path("findings").get(0).path("currentEvidence").path("status").asText()).isEqualTo("SUPPORTED");
        }
        item=reviewAction(item,"START",Map.of());item=reviewAction(item,"RESOLVE",supported);
        assertThat(item.path("status").asText()).isEqualTo("RESOLVED");assertThat(item.path("resolutionReason").asText()).isEqualTo("USER_ENDED_ROUND");assertThat(item.path("nextReviewAt").isNull()).isTrue();
        var gaps=new java.util.HashMap<String,Object>(supported);gaps.put("bindings",new Object[]{Map.of("findingId",finding,"reportKind","missing","reportText",question.path("missing").get(0).asText(),"coverageConfirmed",true,"reason","本次复查仍暴露该缺口。")});
        item=reviewAction(item,"CONFIRM_EVIDENCE",gaps);assertThat(item.path("status").asText()).isEqualTo("DUE");
        item=reviewAction(item,"ARCHIVE",Map.of());item=reviewAction(item,"CONFIRM_EVIDENCE",gaps);
        assertThat(item.path("status").asText()).isEqualTo("ARCHIVED");assertThat(item.path("newEvidenceAvailable").asBoolean()).isTrue();
        var history=getJson("/api/v2/review-items/"+item.path("id").asText()+"/history?limit=100");
        assertThat(history.path("items").size()).isGreaterThanOrEqualTo(10);
        String historyId=history.path("items").get(0).path("id").asText();
        assertThat(getJson("/api/v2/review-items/"+item.path("id").asText()+"/history/"+historyId).path("id").asText()).isEqualTo(item.path("id").asText());
        assertThat(getJson("/api/v2/skills").toString()).contains("完成本轮不表示能力提升");
    }

    @Test void v2WriteRoutesRetainLoopbackAndOriginGuards() throws Exception {
        for(String path:new String[]{"/review-imports","/skills/JAVA.TRANSACTIONS/self-ratings","/practice-cases","/practice-attempts/example/submit","/jd-diagnostics","/stories","/design-sessions","/training-links","/operations/example/retry"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2"+path).header("Origin","https://evil.example").header("Sec-Fetch-Site","cross-site").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"));
        }
        for(String path:new String[]{"/practice-attempts/example/draft","/design-sessions/example/draft"}) {
            mvc.perform(put("/api/v2"+path).header("Origin","http://127.0.0.1:9999").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("http://evil.example/api/v2/stories").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v2/stories").header("Origin","http://127.0.0.1:5173").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"templateKind","INTRO"))))
            .andExpect(status().isCreated());
    }

}
