package cn.interview.service.interview;

import cn.interview.Application;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.mapper.TrainingStore;
import cn.interview.testsupport.SyntheticTrainingData;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=Application.class,properties={"app.llm.provider=mock","app.training.enabled=true","app.knowledge.network-enabled=false"})
@AutoConfigureMockMvc
class InterviewReviewGapTest {
    static final Path DATA=SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean cn.interview.client.training.TrainingModelClient llm;
    @Autowired MockMvc mvc; @Autowired InterviewSessionStore store; @Autowired TrainingStore db; @Autowired ObjectMapper json;
    ObjectNode source() throws Exception {
        var s=(ObjectNode)json.readTree("""
        {"status":"REPORT_READY","experienceVersion":1,"reviewVersion":2,"role":"Java 后端","level":"JUNIOR","mock":true,
        "messages":[{"id":"m1","role":"ASSISTANT","questionId":"q1","content":"超时怎么办？"},{"id":"m2","role":"USER","questionId":"q1","content":"数据库超时后返回错误，使用请求编号排查。"}],
        "mainProject":{"hypothetical":true,"brief":""},"sources":[],"report":{"version":2,"summary":"合成复盘","findings":[{"questionId":"q1","answerStatus":"PARTIAL","observation":"缺少重试边界","suggestion":"说明重试触发条件","status":"INSUFFICIENT","sourceIds":[]}]}}
        """);s.put("id",UUID.randomUUID().toString());store.save(s);return s;
    }
    JsonNode postJson(String path,Map<String,Object> body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());}
    @Test void 无项目也能新题补练并幂等关联结果且原报告不变()throws Exception{
        var s=source();String id=s.path("id").asText(),before=store.get(id).toString();String path="/api/review-gaps/V3/"+id;
        var first=postJson(path+"/q1",Map.of("requestId","one"));var second=postJson(path+"/q1",Map.of("requestId","two"));
        assertThat(first.path("caseId").asText()).isNotBlank().isEqualTo(second.path("caseId").asText());
        var c=db.get("v2_practice_cases",first.path("caseId").asText());
        assertThat(c.path("contextOrigin").asText()).isEqualTo("REVIEW_GAP");
        assertThat(c.path("question").asText()).contains(c.path("answerQuote").asText()).isNotEqualTo("超时怎么办？");
        assertThat(c.path("context").asText()).contains("Java 后端","缺少重试边界","数据库超时后返回错误");
        var a=postJson("/api/v2/practice-cases/"+c.path("id").asText()+"/attempts",Map.of("requestId","attempt"));
        a=postJson("/api/v2/practice-attempts/"+a.path("id").asText()+"/submit",Map.of("requestId","submit","expectedVersion",a.path("revision").asInt(),"answer","超时后先判断写入是否完成，只对可安全重试的操作采用有界重试。"));
        assertThat(a.path("status").asText()).isEqualTo("READY");
        var list=json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.path("items").get(0).path("latestAttempt").path("id").asText()).isEqualTo(a.path("id").asText());
        assertThat(store.get(id).toString()).isEqualTo(before);
        assertThat(db.ids("v2_practice_cases","source_id",id)).hasSize(1);
    }
    @Test void 历史未答与非缺口均拒绝生成()throws Exception{
        var s=source();String path="/api/review-gaps/V3/"+s.path("id").asText();
        mvc.perform(post(path+"/unanswered").contentType("application/json").content("{\"requestId\":\"bad\"}")).andExpect(status().is4xxClientError());
        ((ObjectNode)s.path("report").path("findings").get(0)).put("answerStatus","CLEAR");store.save(s);
        mvc.perform(post(path+"/q1").contentType("application/json").content("{\"requestId\":\"bad\"}")).andExpect(status().is4xxClientError());
        s.remove("reviewVersion");((ObjectNode)s.path("report")).remove("version");store.save(s);
        mvc.perform(get(path)).andExpect(status().is4xxClientError());
        assertThat(db.ids("v2_practice_cases","source_id",s.path("id").asText())).isEmpty();
    }
    @Test void 失败保留操作并禁止作答且显式重试复用原案例()throws Exception{
        var s=source();String sid=s.path("id").asText(),before=store.get(sid).toString(),path="/api/review-gaps/V3/"+sid;
        org.mockito.Mockito.doThrow(cn.interview.client.training.TrainingModelClient.failed("MODEL_TIMEOUT","合成超时"))
            .when(llm).call(org.mockito.ArgumentMatchers.eq(cn.interview.client.training.ModelTask.REVIEW_GAP_QUESTION),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var failed=json.readTree(mvc.perform(post(path+"/q1").contentType("application/json").content("{\"requestId\":\"fail\"}"))
            .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString());
        String opid=failed.path("operationId").asText();assertThat(opid).isNotBlank();
        var row=postJson(path+"/q1",Map.of("requestId","duplicate"));assertThat(row.path("status").asText()).isEqualTo("FAILED");
        String cid=row.path("caseId").asText();
        mvc.perform(post("/api/v2/practice-cases/"+cid+"/attempts").contentType("application/json").content("{\"requestId\":\"too-early\"}"))
            .andExpect(status().isConflict());
        org.mockito.Mockito.verify(llm,org.mockito.Mockito.times(1)).call(org.mockito.ArgumentMatchers.eq(cn.interview.client.training.ModelTask.REVIEW_GAP_QUESTION),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doCallRealMethod().when(llm).call(org.mockito.ArgumentMatchers.eq(cn.interview.client.training.ModelTask.REVIEW_GAP_QUESTION),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        var op=db.get("v2_operations",opid);
        postJson("/api/v2/operations/"+opid+"/retry",Map.of("requestId","explicit-retry","expectedGeneration",op.path("generation").asInt()));
        assertThat(db.get("v2_practice_cases",cid).path("status").asText()).isEqualTo("READY");
        assertThat(db.ids("v2_practice_cases","source_id",sid)).containsExactly(cid);assertThat(store.get(sid).toString()).isEqualTo(before);
    }

}
