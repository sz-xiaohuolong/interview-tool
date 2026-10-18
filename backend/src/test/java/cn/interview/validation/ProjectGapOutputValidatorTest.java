package cn.interview.validation;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ProjectGapOutputValidatorTest {
    final ObjectMapper json = new ObjectMapper();
    ObjectNode input() {
        var input = json.createObjectNode();
        input.putObject("project").put("name", "合成订单服务").put("brief", "我负责订单唯一键，重复请求返回同一订单。");
        input.putObject("checkpoint").put("checkpointId", "PERFORMANCE");
        input.putArray("priorQuestions").add("合成订单服务之前如何压测？");
        return input;
    }
    ObjectNode raw() { return json.createObjectNode().put("question", "合成订单服务在重复请求压测中，你会观察哪些延迟和冲突指标？").put("checkpointId", "PERFORMANCE").put("projectQuote", "订单唯一键"); }

    @Test void newQuestionKeepsProjectBindingWithoutInjectingModelFields() {
        var raw = raw().put("answer", "不应返回参考答案");
        var result = ProjectGapOutputValidator.validate(raw, input());
        assertThat(result.path("question").asText()).contains("合成订单服务");
        assertThat(result.has("answer")).isFalse();
    }
    @Test void wrongCheckpointAndFabricatedProjectQuoteAreRejected() {
        assertThatThrownBy(() -> ProjectGapOutputValidator.validate(raw().put("checkpointId", "FAILURE"), input())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProjectGapOutputValidator.validate(raw().put("projectQuote", "每秒百万请求"), input())).isInstanceOf(ApiException.class);
    }
    @Test void genericOrRepeatedQuestionCannotBePassedOffAsNewProjectPractice() {
        assertThatThrownBy(() -> ProjectGapOutputValidator.validate(raw().put("question", "什么是Java反射？"), input())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> ProjectGapOutputValidator.validate(raw().put("question", "合成订单服务之前如何压测？"), input())).isInstanceOf(ApiException.class);
    }

    ObjectNode defaultNameInput() {
        var input = input();
        ((ObjectNode) input.path("project")).put("name", "本场主项目")
                .put("brief", "合成项目A：订单查询服务。我负责查询接口和Redis缓存接入、回源与失败告警；整体架构与缓存键规则由同事决定。");
        return input;
    }

    @Test void titleInsideBriefCannotReplaceTheRequiredLiteralBinding() {
        var output = raw().put("question", "项目A的订单查询服务主要解决什么业务问题？你负责的查询接口需要支持哪些使用场景？")
                .put("projectQuote", "合成项目A：订单查询服务。我负责查询接口和Redis缓存接入、回源与失败告警；整体架构与缓存键规则由同事决定。");
        assertThatThrownBy(() -> ProjectGapOutputValidator.validate(output, defaultNameInput()))
                .isInstanceOf(ApiException.class);
    }

    @Test void defaultProjectNameStillProvidesAnExplicitLiteralBinding() {
        var output = raw().put("question", "本场主项目的订单查询服务主要解决什么业务问题？你负责的查询接口需要支持哪些使用场景？")
                .put("projectQuote", "合成项目A：订单查询服务。我负责查询接口和Redis缓存接入、回源与失败告警；整体架构与缓存键规则由同事决定。");
        assertThat(ProjectGapOutputValidator.validate(output, defaultNameInput()).path("question").asText())
                .isEqualTo("本场主项目的订单查询服务主要解决什么业务问题？你负责的查询接口需要支持哪些使用场景？");
    }

    @Test void aCompleteShortFactProvidesBindingWithoutTheDefaultProjectName() {
        var output = raw().put("question", "围绕你负责的“回源与失败告警”，你会如何核对失败次数、告警触发与业务影响？")
                .put("projectQuote", "回源与失败告警");
        assertThat(ProjectGapOutputValidator.validate(output, defaultNameInput()).path("projectQuote").asText())
                .isEqualTo("回源与失败告警");
    }
}
