package cn.interview.validation;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InterviewDiagnosisValidationTest {
  final ObjectMapper json=new ObjectMapper();
  final InterviewOutputValidator validator=new InterviewOutputValidator(json);
  final String answer="我实现了请求幂等；尚未验证并发超时。";
  ObjectNode decision() {
    var out=json.createObjectNode().put("action","FOLLOW_UP").put("question","并发超时后怎样验证状态？");out.putArray("sourceIds");
    var d=out.putObject("diagnosis").put("category","INCOMPLETE").put("answerQuote","尚未验证并发超时")
        .put("gap","并发超时验证缺失").put("nextGoal","验证并发下的恢复边界").put("reason","原答明确说明尚未验证");
    d.putArray("coveredPoints").add("请求幂等实现");return out;
  }
  @Test void 当前连续短引可保留但不能接受其他原文() {
    assertThat(validator.followup(decision(),json.createArrayNode(),answer,true).path("diagnosis").path("category").asText()).isEqualTo("INCOMPLETE");
    for(String quote:List.of("","历史回答的原话","我实现了请求幂等【中段按输入预算省略；原文保留在本场记录】尚未验证")) {
      var raw=decision();raw.withObject("/diagnosis").put("answerQuote",quote);
      assertThatThrownBy(()->validator.followup(raw,json.createArrayNode(),answer,true)).isInstanceOf(ApiException.class);
    }
  }
  @Test void 未知分类和非数组考点被拒绝() {
    var raw=decision();raw.withObject("/diagnosis").put("category","已确认候选人造假");
    assertThatThrownBy(()->validator.followup(raw,json.createArrayNode(),answer,true)).isInstanceOf(ApiException.class);
    var invalid=decision();invalid.withObject("/diagnosis").put("coveredPoints","错误结构");
    assertThatThrownBy(()->validator.followup(invalid,json.createArrayNode(),answer,true)).isInstanceOf(ApiException.class);
  }
  @Test void 追问须声明新增考查目标() {
    var raw=decision();raw.withObject("/diagnosis").put("nextGoal","");
    assertThatThrownBy(()->validator.followup(raw,json.createArrayNode(),answer,true)).isInstanceOf(ApiException.class);
  }
  @Test void 新策略缺诊断失败但旧策略仍可读取() {
    var raw=decision();raw.remove("diagnosis");
    assertThatThrownBy(()->validator.followup(raw,json.createArrayNode(),answer,true)).isInstanceOf(ApiException.class);
    assertThat(validator.followup(raw,json.createArrayNode(),answer,false).path("action").asText()).isEqualTo("FOLLOW_UP");
  }
}
