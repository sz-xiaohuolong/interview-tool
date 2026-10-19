package cn.interview.service.material;

import cn.interview.exception.ApiException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProjectEvidenceValidationTest {
    final ObjectMapper json = new ObjectMapper();
    final ProjectEvidenceService evidence = new ProjectEvidenceService(json);
    ObjectNode project() { return json.createObjectNode().put("id", "p1").put("name", "合成订单项目").put("brief", "我负责订单接口").put("hypothetical", false); }
    ArrayNode messages() {
        var messages = json.createArrayNode();
        messages.addObject().put("id", "q1").put("role", "ASSISTANT").put("content", "你在订单项目具体负责什么？").put("questionId", "question-one");
        messages.addObject().put("id", "a1").put("role", "USER").put("content", "我负责订单接口，唯一索引负责拒绝重复订单。").put("questionId", "question-one");
        messages.addObject().put("id", "q2").put("role", "ASSISTANT").put("content", "压测如何验证？").put("questionId", "question-two");
        return messages;
    }
    ObjectNode raw() {
        var raw = json.createObjectNode(); var items = raw.putArray("items");
        for (String id : new String[]{"BUSINESS", "RESPONSIBILITY", "ARCHITECTURE", "TECH_CHOICE", "FAILURE", "PERFORMANCE", "TRADEOFF"}) {
            items.addObject().put("checkpointId", id).put("status", "UNCOVERED").put("reason", "没有本项回答").putArray("evidence");
        }
        var item = (ObjectNode) items.get(1); item.put("status", "PARTIAL").put("reason", "说明了个人接口职责，尚缺实施范围与边界。");
        item.withArray("evidence").addObject().put("questionMessageId", "q1").put("answerMessageId", "a1").put("questionQuote", "具体负责什么").put("answerQuote", "我负责订单接口");
        return raw;
    }
    ObjectNode ref(ObjectNode raw) { return (ObjectNode) raw.path("items").get(1).path("evidence").get(0); }

    @Test void validQuotesKeepExactSubmittedTextAndServerOwnedProjectIdentity() {
        var raw = raw(); raw.putObject("project").put("name", "伪造项目");
        var result = evidence.normalize(raw, messages(), project());
        assertThat(result.path("project").path("name").asText()).isEqualTo("合成订单项目");
        assertThat(result.path("items").get(1).path("status").asText()).isEqualTo("PARTIAL");
        assertThat(result.path("items").get(1).path("evidence").get(0).path("answerQuote").asText()).isEqualTo("我负责订单接口");
    }
    @Test void fabricatedAnswerOrQuestionQuoteIsRejected() {
        var badAnswer = raw(); ref(badAnswer).put("answerQuote", "我独立设计了全公司架构");
        assertThatThrownBy(() -> evidence.normalize(badAnswer, messages(), project())).isInstanceOf(ApiException.class);
        var badQuestion = raw(); ref(badQuestion).put("questionQuote", "你是否负责整个部门");
        assertThatThrownBy(() -> evidence.normalize(badQuestion, messages(), project())).isInstanceOf(ApiException.class);
    }
    @Test void answerFromAnotherQuestionOrWrongRoleCannotCountAsEvidence() {
        var other = raw(); ref(other).put("questionMessageId", "q2").put("questionQuote", "压测如何验证");
        assertThatThrownBy(() -> evidence.normalize(other, messages(), project())).isInstanceOf(ApiException.class);
        var wrongRole = raw(); ref(wrongRole).put("answerMessageId", "q1");
        assertThatThrownBy(() -> evidence.normalize(wrongRole, messages(), project())).isInstanceOf(ApiException.class);
    }
    @Test void technicalSourceStatusCannotBecomeAnswerCoverageAndClearRequiresAnswer() {
        var technical = raw(); ((ObjectNode) technical.path("items").get(1)).put("status", "SUPPORTED");
        assertThatThrownBy(() -> evidence.normalize(technical, messages(), project())).isInstanceOf(ApiException.class);
        var empty = raw(); ((ObjectNode) empty.path("items").get(1)).put("status", "CLEAR").putArray("evidence");
        assertThatThrownBy(() -> evidence.normalize(empty, messages(), project())).isInstanceOf(ApiException.class);
    }
    @Test void duplicateMissingOrUnknownCheckpointsDoNotSilentlyFabricateCoverage() {
        var duplicate = raw(); ((ObjectNode) duplicate.path("items").get(0)).put("checkpointId", "RESPONSIBILITY");
        assertThatThrownBy(() -> evidence.normalize(duplicate, messages(), project())).isInstanceOf(ApiException.class);
        var missing = raw(); ((ArrayNode) missing.path("items")).remove(0);
        assertThatThrownBy(() -> evidence.normalize(missing, messages(), project())).isInstanceOf(ApiException.class);
        var unknown = raw(); ((ObjectNode) unknown.path("items").get(0)).put("checkpointId", "UNKNOWN");
        assertThatThrownBy(() -> evidence.normalize(unknown, messages(), project())).isInstanceOf(ApiException.class);
    }
    @Test void unaskedOrUnansweredDoesNotKeepModelAbilityJudgment() {
        var raw = raw(); ((ObjectNode) raw.path("items").get(5)).put("reason", "你没有性能优化能力");
        var result = evidence.normalize(raw, messages(), project());
        assertThat(result.path("items").get(5).path("reason").asText()).doesNotContain("没有性能优化能力").contains("未覆盖不代表能力不足");
        assertThat(result.path("items").get(5).path("evidence")).isEmpty();
    }
    @Test void hypotheticalOrAbsentProjectNeverCreatesRealExperienceMatrix() {
        assertThat(evidence.normalize(json.createObjectNode(), messages(), project().put("hypothetical", true))).isNull();
        assertThat(evidence.uncovered(json.createObjectNode())).isNull();
    }
}
