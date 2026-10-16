package cn.interview.service;

import cn.interview.client.LlmClient;
import cn.interview.mapper.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RecoveryBoundaryTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper();
    final class FailingModel extends LlmClient {
        String failTask;
        FailingModel(){super(RecoveryBoundaryTest.this.json,"mock","","","",1,"");}
        @Override public ObjectNode call(String task,ObjectNode input){if(task.equals(failTask))throw new IllegalStateException("private-debug-detail");return super.call(task,input);}
    }
    ObjectNode answer(ObjectNode s){return json.createObjectNode().put("content","失败时使用事务回滚").put("requestId","recovery-test").put("expectedMessageId",s.path("messages").get(s.path("messages").size()-1).path("id").asText());}
    @Test void unexpectedModelFailuresRemainRecoverableAtEveryGenerationStage()throws Exception {
        Store store=new Store(data.toString(),json);FailingModel model=new FailingModel();InterviewService service=new InterviewService(store,new MaterialService(store),model,json);
        model.failTask="planner";ObjectNode s=assertDoesNotThrow(()->service.create(json.createObjectNode().put("durationMinutes",5)));String id=s.path("id").asText();
        assertThat(s.path("status").asText()).isEqualTo("FAILED");assertThat(s.toString()).doesNotContain("private-debug-detail");model.failTask=null;s=service.retry(id);assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");
        model.failTask="interviewer";s=service.answer(id,answer(s));assertThat(s.path("status").asText()).isEqualTo("INTERRUPTED");assertThat(s.path("messages").size()).isEqualTo(2);assertThat(s.toString()).doesNotContain("private-debug-detail");model.failTask=null;s=service.retry(id);assertThat(s.path("messages").size()).isEqualTo(3);
        model.failTask="evaluator";s=service.finish(id);assertThat(s.path("status").asText()).isEqualTo("COMPLETED");assertThat(s.path("reportStatus").asText()).isEqualTo("FAILED");assertThat(s.toString()).doesNotContain("private-debug-detail");model.failTask=null;assertThat(service.report(id,true).path("dimensions").size()).isEqualTo(4);
    }
    @Test void storageFailureIsPropagatedInsteadOfReportingSuccess()throws Exception {
        Store store=new Store(data.toString(),json);FailingModel model=new FailingModel();InterviewService service=new InterviewService(store,new MaterialService(store),model,json);ObjectNode s=service.create(json.createObjectNode().put("durationMinutes",5));String id=s.path("id").asText();
        Store failingStore=new Store(data.toString(),json){@Override public void saveSession(ObjectNode value){if(value.path("messages").size()==3)throw new DataAccessResourceFailureException("测试数据库写入失败");super.saveSession(value);}};
        InterviewService failing=new InterviewService(failingStore,new MaterialService(failingStore),model,json);
        assertThatThrownBy(()->failing.answer(id,answer(s))).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(store.session(id).path("messages").size()).isEqualTo(2);
    }
}
