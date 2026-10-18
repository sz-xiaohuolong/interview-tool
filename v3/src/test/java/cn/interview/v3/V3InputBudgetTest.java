package cn.interview.v3;

import cn.interview.v3.service.V3InputBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class V3InputBudgetTest {
    @Test void 大输入明确节选且不修改原快照或问题标识(){
        var json=new ObjectMapper();var original=json.createObjectNode().put("resume","履历".repeat(15000)).put("jd","岗位".repeat(15000));
        var messages=original.putArray("messages");for(int i=0;i<24;i++)messages.addObject().put("questionId","question-"+i).put("content","原始回答".repeat(3000));
        var input=V3InputBudget.bound(original,json);
        assertThat(input.toString().length()).isLessThan(95000);assertThat(input.path("inputTruncated").asBoolean()).isTrue();
        assertThat(original.path("messages").get(0).path("content").asText()).hasSize(12000);
        assertThat(input.path("messages").get(23).path("questionId").asText()).isEqualTo("question-23");
    }
}
