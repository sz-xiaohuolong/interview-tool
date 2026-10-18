package cn.interview.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewGapOutputValidatorTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void 伪造原答与复述原题均拒绝(){
        var input=json.createObjectNode().put("originalAnswer","数据库超时后返回错误，使用请求编号排查。");
        input.putArray("priorQuestions").add("数据库超时后返回错误，这时如何重试？");
        var valid=json.createObjectNode().put("answerQuote","数据库超时后返回错误").put("question","你提到数据库超时后返回错误，哪些操作可安全重试？");
        assertThat(ReviewGapOutputValidator.validate(valid,input).path("question").asText()).contains("安全重试");
        assertThatThrownBy(()->ReviewGapOutputValidator.validate(valid.deepCopy().put("answerQuote","我主导百万并发架构设计"),input)).hasMessageContaining("原答锚点");
        assertThatThrownBy(()->ReviewGapOutputValidator.validate(valid.deepCopy().put("question","数据库超时后返回错误，这时如何重试？"),input)).hasMessageContaining("原题");
    }
}
