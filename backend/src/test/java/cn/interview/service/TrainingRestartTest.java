package cn.interview.service;

import cn.interview.Application;
import cn.interview.common.Models;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.SqliteStoreTestAccess;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.*;

class TrainingRestartTest {
    ConfigurableApplicationContext start(java.nio.file.Path data){return new SpringApplicationBuilder(Application.class).web(org.springframework.boot.WebApplicationType.NONE).run("--app.data-dir="+data,"--app.llm.provider=mock","--app.training.enabled=true","--spring.main.banner-mode=off","--logging.level.root=ERROR");}
    @Test void restartMarksPersistedPendingFeedbackInterruptedAndRejectsOldGeneration(){
        var data=SyntheticTrainingData.migrated();String sid,opid;com.fasterxml.jackson.databind.node.ObjectNode oldOperation;
        try(var app=start(data)){
            var db=app.getBean(TrainingStore.class);var design=app.getBean(DesignPracticeService.class);
            var s=design.create(db.object().put("requestId",Models.id()).put("scenarioId","ORDER_SERVICE"));sid=s.path("id").asText();var body=db.object().put("requestId",Models.id()).put("expectedVersion",0).put("stageId","CLARIFY");var fields=body.putObject("artifact");s.path("stages").get(0).path("fieldDefinitions").forEach(f->fields.put(f.path("id").asText(),"合成重启产物"));
            s=design.submit(sid,body);opid=s.path("operationId").asText();var resource=db.get("v2_design_sessions",sid);var stage=(com.fasterxml.jackson.databind.node.ObjectNode)resource.path("stages").get(0);stage.put("state","FEEDBACK_PENDING");var artifact=(com.fasterxml.jackson.databind.node.ObjectNode)stage.path("artifactVersions").get(0);artifact.put("feedbackStatus","PENDING");artifact.remove("feedback");
            db.atomic(()->db.save("v2_design_sessions",resource,resource.path("revision").asInt()));
            oldOperation=db.get("v2_operations",opid).put("state","RUNNING").put("expectedRevision",resource.path("revision").asInt());
            SqliteStoreTestAccess.jdbc(db.database()).update("UPDATE v2_operations SET state='RUNNING',expected_revision=?,payload=? WHERE id=?",resource.path("revision").asInt(),db.encode(oldOperation),opid);
        }
        try(var app=start(data)){
            var db=app.getBean(TrainingStore.class);var runner=app.getBean(ModelTaskRunner.class);var interrupted=db.get("v2_design_sessions",sid);
            assertThat(interrupted.path("stages").get(0).path("state").asText()).isEqualTo("INTERRUPTED");assertThat(db.get("v2_operations",opid).path("state").asText()).isEqualTo("INTERRUPTED");
            runner.retry(opid,db.object().put("requestId",Models.id()).put("expectedGeneration",0));var recovered=db.get("v2_design_sessions",sid);
            assertThat(recovered.path("stages").get(0).path("state").asText()).isEqualTo("FEEDBACK_READY");assertThat(recovered.path("stages").get(0).path("artifactVersions")).hasSize(1);
            assertThatThrownBy(()->runner.execute(oldOperation)).isInstanceOfSatisfying(RecoveryException.class,e->assertThat(e.code).isEqualTo("V2_OPERATION_SUPERSEDED"));assertThat(db.get("v2_design_sessions",sid)).isEqualTo(recovered);
        }
    }
}
