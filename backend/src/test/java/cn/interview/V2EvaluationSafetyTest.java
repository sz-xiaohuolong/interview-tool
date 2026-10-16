package cn.interview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.llm.provider=mock","app.v2.enabled=true"})
class V2EvaluationSafetyTest {
    static final Path DATA=V2TestData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired V2Store db;@Autowired V2ModelRunner runner;@Autowired V2OutputValidator validator;@Autowired V2Operations ops;@Autowired V2ModelCapacity capacity;@Autowired PracticeService practice;
    ObjectNode input(String answer){var in=db.object().put("answerSnapshotId","a");in.set("rubric",practice.rubric());in.putArray("snapshots").addObject().put("id","a").put("text",answer).put("purpose","ANSWER");return in;}
    @Test void fabricatedQuoteAndUnknownCheckpointFailButMissingUsesWholeRange(){
        var input=input("回答中的事实🙂");var raw=V2MockTasks.evaluation(input,db);var check=(ObjectNode)raw.path("checks").get(0);check.put("status","MET");check.withArray("evidenceRefs").addObject().put("kind","QUOTE").put("snapshotId","a").put("quote","没有出现的原文");
        assertThatThrownBy(()->validator.validate(V2Task.PRACTICE_EVALUATOR,input,raw)).isInstanceOf(V2Exception.class);
        check.put("status","GAP");check.putArray("evidenceRefs").addObject().put("kind","ABSENCE_CHECK").put("snapshotId","a").put("checkId","SCOPE").put("start",0).put("end","回答中的事实🙂".length());
        assertThat(validator.validate(V2Task.PRACTICE_EVALUATOR,input,raw).path("checks").get(0).path("evidenceRefs").get(0).has("quote")).isFalse();
        check.put("checkId","OTHER");assertThatThrownBy(()->validator.validate(V2Task.PRACTICE_EVALUATOR,input,raw)).isInstanceOf(V2Exception.class);
    }
    @Test void repeatedQuoteAndHalfSurrogateCannotBeCited(){
        for(String quote:new String[]{"同",String.valueOf('\uD83D')}){
            var input=input("同同🙂");var raw=V2MockTasks.evaluation(input,db);var check=(ObjectNode)raw.path("checks").get(0);check.put("status","MET").withArray("evidenceRefs").addObject().put("kind","QUOTE").put("snapshotId","a").put("quote",quote);
            assertThatThrownBy(()->validator.validate(V2Task.PRACTICE_EVALUATOR,input,raw)).isInstanceOf(V2Exception.class);
        }
    }
    @Test void fixedIssueRequiresExistingNewQuoteAndMatchingCheckpoint(){
        var in=input("old");in.put("leftAnswer","old").put("rightAnswer","new").put("leftSnapshotId","left").put("rightSnapshotId","right");
        in.putArray("snapshots").addObject().put("id","left").put("text","old");in.withArray("snapshots").addObject().put("id","right").put("text","new");
        in.putObject("leftEvaluation").putArray("checks").addObject().put("checkId","SCOPE").put("status","GAP");in.putObject("rightEvaluation").putArray("checks").addObject().put("checkId","SCOPE").put("status","MET");
        var raw=V2MockTasks.comparison(db);var fixed=raw.withArray("fixed").addObject().put("checkId","SCOPE").put("description","修正");
        fixed.putArray("leftEvidenceRefs").addObject().put("kind","ABSENCE_CHECK").put("snapshotId","left").put("checkId","SCOPE").put("start",0).put("end",3);
        fixed.putArray("rightEvidenceRefs").addObject().put("kind","ABSENCE_CHECK").put("snapshotId","right").put("checkId","SCOPE").put("start",0).put("end",3);
        assertThatThrownBy(()->validator.validate(V2Task.PRACTICE_COMPARE,in,raw)).isInstanceOf(V2Exception.class);
        fixed.putArray("rightEvidenceRefs").addObject().put("kind","QUOTE").put("snapshotId","right").put("quote","new");
        assertThat(validator.validate(V2Task.PRACTICE_COMPARE,in,raw).path("fixed")).hasSize(1);
        ((ObjectNode)fixed.path("leftEvidenceRefs").get(0)).put("checkId","EXPRESSION");
        assertThatThrownBy(()->validator.validate(V2Task.PRACTICE_COMPARE,in,raw)).isInstanceOf(V2Exception.class);
    }
    @Test void failedEvaluationKeepsSealedAnswerAndExplicitRetryUsesSameGroup(){
        var caseRow=db.object().put("familyId",Models.id()).put("caseVersion",1).put("sourceKind","SESSION").put("sourceId","synthetic").put("question","解释事务").put("context","").put("mock",true);caseRow.set("rubric",practice.rubric());caseRow.putArray("priorDialogue");db.atomic(()->db.insert("v2_practice_cases",caseRow));
        var a=practice.attempt(caseRow.path("id").asText(),db.object().put("requestId","draft").put("exposureDeclaration","UNKNOWN"));String aid=a.path("id").asText();
        AtomicInteger calls=new AtomicInteger();var broken=new V2LlmClient(runner.llm.legacy,db){@Override ObjectNode call(V2Task task,ObjectNode input,ObjectNode protocol){calls.incrementAndGet();throw V2LlmClient.failed("MODEL_TEST_FAILURE","合成失败");}};
        var failing=new V2ModelRunner(db,ops,broken,validator,capacity,runner.handlers);var service=new PracticeService(db,ops,failing);var body=db.object().put("requestId","submit").put("expectedVersion",0).put("answer","不可覆盖的合成答案");
        assertThatThrownBy(()->service.submit(aid,body)).isInstanceOf(V2Exception.class);
        var failed=db.get("v2_practice_attempts",aid);assertThat(failed.path("status").asText()).isEqualTo("FAILED");assertThat(failed.path("answerText").asText()).isEqualTo("不可覆盖的合成答案");
        assertThatThrownBy(()->service.submit(aid,body)).isInstanceOf(V2Exception.class);assertThat(calls).hasValue(1);
        String group=failed.path("primaryGroupId").asText();String op=db.get("v2_practice_evaluation_groups",group).path("operationId").asText();
        runner.retry(op,db.object().put("requestId","retry").put("expectedGeneration",0));
        assertThat(db.get("v2_practice_attempts",aid).path("status").asText()).isEqualTo("READY");assertThat(db.get("v2_practice_attempts",aid).path("primaryGroupId").asText()).isEqualTo(group);
        assertThat(ops.safe(op).path("generation").asInt()).isEqualTo(1);
    }
    @Test void resultStorageFailureRetainsRecoveryPointersAndLeavesRetryableState(){
        var c=db.object().put("familyId",Models.id()).put("caseVersion",1).put("sourceKind","SESSION").put("sourceId","synthetic");db.atomic(()->db.insert("v2_practice_cases",c));
        var spy=org.mockito.Mockito.spy(db);
        org.mockito.Mockito.doAnswer(inv->{String table=inv.getArgument(0);ObjectNode row=inv.getArgument(1);int revision=inv.getArgument(2);if(table.equals("v2_practice_evaluation_groups")&&row.path("status").asText().equals("READY"))throw new org.springframework.dao.DataAccessResourceFailureException("合成存储失败");return db.save(table,row,revision);}).when(spy).save(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
        var tested=new V2ModelRunner(spy,ops,runner.llm,validator,capacity,runner.handlers);String key=Models.id();
        assertThatThrownBy(()->tested.run(V2Task.PRACTICE_EVALUATOR,"STORAGE_TEST",key,db.object().put("requestId","one"),()->{var g=db.object().put("caseId",c.path("id").asText()).put("status","RUNNING");g.putArray("attemptIds");db.insert("v2_practice_evaluation_groups",g);return new V2ModelRunner.Work("v2_practice_evaluation_groups",g,input("保留的测试答案"));}))
            .isInstanceOfSatisfying(V2Exception.class,e->{assertThat(e.recovery.get("operationId")).isNotNull();assertThat(e.recovery.get("nextAction")).isEqualTo("CHECK_STORAGE");});
        String op=db.legacy.jdbc.queryForObject("SELECT id FROM v2_operations WHERE scope_key=?",String.class,key);assertThat(ops.safe(op).path("state").asText()).isEqualTo("FAILED");
    }
}
