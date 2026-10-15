package cn.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class ModelValidationTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode session(){ObjectNode s=json.createObjectNode().put("context","");s.putArray("messages").addObject().put("role","USER").put("content","使用事务").put("questionIndex",0);return s;}
    ObjectNode report(){ObjectNode r=json.createObjectNode().put("summary","已作答");for(String name:new String[]{"技术准确性","理解深度","表达清晰度","追问应对能力"})r.withArray("dimensions").addObject().put("name",name).put("score",2).put("reason","已回答");var q=r.withArray("questions").addObject().put("questionIndex",0).put("question","为什么使用事务？").put("answerOutline","说明事务保证").put("knowledgeExplanation","事务原子性");for(String key:new String[]{"correct","incorrect","missing","uncertain","evidence"})q.putArray(key);r.putArray("weaknesses");r.putArray("suggestions");return r;}
    @Test void scoreCannotOverflowIntoAllowedRange(){ObjectNode r=report();((ObjectNode)r.path("dimensions").get(0)).put("score",4294967296L);assertThatThrownBy(()->ModelValidator.report(r,session())).isInstanceOf(ApiException.class);}
    @Test void questionIndexCannotOverflowIntoAnsweredIndex(){ObjectNode r=report();((ObjectNode)r.path("questions").get(0)).put("questionIndex",4294967296L);assertThatThrownBy(()->ModelValidator.report(r,session())).isInstanceOf(ApiException.class);}
    @Test void hypotheticalPlanMustMakeAssumptionVisibleInQuestion(){ObjectNode p=json.createObjectNode();p.putArray("questions").addObject().put("question","你们处理十万 QPS 的策略是什么？").put("topic","扩展性").put("level","L3").put("evidence","假设场景");assertThatThrownBy(()->ModelValidator.plan(p,1,false,"个人开发的订单管理项目")).isInstanceOf(ApiException.class);}

    @Test void acceptedModelEnumsAreNormalizedBeforeConsumption() {
        ObjectNode decision=json.createObjectNode().put("action"," FOLLOW_UP ").put("question","为什么选择事务？").put("topic","事务").put("reason","检查理解");ModelValidator.decision(decision);assertThat(decision.path("action").asText()).isEqualTo("FOLLOW_UP");
        ObjectNode p=json.createObjectNode();p.putArray("questions").addObject().put("question","如何理解事务？").put("topic","事务").put("level"," L1 ").put("evidence","通用岗位知识");ModelValidator.plan(p,1,false,"");assertThat(p.path("questions").get(0).path("level").asText()).isEqualTo("L1");
        ObjectNode r=report();((ObjectNode)r.path("dimensions").get(0)).put("name"," 技术准确性 ");ModelValidator.report(r,session());assertThat(r.path("dimensions").get(0).path("name").asText()).isEqualTo("技术准确性");
        ObjectNode predictions=json.createObjectNode();predictions.putArray("risks");for(int n=0;n<10;n++) {var q=predictions.withArray("questions").addObject().put("question","如何解释事务？").put("evidence","需要补充").put("knowledgePoint","事务").put("priority"," 高 ").put("answerOutline","结合项目解释");q.putArray("followUps").add("如何验证？");}ModelValidator.prediction(predictions,"");assertThat(predictions.path("questions").get(0).path("priority").asText()).isEqualTo("高");
    }

    @Test void predictionQuestionsCannotContainReferenceAnswers() {
        ObjectNode predictions=json.createObjectNode();predictions.putArray("risks");for(int n=0;n<10;n++) {var q=predictions.withArray("questions").addObject().put("question","请解释事务？参考答案：事务满足 ACID。").put("evidence","需要补充").put("knowledgePoint","事务").put("priority","高").put("answerOutline","结合项目解释");q.putArray("followUps").add("如何验证？");}
        assertThatThrownBy(()->ModelValidator.prediction(predictions,"")).isInstanceOf(ApiException.class);
    }

}
