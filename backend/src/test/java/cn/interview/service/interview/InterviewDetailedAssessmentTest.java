package cn.interview.service.interview;

import cn.interview.validation.InterviewOutputValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InterviewDetailedAssessmentTest {
  final ObjectMapper json = new ObjectMapper();
  final InterviewOutputValidator validator = new InterviewOutputValidator(json);
  final String answer = "数据库超时后返回错误，使用请求编号排查。";
  ObjectNode raw() throws Exception {
    var raw = (ObjectNode) json.readTree("""
      {"summary":"本场讨论了异常处理。","findings":[{"questionId":"q1","assessment":"重试边界待核实","status":"INSUFFICIENT","sourceIds":[],"suggestion":"补充重试验证","answerStatus":"PARTIAL","answerQuote":"数据库超时后返回错误","observation":"尚未说明重试边界","headline":"补充重试边界","priority":1,"terms":[]}],
       "detailedAssessment":{"version":1,"overall":{"text":"已描述异常现象，但恢复流程仍需补充。","questionIds":["q1"]},"strengths":[],"improvements":[{"text":"超时处理尚未说明哪些失败可重试，难以核对是否可能重复执行。","questionIds":["q1"]}],"nextSteps":[{"text":"绘制超时恢复状态图，标注重试条件；用重复请求测试确认只写入一次。","questionIds":["q1"]}]}}
      """);
    return raw;
  }
  com.fasterxml.jackson.databind.JsonNode messages() {
    var messages=json.createArrayNode(); messages.addObject().put("role","USER").put("questionId","q1").put("content",answer); return messages;
  }
  @Test void 四部分评估保留真实题号且没有充分优势时不强凑() throws Exception {
    var result = validator.evidenceReport(raw(), messages(), json.createArrayNode());
    var detail = result.path("detailedAssessment");
    assertThat(detail.path("version").asInt()).isEqualTo(1);
    assertThat(detail.path("overall").path("text").asText()).contains("恢复流程");
    assertThat(detail.path("strengths")).isEmpty();
    assertThat(detail.path("improvements").get(0).path("questionIds").get(0).asText()).isEqualTo("q1");
    assertThat(detail.path("nextSteps").get(0).path("text").asText()).contains("只写入一次");
  }
  @Test void 不能引用未作答题号() throws Exception {
    var raw=raw(); ((ObjectNode)raw.path("detailedAssessment").path("improvements").get(0)).putArray("questionIds").add("unanswered");
    assertThatThrownBy(() -> validator.evidenceReport(raw,messages(),json.createArrayNode())).hasMessageContaining("不合法");
  }
  @Test void 不能把尚未验证的题目编成短板() throws Exception {
    var raw=raw(); ((ObjectNode)raw.path("findings").get(0)).put("answerStatus","UNVERIFIED");
    assertThatThrownBy(() -> validator.evidenceReport(raw,messages(),json.createArrayNode())).hasMessageContaining("不合法");
  }
  @Test void 不能把待补强题目包装为优势() throws Exception {
    var raw=raw(); ((com.fasterxml.jackson.databind.node.ArrayNode)raw.path("detailedAssessment").path("strengths")).add(raw.path("detailedAssessment").path("improvements").get(0));
    assertThatThrownBy(() -> validator.evidenceReport(raw,messages(),json.createArrayNode())).hasMessageContaining("不合法");
  }
  @Test void 旧报告不补写详细评估也不重新生成() throws Exception {
    var raw=raw(); raw.remove("detailedAssessment");
    assertThat(validator.evidenceReport(raw,messages(),json.createArrayNode()).has("detailedAssessment")).isFalse();
  }
  @Test void 缺失段落或重复题号拒绝() throws Exception {
    var raw=raw(); ((ObjectNode)raw.path("detailedAssessment")).remove("nextSteps");
    assertThatThrownBy(() -> validator.evidenceReport(raw,messages(),json.createArrayNode())).hasMessageContaining("不合法");
    var duplicate=raw(); ((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("detailedAssessment").path("overall").path("questionIds")).add("q1");
    assertThatThrownBy(() -> validator.evidenceReport(duplicate,messages(),json.createArrayNode())).hasMessageContaining("不合法");
  }
  @Test void 行动必须源自本场改进项且段落数量受限() throws Exception {
    var raw=raw(); ((com.fasterxml.jackson.databind.node.ArrayNode)raw.path("detailedAssessment").path("improvements")).removeAll();
    assertThatThrownBy(() -> validator.evidenceReport(raw,messages(),json.createArrayNode())).hasMessageContaining("不合法");
    var tooMany=raw();var list=(com.fasterxml.jackson.databind.node.ArrayNode)tooMany.path("detailedAssessment").path("nextSteps");
    var item=list.get(0).deepCopy();for(int i=0;i<3;i++)list.add(item);
    assertThatThrownBy(() -> validator.evidenceReport(tooMany,messages(),json.createArrayNode())).hasMessageContaining("不合法");
  }
  @Test void 伪造短引不能成为综合段落的依据() throws Exception {
    var raw=raw(); ((ObjectNode)raw.path("findings").get(0)).put("answerQuote","我已完成分布式事务优化");
    assertThatThrownBy(() -> validator.evidenceReport(raw,messages(),json.createArrayNode())).hasMessageContaining("不合法");
  }

}
