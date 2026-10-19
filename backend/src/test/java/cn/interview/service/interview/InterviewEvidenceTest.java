package cn.interview.service.interview;

import cn.interview.validation.InterviewOutputValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InterviewEvidenceTest {
    final ObjectMapper json=new ObjectMapper();final InterviewOutputValidator validator=new InterviewOutputValidator(json);
    @Test void 阶段完成必须是布尔值且不能和继续追问并用()throws Exception{
        var raw=json.createObjectNode().put("action","NEXT").put("stageComplete","true");raw.putArray("sourceIds");
        assertThatThrownBy(()->validator.followup(raw,json.createArrayNode())).hasMessageContaining("依据不合法");
        raw.put("action","FOLLOW_UP").put("question","合成追问").put("stageComplete",true);
        assertThatThrownBy(()->validator.followup(raw,json.createArrayNode())).hasMessageContaining("依据不合法");
        raw.put("action","NEXT");
        assertThat(validator.followup(raw,json.createArrayNode()).path("stageComplete").asBoolean()).isTrue();
    }
    @Test void 不允许评估未回答问题或编造来源()throws Exception{
        var messages=json.readTree("[{\"role\":\"USER\",\"questionId\":\"q1\",\"content\":\"已提交回答\"}]");
        var raw=json.readTree("{\"summary\":\"合成总结\",\"findings\":[{\"questionId\":\"q2\",\"assessment\":\"判断\",\"suggestion\":\"建议\",\"status\":\"SUPPORTED\",\"sourceIds\":[]}]}");
        assertThatThrownBy(()->validator.report(raw,messages,json.createArrayNode())).hasMessageContaining("依据不合法");
        ((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("findings").get(0)).put("questionId","q1").putArray("sourceIds").add("fake");
        assertThatThrownBy(()->validator.report(raw,messages,json.createArrayNode())).hasMessageContaining("依据不合法");
    }
    @Test void 只有可逐字核对的非过期官方片段才能保留技术判断()throws Exception{
        var messages=json.readTree("[{\"role\":\"USER\",\"questionId\":\"q1\",\"content\":\"合成回答\"}]");
        var source=json.createObjectNode().put("id","official:v1").put("kind","OFFICIAL").put("stale",false).put("excerpt","Official documentation states that tool arguments must be validated before execution.");
        var sources=json.createArrayNode().add(source);
        var finding=json.createObjectNode().put("questionId","q1").put("assessment","应验证工具参数").put("suggestion","补充参数校验用例").put("status","SUPPORTED").put("sourceQuote","tool arguments must be validated");finding.putArray("sourceIds").add("official:v1");
        var report=json.createObjectNode().put("summary","合成复盘");report.putArray("findings").add(finding);
        assertThat(validator.report(report,messages,sources).path("findings").get(0).path("status").asText()).isEqualTo("SUPPORTED");
        source.put("stale",true);assertThat(validator.report(report,messages,sources).path("findings").get(0).path("status").asText()).isEqualTo("INSUFFICIENT");
        source.put("stale",false).put("kind","INTERVIEW");assertThat(validator.report(report,messages,sources).path("findings").get(0).path("status").asText()).isEqualTo("INSUFFICIENT");
        source.put("kind","OFFICIAL");finding.put("sourceQuote","模型编造的不存在的引用片段");assertThat(validator.report(report,messages,sources).path("findings").get(0).path("status").asText()).isEqualTo("INSUFFICIENT");
    }
    @Test void 缺少逐题发现时补足依据不足()throws Exception{
        var messages=json.readTree("[{\"role\":\"USER\",\"questionId\":\"q1\"}]");
        var report=json.createObjectNode().put("summary","合成复盘");report.putArray("findings");
        var out=validator.report(report,messages,json.createArrayNode());assertThat(out.path("findings").size()).isEqualTo(1);assertThat(out.path("findings").get(0).path("status").asText()).isEqualTo("INSUFFICIENT");
    }
    @Test void 不能用仅匹配JD冒充已经考查简历职责()throws Exception{
        var raw=json.readTree("{\"questions\":[{\"question\":\"如何做检索评估？\",\"topic\":\"AI_EVALUATION\",\"evidence\":\"负责检索评估\",\"sourceIds\":[],\"backups\":[]}]}");
        assertThatThrownBy(()->validator.questions(raw,json.createArrayNode(),"本人负责Java交易接口与幂等补偿","岗位要求：负责检索评估",1)).hasMessageContaining("依据不合法");
    }

}
