package cn.interview.service.interview;

import cn.interview.exception.ApiException;
import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentQuestionValidationTest {
  @Test void 不能用不同考点身份重复提出完全相同的主问题() {
    var json = new ObjectMapper();
    var input = json.createObjectNode().put("sessionType", "QUICK");
    var points = input.putObject("agentPractice").putArray("points");
    var raw = json.createObjectNode(); var questions = raw.putArray("questions");
    for (String id : new String[] {"memory-isolation", "memory-correction"}) {
      points.addObject().put("id", id).put("theme", "AGENT_CONTEXT_MEMORY").put("title", "合成独立考点");
      questions.addObject().put("pointId", id).put("topic", "AGENT_CONTEXT_MEMORY")
          .put("question", "假设对话记忆出现错误，你如何定位？");
    }
    assertThatThrownBy(() -> new InterviewOutputValidator(json).agentQuestions(raw, questions.deepCopy(), input))
        .isInstanceOf(ApiException.class).hasMessageContaining("重复");
  }
}
