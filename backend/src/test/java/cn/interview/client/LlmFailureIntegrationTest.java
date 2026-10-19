package cn.interview.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.llm.provider=openai","app.llm.api-key=test-only-key","app.llm.model=test-model","app.llm.timeout-seconds=1","app.llm.reasoning-effort=high"})
@AutoConfigureMockMvc
class LlmFailureIntegrationTest {
    static final AtomicReference<String> BODY=new AtomicReference<>();
    static final AtomicReference<String> REQUEST=new AtomicReference<>();
    static volatile int code=200;
    static volatile boolean delayed=false;
    static HttpServer server;
    static String data;
    static {
        try {
            data=Files.createTempDirectory("llm-test-").toString();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/v1/chat/completions",exchange->{
                REQUEST.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                if(delayed) { try { Thread.sleep(1500); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); } }
                byte[] response=BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(code,response.length);exchange.getResponseBody().write(response);exchange.close();
            });server.start();
        } catch(Exception e) {throw new RuntimeException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("app.data-dir",()->data);r.add("app.llm.base-url",()->"http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired FocusedInterviewModelClient llm;
    @BeforeEach void reset(){code=200;delayed=false;}
    void output(String content) throws Exception { BODY.set(json.writeValueAsString(Map.of("choices",new Object[]{Map.of("message",Map.of("content",content))}))); }
    void plan() throws Exception { output("{\"questions\":[{\"question\":\"请说明事务的隔离级别？\",\"topic\":\"事务\",\"level\":\"L1\",\"evidence\":\"通用岗位知识\"},{\"question\":\"你会如何处理并发更新？\",\"topic\":\"并发\",\"level\":\"L2\",\"evidence\":\"通用岗位知识\"},{\"question\":\"假设流量增加十倍，你如何排查瓶颈？\",\"topic\":\"性能\",\"level\":\"L3\",\"evidence\":\"假设场景\"}]}"); }
    JsonNode postJson(String path,Object body) throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    JsonNode create() throws Exception{return postJson("/api/sessions",Map.of("mode","QUICK","durationMinutes",5));}
    @Test void configuredReasoningEffortReachesEveryModelTask() throws Exception {
        output("{}");
        for(String task:new String[]{"planner","interviewer","predictor","evaluator"}) {
            llm.call(task,json.createObjectNode());
            assertThat(json.readTree(REQUEST.get()).path("reasoning_effort").asText()).isEqualTo("high");
        }
    }
    @Test void blankReasoningEffortPreservesProviderDefault() throws Exception {
        output("{}");
        FocusedInterviewModelClient defaultClient=new FocusedInterviewModelClient(json,"openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-only-key","test-model",1,"");
        defaultClient.call("planner",json.createObjectNode());
        assertThat(json.readTree(REQUEST.get()).has("reasoning_effort")).isFalse();
    }
    @Test void malformedJsonCreatesRecoverableFailureWithoutLeakingUpstream() throws Exception {
        output("not json test-only-key"); JsonNode s=create();
        assertThat(s.path("status").asText()).isEqualTo("FAILED");assertThat(s.path("pendingAction").asText()).isEqualTo("PLAN");
        assertThat(s.toString()).doesNotContain("test-only-key");
        plan(); s=postJson("/api/sessions/"+s.path("id").asText()+"/retry",Map.of());assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");
    }
    @Test void semanticInvalidPlanDoesNotStartInterview() throws Exception {
        output("{\"questions\":[{\"question\":42,\"topic\":\"事务\"}]}");
        assertThat(create().path("status").asText()).isEqualTo("FAILED");
    }
    @Test void failedAnswerIsSavedOnceAndRetryUsesSameQuestion() throws Exception {
        plan();JsonNode s=create();String id=s.path("id").asText();String q=s.path("messages").get(0).path("id").asText();
        code=429;BODY.set("secret upstream diagnostic test-only-key");
        s=postJson("/api/sessions/"+id+"/answers",Map.of("content","通过隔离级别控制并发行为","requestId","retry-one","expectedMessageId",q));
        assertThat(s.path("status").asText()).isEqualTo("INTERRUPTED");assertThat(s.path("messages").size()).isEqualTo(2);
        assertThat(s.path("error").asText()).contains("限流");assertThat(s.toString()).doesNotContain("test-only-key");
        code=200;output("{\"action\":\"FOLLOW_UP\",\"question\":\"你提到隔离级别，如何验证幻读是否出现？\",\"topic\":\"事务\",\"reason\":\"内部决策不可见\"}");
        s=postJson("/api/sessions/"+id+"/retry",Map.of());assertThat(s.path("messages").size()).isEqualTo(3);assertThat(s.toString()).doesNotContain("内部决策不可见");
        s=postJson("/api/sessions/"+id+"/answers",Map.of("content","通过隔离级别控制并发行为","requestId","retry-one","expectedMessageId",q));
        assertThat(s.path("messages").size()).isEqualTo(3);
    }
    @Test void timeoutIsExplicitAndDoesNotFakeQuestions() throws Exception {
        plan();delayed=true;JsonNode s=create();assertThat(s.path("status").asText()).isEqualTo("FAILED");assertThat(s.path("error").asText()).contains("超时");assertThat(s.path("messages").size()).isZero();
    }
    @Test void invalidReportScoreIsRejectedAndCanRetry() throws Exception {
        plan();JsonNode s=create();String id=s.path("id").asText();
        output("{\"action\":\"FOLLOW_UP\",\"question\":\"你提到事务，怎样确认回滚？\",\"topic\":\"事务\",\"reason\":\"检查细节\"}");
        s=postJson("/api/sessions/"+id+"/answers",Map.of("content","失败时回滚","requestId","x","expectedMessageId",s.path("messages").get(0).path("id").asText()));
        output("{\"summary\":\"评分无效\",\"dimensions\":[{\"name\":\"技术准确性\",\"score\":7,\"reason\":\"超出范围\"}],\"questions\":[],\"weaknesses\":[],\"suggestions\":[]}");
        s=postJson("/api/sessions/"+id+"/finish",Map.of());assertThat(s.path("reportStatus").asText()).isEqualTo("FAILED");
        mvc.perform(get("/api/sessions/"+id+"/report")).andExpect(status().isConflict());
    }
    @Test void unknownActionSavesAnswerAndRejectsModelControl() throws Exception {
        plan();JsonNode s=create();output("{\"action\":\"DELETE_HISTORY\",\"question\":\"任意问题？\",\"topic\":\"事务\",\"reason\":\"不合法动作\"}");
        s=postJson("/api/sessions/"+s.path("id").asText()+"/answers",Map.of("content","通过事务回滚","requestId","bad-action","expectedMessageId",s.path("messages").get(0).path("id").asText()));
        assertThat(s.path("status").asText()).isEqualTo("INTERRUPTED");assertThat(s.path("messages").size()).isEqualTo(2);assertThat(s.path("error").asText()).contains("动作");
    }
    @Test void fabricatedReportEvidenceIsRejectedThenValidReportCanBeRetried() throws Exception {
        plan();JsonNode s=create();String id=s.path("id").asText();
        output("{\"action\":\"FOLLOW_UP\",\"question\":\"怎样确认回滚？\",\"topic\":\"事务\",\"reason\":\"检查细节\"}");
        postJson("/api/sessions/"+id+"/answers",Map.of("content","失败时回滚","requestId","evidence","expectedMessageId",s.path("messages").get(0).path("id").asText()));
        var r=json.createObjectNode().put("summary","回答提到了回滚，还缺少验证细节。");
        for(String name:new String[]{"技术准确性","理解深度","表达清晰度","追问应对能力"})r.withArray("dimensions").addObject().put("name",name).put("score",2).put("reason","已提到回滚，但没有具体实现细节；追问未回答。");
        var q=r.withArray("questions").addObject().put("questionIndex",0).put("question","请说明事务的隔离级别？").put("answerOutline","说明失败如何触发回滚与验证。").put("knowledgeExplanation","事务可以保证原子性。");
        q.putArray("correct").add("提到了失败回滚");q.putArray("incorrect");q.putArray("missing").add("隔离级别");q.putArray("uncertain").add("实际实现未知");q.putArray("evidence").add("用户声称吞吐量提高了十倍");r.putArray("weaknesses").add("事务隔离级别");r.putArray("suggestions").add("用具体例子说明不同隔离级别");output(r.toString());
        s=postJson("/api/sessions/"+id+"/finish",Map.of());assertThat(s.path("reportStatus").asText()).isEqualTo("FAILED");assertThat(s.path("error").asText()).contains("依据");
        q.putArray("evidence").add("失败时回滚");output(r.toString());JsonNode report=postJson("/api/sessions/"+id+"/report",Map.of());assertThat(report.path("questions").get(0).path("evidence").get(0).asText()).isEqualTo("失败时回滚");
    }

    @Test void trailingModelContentCannotPassAsValidJson() throws Exception {
        plan();String envelope=BODY.get();String valid=json.readTree(envelope).path("choices").get(0).path("message").path("content").asText();output(valid+" 非法尾部内容");
        assertThat(create().path("status").asText()).isEqualTo("FAILED");
    }

    @Test void whitespaceEndActionOnLastQuestionCompletesWithoutPendingDeadEnd() throws Exception {
        plan();JsonNode s=create();String id=s.path("id").asText();
        for(int index=0;index<3;index++) {
            output(json.writeValueAsString(index<2?Map.of("action","NEXT","question","请继续说明实现中的权衡？","topic","事务","reason","切换问题"):Map.of("action"," END ","reason","完成面试")));
            s=postJson("/api/sessions/"+id+"/answers",Map.of("content","我会在失败时回滚并验证状态","requestId","last-space-"+index,"expectedMessageId",s.path("messages").get(s.path("messages").size()-1).path("id").asText()));
        }
        assertThat(s.path("status").asText()).isEqualTo("COMPLETED");assertThat(s.path("pendingAction").asText()).isEmpty();assertThat(s.path("messages").size()).isEqualTo(6);
    }

}
