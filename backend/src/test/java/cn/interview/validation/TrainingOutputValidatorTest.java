package cn.interview.validation;

import cn.interview.client.mock.TrainingMockTasksTestAccess;
import cn.interview.client.training.ModelTask;
import cn.interview.exception.RecoveryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 纯 JSON 校验无需 Spring、SQLite 或训练目录迁移。 */
class TrainingOutputValidatorTest {
    final ObjectMapper json=new ObjectMapper();
    final TrainingOutputValidator validator=new TrainingOutputValidator(json);
    ObjectNode input(String answer){
        var input=json.createObjectNode().put("answerSnapshotId","a");
        var rubric=input.putArray("rubric");for(String id:new String[]{"SCOPE","REASONING","COVERAGE","EXPRESSION"})rubric.addObject().put("checkId",id);
        input.putArray("snapshots").addObject().put("id","a").put("text",answer).put("purpose","ANSWER");return input;
    }
    @Test void fabricatedQuoteAndUnknownCheckpointFailButMissingUsesWholeRange(){
        var input=input("回答中的事实🙂");var raw=TrainingMockTasksTestAccess.evaluation(input,json);var check=(ObjectNode)raw.path("checks").get(0);check.put("status","MET");check.withArray("evidenceRefs").addObject().put("kind","QUOTE").put("snapshotId","a").put("quote","没有出现的原文");
        assertThatThrownBy(()->validator.validate(ModelTask.PRACTICE_EVALUATOR,input,raw)).isInstanceOf(RecoveryException.class);
        check.put("status","GAP");check.putArray("evidenceRefs").addObject().put("kind","ABSENCE_CHECK").put("snapshotId","a").put("checkId","SCOPE").put("start",0).put("end","回答中的事实🙂".length());
        assertThat(validator.validate(ModelTask.PRACTICE_EVALUATOR,input,raw).path("checks").get(0).path("evidenceRefs").get(0).has("quote")).isFalse();
        check.put("checkId","OTHER");assertThatThrownBy(()->validator.validate(ModelTask.PRACTICE_EVALUATOR,input,raw)).isInstanceOf(RecoveryException.class);
    }
    @Test void repeatedQuoteAndHalfSurrogateCannotBeCited(){
        for(String quote:new String[]{"同",String.valueOf('\uD83D')}){
            var input=input("同同🙂");var raw=TrainingMockTasksTestAccess.evaluation(input,json);var check=(ObjectNode)raw.path("checks").get(0);check.put("status","MET").withArray("evidenceRefs").addObject().put("kind","QUOTE").put("snapshotId","a").put("quote",quote);
            assertThatThrownBy(()->validator.validate(ModelTask.PRACTICE_EVALUATOR,input,raw)).isInstanceOf(RecoveryException.class);
        }
    }
    @Test void fixedIssueRequiresExistingNewQuoteAndMatchingCheckpoint(){
        var in=input("old");in.put("leftAnswer","old").put("rightAnswer","new").put("leftSnapshotId","left").put("rightSnapshotId","right");
        in.putArray("snapshots").addObject().put("id","left").put("text","old");in.withArray("snapshots").addObject().put("id","right").put("text","new");
        in.putObject("leftEvaluation").putArray("checks").addObject().put("checkId","SCOPE").put("status","GAP");in.putObject("rightEvaluation").putArray("checks").addObject().put("checkId","SCOPE").put("status","MET");
        var raw=TrainingMockTasksTestAccess.comparison(json);var fixed=raw.withArray("fixed").addObject().put("checkId","SCOPE").put("description","修正");
        fixed.putArray("leftEvidenceRefs").addObject().put("kind","ABSENCE_CHECK").put("snapshotId","left").put("checkId","SCOPE").put("start",0).put("end",3);
        fixed.putArray("rightEvidenceRefs").addObject().put("kind","ABSENCE_CHECK").put("snapshotId","right").put("checkId","SCOPE").put("start",0).put("end",3);
        assertThatThrownBy(()->validator.validate(ModelTask.PRACTICE_COMPARE,in,raw)).isInstanceOf(RecoveryException.class);
        fixed.putArray("rightEvidenceRefs").addObject().put("kind","QUOTE").put("snapshotId","right").put("quote","new");
        assertThat(validator.validate(ModelTask.PRACTICE_COMPARE,in,raw).path("fixed")).hasSize(1);
        ((ObjectNode)fixed.path("leftEvidenceRefs").get(0)).put("checkId","EXPRESSION");
        assertThatThrownBy(()->validator.validate(ModelTask.PRACTICE_COMPARE,in,raw)).isInstanceOf(RecoveryException.class);
    }
}
