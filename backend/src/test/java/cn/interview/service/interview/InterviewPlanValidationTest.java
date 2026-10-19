package cn.interview.service.interview;

import cn.interview.exception.ApiException;
import cn.interview.validation.InterviewOutputValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** 准备失败须保留严格校验，并提供不包含资料正文的具体原因。 */
class InterviewPlanValidationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final InterviewOutputValidator validator = new InterviewOutputValidator(json);

    private ObjectNode plan(String quote) {
        var raw = json.createObjectNode();
        raw.putArray("questions").addObject().put("id", "q1").put("topic", "AI_RAG")
            .put("question", "请说明你实际负责的检索流程？").put("evidence", quote)
            .putArray("sourceIds");
        ((ObjectNode) raw.path("questions").get(0)).putArray("backups");
        return raw;
    }

    @Test void 非原文引用拒绝时说明题号与原因且不泄露模型正文() {
        var raw = plan("敏感标记：模型编造的个人职责");
        assertThatThrownBy(() -> validator.questions(raw, json.createArrayNode(), "合成资料：本人负责接口", "", 1))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code).isEqualTo("V3_MODEL_INVALID");
                assertThat(error.getMessage()).contains("第1道主问题", "资料引用", "原文", "显式重试")
                    .doesNotContain("敏感标记", "本人负责接口");
            });
    }

    @Test void 未使用简历依据时不能只用通用标记通过并须说明缺少简历题() {
        var raw = plan("通用岗位知识");
        assertThatThrownBy(() -> validator.questions(raw, json.createArrayNode(), "合成简历：负责接口", "", 1))
            .hasMessageContaining("至少一道").hasMessageContaining("简历原文");
    }

    @Test void 四阶段顺序错误须定位题号且继续拒绝不完整闭环() {
        var raw = json.createObjectNode(); var list = raw.putArray("questions");
        for (String stage : new String[]{"INTRO", "TECHNICAL", "PROJECT", "QUESTIONS"})
            list.addObject().put("stage", stage);
        var clean = json.createArrayNode();
        for (int i = 0; i < 4; i++) clean.addObject();
        assertThatThrownBy(() -> validator.stageQuestions(raw, clean, json.createObjectNode()))
            .hasMessageContaining("第3道主问题").hasMessageContaining("阶段顺序");
    }

    @Test void 题干过长或非法来源须保留拒绝并给出对应原因() {
        var raw = plan("通用岗位知识");
        var question = (ObjectNode) raw.path("questions").get(0);
        question.put("question", "长".repeat(2001));
        assertThatThrownBy(() -> validator.questions(raw, json.createArrayNode(), "", "", 1))
            .hasMessageContaining("第1道主问题").hasMessageContaining("题干").hasMessageContaining("长度限制");
        question.put("question", "合成题目？").putArray("sourceIds").add("不应泄露的编造来源");
        assertThatThrownBy(() -> validator.questions(raw, json.createArrayNode(), "", "", 1))
            .hasMessageContaining("公开来源引用不合法").hasMessageNotContaining("不应泄露的编造来源");
    }
}
