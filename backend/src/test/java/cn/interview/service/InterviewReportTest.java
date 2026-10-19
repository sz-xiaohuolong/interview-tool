package cn.interview.service;

import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InterviewReportTest {
    final ObjectMapper json = new ObjectMapper();
    final InterviewOutputValidator validator = new InterviewOutputValidator(json);
    ObjectNode report() throws Exception { return (ObjectNode)json.readTree("""
        {"summary":"合成摘要","findings":[{"questionId":"q1","assessment":"需要核对版本","status":"SUPPORTED","sourceIds":[],"suggestion":"补充超时后的处理步骤","answerStatus":"PARTIAL","answerQuote":"数据库超时后返回错误","observation":"说明了超时行为，但没有解释重试边界","headline":"补充超时与重试边界","priority":1,"terms":[{"term":"Trade-off","explanation":"选型时在收益与代价之间做取舍"}]}]}
        """); }
    @Test void 原答充分程度与技术依据独立() throws Exception {
        var messages=json.readTree("""
            [{"role":"ASSISTANT","questionId":"q1","content":"如何处理超时"},{"role":"USER","questionId":"q1","content":"数据库超时后返回错误，使用请求编号排查。"}]
            """);
        var result=validator.evidenceReport(report(),messages,json.createArrayNode());
        var f=result.path("findings").get(0);
        assertThat(f.path("answerStatus").asText()).isEqualTo("PARTIAL");
        assertThat(f.path("status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(f.path("observation").asText()).contains("重试边界");
        assertThat(f.path("terms").get(0).path("explanation").asText()).contains("取舍");
    }
    @Test void 伪造或跨题原答引用不能成为诊断证据() throws Exception {
        var messages=json.readTree("""
            [{"role":"USER","questionId":"q1","content":"我不清楚"},{"role":"USER","questionId":"q2","content":"数据库超时后返回错误"}]
            """);
        assertThatThrownBy(()->validator.evidenceReport(report(),messages,json.createArrayNode())).hasMessageContaining("不合法");
    }
    @Test void 漏评与无观察不能被当作优势或短板() throws Exception {
        var raw=report();((ObjectNode)raw.path("findings").get(0)).remove(java.util.List.of("answerStatus","answerQuote","observation","headline","priority","terms"));
        var messages=json.readTree("""
            [{"role":"USER","questionId":"q1","content":"我不清楚"},{"role":"USER","questionId":"q2","content":"我还没验证"}]
            """);
        var result=validator.evidenceReport(raw,messages,json.createArrayNode());
        assertThat(result.path("findings")).hasSize(2);
        for(var f:result.path("findings"))assertThat(f.path("answerStatus").asText()).isEqualTo("UNVERIFIED");
    }
}
