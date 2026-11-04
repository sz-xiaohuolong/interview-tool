package cn.interview.service.training;

import cn.interview.client.interview.FocusedInterviewModelClient;
import cn.interview.client.training.ModelTask;
import cn.interview.client.training.TrainingModelClient;
import cn.interview.common.Models;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.SqliteStoreTestAccess;
import cn.interview.mapper.TrainingStore;
import cn.interview.task.ModelCapacity;
import cn.interview.task.ModelTaskHandler;
import cn.interview.task.ModelTaskRunner;
import cn.interview.testsupport.SyntheticTrainingData;
import cn.interview.validation.TrainingOutputValidator;

import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"app.llm.provider=mock","app.training.enabled=true"})
class TrainingEvaluationSafetyTest {
    @Autowired ObjectProvider<ModelTaskHandler> handlers;
    @Autowired TrainingModelClient trainingLlm;
    @Autowired FocusedInterviewModelClient baseLlm;

    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired TrainingStore db;@Autowired ModelTaskRunner runner;@Autowired TrainingOutputValidator validator;@Autowired OperationService ops;@Autowired ModelCapacity capacity;@Autowired PracticeService practice;
    ObjectNode input(String answer){var in=db.object().put("answerSnapshotId","a");in.set("rubric",practice.rubric());in.putArray("snapshots").addObject().put("id","a").put("text",answer).put("purpose","ANSWER");return in;}
    @Test void failedEvaluationKeepsSealedAnswerAndExplicitRetryUsesSameGroup(){
        var caseRow=db.object().put("familyId",Models.id()).put("caseVersion",1).put("sourceKind","SESSION").put("sourceId","synthetic").put("question","解释事务").put("context","").put("mock",true);caseRow.set("rubric",practice.rubric());caseRow.putArray("priorDialogue");db.atomic(()->db.insert("v2_practice_cases",caseRow));
        var a=practice.attempt(caseRow.path("id").asText(),db.object().put("requestId","draft").put("exposureDeclaration","UNKNOWN")).body();String aid=a.path("id").asText();
        AtomicInteger calls=new AtomicInteger();var broken=new TrainingModelClient(baseLlm::connection,db.json(),new cn.interview.client.transport.OpenAiJsonClient(db.json()),60){@Override public ObjectNode call(ModelTask task,ObjectNode input,ObjectNode protocol){calls.incrementAndGet();throw TrainingModelClient.failed("MODEL_TEST_FAILURE","合成失败");}};
        var failing=new ModelTaskRunner(db,ops,broken,validator,capacity,handlers);var service=new PracticeService(db,ops,failing);var body=db.object().put("requestId","submit").put("expectedVersion",0).put("answer","不可覆盖的合成答案");
        assertThatThrownBy(()->service.submit(aid,body)).isInstanceOf(RecoveryException.class);
        var failed=db.get("v2_practice_attempts",aid);assertThat(failed.path("status").asText()).isEqualTo("FAILED");assertThat(failed.path("answerText").asText()).isEqualTo("不可覆盖的合成答案");
        assertThatThrownBy(()->service.submit(aid,body)).isInstanceOf(RecoveryException.class);assertThat(calls).hasValue(1);
        String group=failed.path("primaryGroupId").asText();String op=db.get("v2_practice_evaluation_groups",group).path("operationId").asText();
        runner.retry(op,db.object().put("requestId","retry").put("expectedGeneration",0));
        assertThat(db.get("v2_practice_attempts",aid).path("status").asText()).isEqualTo("READY");assertThat(db.get("v2_practice_attempts",aid).path("primaryGroupId").asText()).isEqualTo(group);
        assertThat(ops.safe(op).path("generation").asInt()).isEqualTo(1);
    }
    @Test void resultStorageFailureRetainsRecoveryPointersAndLeavesRetryableState(){
        var c=db.object().put("familyId",Models.id()).put("caseVersion",1).put("sourceKind","SESSION").put("sourceId","synthetic");db.atomic(()->db.insert("v2_practice_cases",c));
        var spy=org.mockito.Mockito.spy(db);
        org.mockito.Mockito.doAnswer(inv->{String table=inv.getArgument(0);ObjectNode row=inv.getArgument(1);int revision=inv.getArgument(2);if(table.equals("v2_practice_evaluation_groups")&&row.path("status").asText().equals("READY"))throw new org.springframework.dao.DataAccessResourceFailureException("合成存储失败");return db.save(table,row,revision);}).when(spy).save(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt());
        var tested=new ModelTaskRunner(spy,ops,trainingLlm,validator,capacity,handlers);String key=Models.id();
        assertThatThrownBy(()->tested.run(ModelTask.PRACTICE_EVALUATOR,"STORAGE_TEST",key,db.object().put("requestId","one"),()->{var g=db.object().put("caseId",c.path("id").asText()).put("status","RUNNING");g.putArray("attemptIds");db.insert("v2_practice_evaluation_groups",g);return new ModelTaskRunner.Work("v2_practice_evaluation_groups",g,input("保留的测试答案"));}))
            .isInstanceOfSatisfying(RecoveryException.class,e->{assertThat(e.recovery().get("operationId")).isNotNull();assertThat(e.recovery().get("nextAction")).isEqualTo("CHECK_STORAGE");});
        String op=SqliteStoreTestAccess.jdbc(db.database()).queryForObject("SELECT id FROM v2_operations WHERE scope_key=?",String.class,key);assertThat(ops.safe(op).path("state").asText()).isEqualTo("FAILED");
    }
}
