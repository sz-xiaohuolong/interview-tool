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
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.llm.provider=mock","app.v2.enabled=true"})
@AutoConfigureMockMvc
class V2JdIntegrationTest {
    static final Path DATA=V2TestData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired Store legacy;@Autowired V2Store db;@Autowired JdService jd;@Autowired V2InputPreparations inputs;@Autowired TrainingLinkService links;
    JsonNode call(String path,Object body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    JsonNode prepare(Map<String,Object> inputs)throws Exception{var body=new java.util.HashMap<>(inputs);body.put("requestId",UUID.randomUUID().toString());return call("/api/v2/jd-diagnostics/input-preparations",body);}
    JsonNode confirmedLimitDiagnostic(int count,String replaceOf)throws Exception{
        var lines=new java.util.ArrayList<String>();for(int i=1;i<=count;i++)lines.add(String.format("合成要求 %02d：验证事务边界",i));
        var manifest=prepare(Map.of("jdText",String.join("\n",lines)));
        var body=new java.util.HashMap<String,Object>();body.put("requestId",UUID.randomUUID().toString());body.put("manifestId",manifest.path("manifestId").asText());body.put("manifestHash",manifest.path("manifestHash").asText());if(replaceOf!=null)body.put("replaceOf",replaceOf);
        var d=call("/api/v2/jd-diagnostics",body);var ids=new java.util.ArrayList<String>();for(JsonNode r:d.path("requirements"))ids.add(r.path("id").asText());
        return call("/api/v2/jd-diagnostics/"+d.path("id").asText()+"/confirm",Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",d.path("revision").asInt(),"requirementIds",ids));
    }
    com.fasterxml.jackson.databind.node.ArrayNode limitActions(JsonNode d,int count){
        var actions=db.array();for(int i=0;i<count;i++)actions.addObject().put("requirementId",d.path("requirements").get(i).path("id").asText()).put("type","VERIFY_REQUIREMENT").put("purpose","核对要求 "+(i+1)).put("completionCondition","完成专项并确认对应原文证据");return actions;
    }
    @Test void twelveRequirementsAreAcceptedAndThirteenthDoesNotOverwriteConfirmedDiagnostic()throws Exception{
        var d=confirmedLimitDiagnostic(12,null);String did=d.path("id").asText();var before=db.get("v2_jd_diagnostics",did).deepCopy();
        assertThat(d.path("requirements")).hasSize(12);
        for(JsonNode r:d.path("requirements"))assertThat(r.path("jdEvidence").path("quote")).isEqualTo(r.path("jdQuote"));
        var lines=new java.util.ArrayList<String>();for(int i=1;i<=13;i++)lines.add(String.format("合成要求 %02d：验证事务边界",i));
        var manifest=prepare(Map.of("jdText",String.join("\n",lines)));
        var response=mvc.perform(post("/api/v2/jd-diagnostics").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"manifestId",manifest.path("manifestId").asText(),"manifestHash",manifest.path("manifestHash").asText(),"replaceOf",did))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("V2_JD_SCOPE_TOO_LARGE")).andExpect(jsonPath("$.nextAction").value("EDIT_INPUT")).andReturn().getResponse();
        var failure=json.readTree(response.getContentAsString());var failed=db.get("v2_jd_diagnostics",failure.path("resourceId").asText());
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");assertThat(failed.has("requirements")).isFalse();assertThat(db.get("v2_jd_diagnostics",did)).isEqualTo(before);
    }
    @Test void sprintAcceptsFiveActionsAndRejectsSixWithoutCreatingRecord()throws Exception{
        var d=confirmedLimitDiagnostic(6,null);int before=legacy.jdbc.queryForObject("SELECT count(*) FROM v2_sprints",Integer.class);
        mvc.perform(post("/api/v2/sprints").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",d.path("id").asText(),"actions",limitActions(d,6)))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("V2_INVALID_INPUT"));
        assertThat(legacy.jdbc.queryForObject("SELECT count(*) FROM v2_sprints",Integer.class)).isEqualTo(before);
        var s=call("/api/v2/sprints",Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",d.path("id").asText(),"actions",limitActions(d,5)));
        assertThat(s.path("actions")).hasSize(5);assertThat(s.path("actions").findValuesAsText("status")).containsOnly("TODO");
    }
    @Test void diagnosticUpdateAndReopenCannotExceedFiveActiveActionsOrChangeRejectedHistory()throws Exception{
        var d1=confirmedLimitDiagnostic(6,null);var s=call("/api/v2/sprints",Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",d1.path("id").asText(),"actions",limitActions(d1,5)));String sid=s.path("id").asText(),path="/api/v2/sprints/"+sid+"/actions";
        var d2=confirmedLimitDiagnostic(6,d1.path("id").asText());var diff=jd.diff(sid,d2.path("id").asText());
        var extra=limitActions(d2,6);var singleExtra=db.array().add(extra.get(5));
        var before=db.get("v2_sprints",sid).deepCopy();var history=jd.ops.history("v2_sprints",sid,null,100).deepCopy();
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","APPLY_DIAGNOSTIC","diagnosticId",d2.path("id").asText(),"diffHash",diff.path("diffHash").asText(),"confirmDifferences",true,"newActions",singleExtra))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("V2_INVALID_INPUT")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("5")));
        assertThat(db.get("v2_sprints",sid)).isEqualTo(before);assertThat(jd.ops.history("v2_sprints",sid,null,100)).isEqualTo(history);
        String skippedId=s.path("actions").get(0).path("id").asText();s=call(path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","SKIP","actionId",skippedId,"reason","先处理其他要求，保留本项待重开"));
        diff=jd.diff(sid,d2.path("id").asText());
        s=call(path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","APPLY_DIAGNOSTIC","diagnosticId",d2.path("id").asText(),"diffHash",diff.path("diffHash").asText(),"confirmDifferences",true,"newActions",singleExtra));
        assertThat(s.path("actions")).hasSize(6);assertThat(s.path("actions").findValuesAsText("status")).containsExactly("SKIPPED","TODO","TODO","TODO","TODO","TODO");
        before=db.get("v2_sprints",sid).deepCopy();history=jd.ops.history("v2_sprints",sid,null,100).deepCopy();
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","REOPEN","actionId",skippedId))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("V2_INVALID_INPUT")).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("5")));
        assertThat(db.get("v2_sprints",sid)).isEqualTo(before);assertThat(jd.ops.history("v2_sprints",sid,null,100)).isEqualTo(history);
        String another=s.path("actions").get(1).path("id").asText();s=call(path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","SKIP","actionId",another,"reason","给原要求腾出本轮安排"));
        s=call(path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","REOPEN","actionId",skippedId));
        assertThat(jd.findAction(s,skippedId).path("status").asText()).isEqualTo("TODO");assertThat(jd.findAction(s,another).path("status").asText()).isEqualTo("SKIPPED");
    }
    JsonNode prioritySprint()throws Exception{
        var d=db.object().put("familyId",UUID.randomUUID().toString()).put("diagnosticVersion",1).put("status","CONFIRMED").put("mock",true);
        d.putObject("manifest").putArray("dependencies");var requirements=d.putArray("requirements");var actions=db.array();
        String[][] rows={{"supported","REQUIRED","SUPPORTED"},{"unverified-a","REQUIRED","UNVERIFIED"},{"preferred","PREFERRED","GAP"},{"gap","REQUIRED","GAP"},{"unverified-b","REQUIRED","UNVERIFIED"}};
        for(var row:rows){requirements.addObject().put("id",row[0]).put("importance",row[1]).put("trainingStatus",row[2]).put("confirmed",true).put("priorityReason","合成用户确认要求");actions.addObject().put("requirementId",row[0]).put("type","VERIFY_REQUIREMENT").put("purpose","验证："+row[0]).put("completionCondition","完成训练并核对该要求的证据");}
        db.atomic(()->db.insert("v2_jd_diagnostics",d));
        return call("/api/v2/sprints",Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",d.path("id").asText(),"actions",actions));
    }
    @Test void sprintPrioritizesRequiredGapsThenRequiredUnverifiedThenOtherRequirements()throws Exception{
        var sprint=prioritySprint();
        assertThat(sprint.path("actions").findValuesAsText("requirementId")).containsExactly("gap","unverified-a","unverified-b","supported","preferred");
        assertThat(sprint.path("actions").findValues("priority")).isEqualTo(java.util.List.of(json.valueToTree(1),json.valueToTree(2),json.valueToTree(2),json.valueToTree(3),json.valueToTree(3)));
    }
    @Test void sprintReordersOnlyWithinPriorityAndRejectedOrderKeepsRevisionAndHistory()throws Exception{
        var s=prioritySprint();String sid=s.path("id").asText(),path="/api/v2/sprints/"+sid+"/actions";
        var ids=new java.util.ArrayList<String>();var actionByRequirement=new java.util.HashMap<String,String>();
        for(JsonNode a:s.path("actions")){ids.add(a.path("id").asText());actionByRequirement.put(a.path("requirementId").asText(),a.path("id").asText());}
        java.util.Collections.swap(ids,ids.indexOf(actionByRequirement.get("unverified-a")),ids.indexOf(actionByRequirement.get("unverified-b")));
        var reordered=call(path,Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",s.path("revision").asInt(),"action","REORDER","actionIds",ids));
        assertThat(reordered.path("revision").asInt()).isEqualTo(s.path("revision").asInt()+1);
        var actualIds=new java.util.ArrayList<String>();for(JsonNode a:reordered.path("actions"))actualIds.add(a.path("id").asText());assertThat(actualIds).containsExactlyElementsOf(ids);
        for(JsonNode a:s.path("actions"))assertThat(jd.findAction(reordered,a.path("id").asText())).isEqualTo(a);
        var before=db.get("v2_sprints",sid).deepCopy();var history=jd.ops.history("v2_sprints",sid,null,100).deepCopy();String op=history.path("items").get(0).path("id").asText();var snapshot=jd.ops.historyDetail("v2_sprints",sid,op).deepCopy();
        java.util.Collections.swap(ids,ids.indexOf(actionByRequirement.get("gap")),ids.indexOf(actionByRequirement.get("unverified-a")));
        mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId",UUID.randomUUID().toString(),"expectedVersion",reordered.path("revision").asInt(),"action","REORDER","actionIds",ids)))).andExpect(status().isBadRequest());
        assertThat(db.get("v2_sprints",sid)).isEqualTo(before);assertThat(jd.ops.history("v2_sprints",sid,null,100)).isEqualTo(history);assertThat(jd.ops.historyDetail("v2_sprints",sid,op)).isEqualTo(snapshot);
    }
    @Test void previewDoesNotCallModelAndChangedSourceIsRejectedBeforeGeneration()throws Exception{
        var material=call("/api/materials",Map.of("kind","PROJECT","name","合成项目","content","个人负责订单接口"));
        var manifest=prepare(Map.of("jdText","熟悉 Java 事务", "materialIds",new String[]{material.path("id").asText()}));
        assertThat(manifest.path("snapshots").toString()).contains("个人负责订单接口");
        legacy.jdbc.update("UPDATE materials SET content='资料已变化' WHERE id=?",material.path("id").asText());
        mvc.perform(post("/api/v2/jd-diagnostics").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","changed","manifestId",manifest.path("manifestId").asText(),"manifestHash",manifest.path("manifestHash").asText()))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("V2_SOURCE_CHANGED"));
    }
    @Test void noMaterialsIsUnknownAndConfirmationRequiredBeforeSprint()throws Exception{
        var manifest=prepare(Map.of("jdText","熟悉 Java 事务\n了解接口幂等"));var body=Map.of("requestId",UUID.randomUUID().toString(),"manifestId",manifest.path("manifestId").asText(),"manifestHash",manifest.path("manifestHash").asText());
        var d=call("/api/v2/jd-diagnostics",body);String did=d.path("id").asText();
        assertThat(d.path("requirements").get(0).path("materialCoverage").asText()).isEqualTo("NOT_MENTIONED");assertThat(d.path("requirements").get(0).path("trainingStatus").asText()).isEqualTo("UNVERIFIED");
        mvc.perform(post("/api/v2/sprints").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","unconfirmed","confirmedDiagnosticId",did,"actions",new Object[]{})))) .andExpect(status().isConflict());
        var confirmed=call("/api/v2/jd-diagnostics/"+did+"/confirm",Map.of("requestId","confirm","expectedVersion",d.path("revision").asInt(),"requirementIds",new String[]{d.path("requirements").get(0).path("id").asText()}));
        var action=Map.of("requirementId",confirmed.path("requirements").get(0).path("id").asText(),"type","VERIFY_REQUIREMENT","purpose","核对事务边界","completionCondition","完成专项并确认对应题目依据");
        var sprint=call("/api/v2/sprints",Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",did,"actions",new Object[]{action}));
        assertThat(sprint.path("actions").get(0).path("status").asText()).isEqualTo("TODO");
        mvc.perform(post("/api/v2/sprints/"+sprint.path("id").asText()+"/actions").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId","complete","expectedVersion",0,"action","COMPLETE","actionId",sprint.path("actions").get(0).path("id").asText(),"coverageConfirmed",true)))) .andExpect(status().isBadRequest());
        assertThat(call("/api/v2/jd-diagnostics",body).path("id").asText()).isEqualTo(did);
    }
    @Test void materialCompletionFreezesTextAndActionKeepsOriginalDiagnostic()throws Exception{
        var m=call("/api/materials",Map.of("kind","PROJECT","name","合成来源","content","原始说明"));
        var manifest=prepare(Map.of("jdText","熟悉事务","materialIds",new String[]{m.path("id").asText()}));
        var d=call("/api/v2/jd-diagnostics",Map.of("requestId",UUID.randomUUID().toString(),"manifestId",manifest.path("manifestId").asText(),"manifestHash",manifest.path("manifestHash").asText()));String did=d.path("id").asText(),rid=d.path("requirements").get(0).path("id").asText();
        d=call("/api/v2/jd-diagnostics/"+did+"/confirm",Map.of("requestId","confirm","expectedVersion",d.path("revision").asInt(),"requirementIds",new String[]{rid}));
        var sprint=call("/api/v2/sprints",Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",did,"actions",new Object[]{Map.of("requirementId",rid,"type","MATERIAL_UPDATE","purpose","补充事务经历","completionCondition","核对修订原文")}));
        String aid=sprint.path("actions").get(0).path("id").asText();assertThat(sprint.path("actions").get(0).path("sourceDiagnosticId").asText()).isEqualTo(did);
        legacy.jdbc.update("UPDATE materials SET content='修订后：本人负责事务回滚边界' WHERE id=?",m.path("id").asText());var snapshot=jd.completionMaterial(m.path("id").asText());
        var completed=call("/api/v2/sprints/"+sprint.path("id").asText()+"/actions",Map.of("requestId","done","expectedVersion",0,"action","COMPLETE","actionId",aid,"coverageConfirmed",true,"materialId",m.path("id").asText(),"materialHash",snapshot.path("hash").asText()));
        legacy.jdbc.update("DELETE FROM materials WHERE id=?",m.path("id").asText());var stored=jd.sprintView(sprint.path("id").asText());
        assertThat(stored.path("actions").get(0).path("completionEvidence").toString()).contains("本人负责事务回滚边界");assertThat(completed.path("actions").get(0).path("status").asText()).isEqualTo("DONE");
    }
    @Test void disputedFindingWithdrawsOnlyCurrentSupportAndKeepsHistoricalVerdict(){
        var session=db.object().put("id",UUID.randomUUID().toString());legacy.saveSession(session);var review=db.object().put("sourceSessionId",session.path("id").asText()).put("questionIndex",0).put("status","DUE").put("reportHash","original").put("mock",true);
        var fs=review.putArray("findings");var first=fs.addObject().put("id","f1").put("kind","missing").put("text","事务边界").put("confirmed",true).put("disputed",false);first.putObject("currentEvidence").put("sourceReportHash","original").put("status","SUPPORTED");fs.addObject().put("id","f2").put("kind","missing").put("text","失败恢复").put("confirmed",true).put("disputed",false);db.atomic(()->db.insert("v2_review_items",review));
        var event=db.object().put("reviewItemId",review.path("id").asText()).put("sourceKey",UUID.randomUUID().toString()).put("eventType","CONFIRM").put("sourceReportHash","original");event.putObject("details");db.atomic(()->db.insert("v2_skill_events",event));
        var observation=inputs.trainingSource(event.path("id").asText()).path("observation");assertThat(observation.path("status").asText()).isEqualTo("GAP");assertThat(observation.path("findings")).hasSize(2);
        var d=db.object().put("familyId",UUID.randomUUID().toString()).put("diagnosticVersion",1).put("status","CONFIRMED");d.putObject("manifest").putArray("dependencies");var r=d.putArray("requirements").addObject().put("trainingStatus","SUPPORTED");r.putArray("trainingBindings").addObject().put("eventId",event.path("id").asText()).put("findingId","f1");db.atomic(()->db.insert("v2_jd_diagnostics",d));assertThat(jd.view(d).path("requirements").get(0).path("currentTrainingStatus").asText()).isEqualTo("SUPPORTED");
        first.put("disputed",true);db.atomic(()->db.save("v2_review_items",review,0));var view=jd.view(d);assertThat(view.path("requirements").get(0).path("trainingStatus").asText()).isEqualTo("SUPPORTED");assertThat(view.path("requirements").get(0).path("currentTrainingStatus").asText()).isEqualTo("DISPUTED");
    }
    @Test void reopeningCompletedActionUsesCurrentDiagnosticAndPreservesCompletionHistory()throws Exception{
        var m=call("/api/materials",Map.of("kind","PROJECT","name","合成重开来源","content","旧事务说明"));String mid=m.path("id").asText();
        var manifest=prepare(Map.of("jdText","熟悉 Java 事务","materialIds",new String[]{mid}));
        var d1=call("/api/v2/jd-diagnostics",Map.of("requestId",UUID.randomUUID().toString(),"manifestId",manifest.path("manifestId").asText(),"manifestHash",manifest.path("manifestHash").asText()));String did1=d1.path("id").asText(),rid=d1.path("requirements").get(0).path("id").asText();
        d1=call("/api/v2/jd-diagnostics/"+did1+"/confirm",Map.of("requestId","confirm","expectedVersion",d1.path("revision").asInt(),"requirementIds",new String[]{rid}));
        var s=call("/api/v2/sprints",Map.of("requestId",UUID.randomUUID().toString(),"confirmedDiagnosticId",did1,"actions",new Object[]{Map.of("requirementId",rid,"type","MATERIAL_UPDATE","purpose","补充事务边界","completionCondition","确认新资料")}));String sid=s.path("id").asText(),aid=s.path("actions").get(0).path("id").asText();
        legacy.jdbc.update("UPDATE materials SET content='新的事务边界说明' WHERE id=?",mid);
        s=call("/api/v2/sprints/"+sid+"/actions",Map.of("requestId","done","expectedVersion",s.path("revision").asInt(),"action","COMPLETE","actionId",aid,"coverageConfirmed",true,"materialId",mid,"materialHash",jd.completionMaterial(mid).path("hash").asText()));
        var evidence=s.path("actions").get(0).path("completionEvidence").deepCopy();
        manifest=prepare(Map.of("jdText","熟悉 Java 事务","materialIds",new String[]{mid}));
        var d2=call("/api/v2/jd-diagnostics",Map.of("requestId",UUID.randomUUID().toString(),"manifestId",manifest.path("manifestId").asText(),"manifestHash",manifest.path("manifestHash").asText(),"replaceOf",did1));String did2=d2.path("id").asText();
        d2=call("/api/v2/jd-diagnostics/"+did2+"/confirm",Map.of("requestId","confirm","expectedVersion",d2.path("revision").asInt(),"requirementIds",new String[]{rid}));
        var diff=jd.diff(sid,did2);s=call("/api/v2/sprints/"+sid+"/actions",Map.of("requestId","apply","expectedVersion",s.path("revision").asInt(),"action","APPLY_DIAGNOSTIC","diagnosticId",did2,"diffHash",diff.path("diffHash").asText(),"confirmDifferences",true));
        assertThat(s.path("actions").get(0).path("sourceDiagnosticId").asText()).isEqualTo(did1);
        s=call("/api/v2/sprints/"+sid+"/actions",Map.of("requestId","reopen","expectedVersion",s.path("revision").asInt(),"action","REOPEN","actionId",aid));
        var a=s.path("actions").get(0);assertThat(a.path("sourceDiagnosticId").asText()).isEqualTo(did2);assertThat(a.path("originalSourceDiagnosticId").asText()).isEqualTo(did1);assertThat(a.path("completionEvidence")).isEqualTo(evidence);
        var link=db.object().put("requestId",Models.id()).put("sourceKind","JD").put("sourceId",did2).put("sprintId",sid).put("actionId",aid);link.putArray("requirementIds").add(rid);assertThat(links.link(link).path("sourceId").asText()).isEqualTo(did2);
    }

}
