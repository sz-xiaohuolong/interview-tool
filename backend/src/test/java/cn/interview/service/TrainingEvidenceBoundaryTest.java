package cn.interview.service;

import cn.interview.client.FocusedInterviewModelClient;
import cn.interview.client.TrainingModelClient;
import cn.interview.client.TrainingMockTasksTestAccess;
import cn.interview.client.ModelTask;
import cn.interview.common.Models;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;
import cn.interview.validation.TrainingOutputValidator;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.llm.provider=mock","app.training.enabled=true"})
class TrainingEvidenceBoundaryTest {
    @Autowired FocusedInterviewModelClient baseLlm;

    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired TrainingStore db;
    @Autowired TrainingOutputValidator validator;
    @Autowired InputPreparationService inputs;
    @Autowired StoryService stories;
    @Autowired DesignPracticeService design;
    @Autowired ModelTaskRunner runner;
    @Test void modelDraftIsNotConvertedIntoUserFactOnRegeneration(){
        var raw=db.object();raw.putObject("result").put("text","团队达成未确认成果").put("originType","MODEL_DRAFT");
        var b=db.object().put("requestId",Models.id()).put("templateKind","STAR");b.set("fields",raw);
        var s=stories.create(b);var manifest=stories.prepare(s.path("id").asText(),db.object().put("requestId",Models.id()).put("expectedVersion",0));
        for(var snapshot:manifest.path("snapshots"))assertThat(snapshot.path("text").asText()).doesNotContain("团队达成未确认成果");
        var confirmations=db.object();for(String key:StoryService.FIELDS.get("STAR"))confirmations.putObject(key).put("notApplicable",true).put("reason","暂缺");
        var confirm=db.object().put("requestId",Models.id()).put("expectedVersion",0).put("storyVersion",1);confirm.set("confirmations",confirmations);
        assertThatThrownBy(()->stories.confirm(s.path("id").asText(),confirm)).isInstanceOf(RecoveryException.class);
    }
    @Test void malformedStoryFieldReturnsValidationError(){
        var in=db.object();in.putArray("templateFields").add("action");in.putArray("snapshots");var raw=db.object().put("expressionDraft","");raw.putArray("pendingFacts");raw.putObject("fields").put("action","非法字符串字段");
        assertThatThrownBy(()->validator.validate(ModelTask.STORY_DRAFTER,in,raw)).isInstanceOf(RecoveryException.class);
    }
    ObjectNode jdInput(){var in=db.object().put("jdText","熟悉事务");in.putArray("trainingEvents");in.putArray("snapshots").addObject().put("id","jd").put("text","熟悉事务").put("purpose","JD");in.withArray("snapshots").addObject().put("id","m").put("text","项目说明").put("purpose","MATERIAL").put("truncated",false);return in;}
    @Test void thirteenRequirementsAreRejectedEvenWhenModelClaimsNoMore(){
        var lines=new ArrayList<String>();for(int i=1;i<=13;i++)lines.add(String.format("合成要求 %02d：验证事务边界",i));
        String text=String.join("\n",lines);var in=db.object().put("jdText",text);in.putArray("trainingEvents");in.putArray("snapshots").addObject().put("id","jd").put("purpose","JD").put("text",text);
        var raw=TrainingMockTasksTestAccess.generate(ModelTask.JD_DIAGNOSTIC,in,db);raw.put("hasMore",false);var last=((ObjectNode)raw.path("requirements").get(11)).deepCopy().put("jdQuote",lines.get(12));raw.withArray("requirements").add(last);
        assertThatThrownBy(()->validator.validate(ModelTask.JD_DIAGNOSTIC,in,raw)).isInstanceOfSatisfying(RecoveryException.class,e->{assertThat(e.code).isEqualTo("V2_JD_SCOPE_TOO_LARGE");assertThat(e.status).isEqualTo(422);});
    }
    @Test void absentMaterialRequiresWholeSnapshotCheck(){
        var in=jdInput();var raw=TrainingMockTasksTestAccess.generate(ModelTask.JD_DIAGNOSTIC,in,db);((ObjectNode)raw.path("requirements").get(0)).putArray("evidenceRefs");
        assertThatThrownBy(()->validator.validate(ModelTask.JD_DIAGNOSTIC,in,raw)).isInstanceOf(RecoveryException.class);
    }
    @Test void requirementCannotClaimSupportFromDifferentFindingInSameEvent(){
        var in=jdInput();var event=in.withArray("trainingEvents").addObject().put("id","event").put("status","GAP");var fs=event.putArray("findings");fs.addObject().put("id","supported").put("status","SUPPORTED");fs.addObject().put("id","gap").put("status","GAP");
        var raw=TrainingMockTasksTestAccess.generate(ModelTask.JD_DIAGNOSTIC,in,db);var r=(ObjectNode)raw.path("requirements").get(0);r.put("trainingStatus","SUPPORTED");r.withArray("trainingEventIds").add("event");r.putArray("trainingBindings").addObject().put("eventId","event").put("findingId","gap");
        assertThatThrownBy(()->validator.validate(ModelTask.JD_DIAGNOSTIC,in,raw)).isInstanceOf(RecoveryException.class);
    }
    @Test void endedDesignSessionCannotRetryHistoricalFailure(){
        var s=design.create(db.object().put("requestId",Models.id()).put("scenarioId","ORDER_SERVICE"));String sid=s.path("id").asText();var fields=db.object();for(var f:s.path("stages").get(0).path("fieldDefinitions"))fields.put(f.path("id").asText(),"合成方案");
        var broken=new TrainingModelClient(baseLlm::connection,db,new cn.interview.client.OpenAiJsonClient(db.json()),60){@Override public ObjectNode call(ModelTask task,ObjectNode input,ObjectNode protocol){throw TrainingModelClient.failed("MODEL_TEST_FAILURE","合成失败");}};
        var failing=new ModelTaskRunner(db,runner.ops,broken,validator,runner.capacity,runner.handlers);var ds=new DesignPracticeService(db,runner.ops,failing);
        var body=db.object().put("requestId",Models.id()).put("expectedVersion",0).put("stageId","CLARIFY");body.set("artifact",fields);
        assertThatThrownBy(()->ds.submit(sid,body)).isInstanceOf(RecoveryException.class);var failed=db.get("v2_design_sessions",sid);String op=failed.path("operationId").asText();
        var ended=design.finish(sid,db.object().put("requestId",Models.id()).put("expectedVersion",failed.path("revision").asInt()).put("early",true).put("reason","先结束"));
        var before=db.get("v2_design_sessions",sid);assertThatThrownBy(()->runner.retry(op,db.object().put("requestId",Models.id()).put("expectedGeneration",0))).isInstanceOf(RecoveryException.class);
        assertThat(db.get("v2_design_sessions",sid)).isEqualTo(before);
    }
    @Test void identicalFailedStageMustRetryOriginalOperationInsteadOfDuplicatingArtifact(){
        var s=design.create(db.object().put("requestId",Models.id()).put("scenarioId","ORDER_SERVICE"));String sid=s.path("id").asText();var fields=db.object();for(var f:s.path("stages").get(0).path("fieldDefinitions"))fields.put(f.path("id").asText(),"合成方案");
        var broken=new TrainingModelClient(baseLlm::connection,db,new cn.interview.client.OpenAiJsonClient(db.json()),60){@Override public ObjectNode call(ModelTask task,ObjectNode input,ObjectNode protocol){throw TrainingModelClient.failed("MODEL_TEST_FAILURE","合成失败");}};
        var failing=new ModelTaskRunner(db,runner.ops,broken,validator,runner.capacity,runner.handlers);var ds=new DesignPracticeService(db,runner.ops,failing);
        var body=db.object().put("requestId",Models.id()).put("expectedVersion",0).put("stageId","CLARIFY");body.set("artifact",fields);
        assertThatThrownBy(()->ds.submit(sid,body)).isInstanceOf(RecoveryException.class);var failed=db.get("v2_design_sessions",sid);String operation=failed.path("operationId").asText();
        var repeat=db.object().put("requestId",Models.id()).put("expectedVersion",failed.path("revision").asInt()).put("stageId","CLARIFY").put("reuseCurrentArtifact",true);
        assertThatThrownBy(()->design.submit(sid,repeat)).isInstanceOfSatisfying(RecoveryException.class,e->{assertThat(e.code).isEqualTo("V2_RETRY_EXISTING_OPERATION");assertThat(e.recovery().get("operationId")).isEqualTo(operation);});
        assertThat(db.get("v2_design_sessions",sid).path("stages").get(0).path("artifactVersions")).hasSize(1);
        runner.retry(operation,db.object().put("requestId",Models.id()).put("expectedGeneration",0));
        var recovered=db.get("v2_design_sessions",sid);assertThat(recovered.path("stages").get(0).path("artifactVersions")).hasSize(1);assertThat(recovered.path("stages").get(0).path("state").asText()).isEqualTo("FEEDBACK_READY");
    }
}
