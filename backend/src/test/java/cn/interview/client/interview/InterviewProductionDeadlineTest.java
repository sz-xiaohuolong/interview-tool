package cn.interview.client.interview;

import cn.interview.Application;
import cn.interview.client.mock.MockInterviewModel;
import cn.interview.service.interview.InterviewService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用生产默认预算验证慢响应与实际截止，不调用远程模型。 */
@SpringBootTest(classes=Application.class,properties={"app.llm.provider=mock","app.knowledge.network-enabled=false"})
@Import(InterviewProductionDeadlineTest.Configuration.class)
class InterviewProductionDeadlineTest {
    static final Path DATA;static{try{DATA=Files.createTempDirectory("v3-deadline-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired InterviewService service;@Autowired ObjectMapper json;@Autowired BlockingModel gateway;
    @BeforeEach void reset(){gateway.release=new CountDownLatch(1);gateway.called=new CountDownLatch(1);gateway.timeout=0;}
    ObjectNode action(ObjectNode s){return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",s.path("revision").asInt());}
    @Test void 超过原七秒仍接收有效追问且没有切换备用()throws Exception{
        var s=service.create(json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("sessionType","QUICK").put("role","Java 后端工程师").put("plannedQuestionCount",3));String id=s.path("id").asText();
        long readyUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!s.path("status").asText().equals("IN_PROGRESS")&&System.nanoTime()<readyUntil){Thread.sleep(10);s=service.get(id);}assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");
        try{
            s=service.action(id,"answers",action(s).put("expectedQuestionId",s.path("messages").get(0).path("questionId").asText()).put("content","我负责查询接口接入，先检查缓存，未命中时回源。"));
            assertThat(gateway.called.await(2,TimeUnit.SECONDS)).isTrue();assertThat(gateway.timeout).isEqualTo(30);
            Thread.sleep(8100);assertThat(service.get(id).path("status").asText()).isEqualTo("ANSWERING");
            gateway.release.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            do{Thread.sleep(10);s=service.get(id);}while(s.path("status").asText().equals("ANSWERING")&&System.nanoTime()<until);
            assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");assertThat(s.path("metrics").path("lastFallback").asBoolean()).isFalse();
            assertThat(s.path("messages").get(s.path("messages").size()-1).path("fallback").asBoolean()).isFalse();
        }finally{gateway.release.countDown();}
    }
    @Test void 生产预算三十一秒截止且立即返回思考状态()throws Exception{
        var s=service.create(json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("role","Java 后端工程师").put("level","JUNIOR"));String id=s.path("id").asText();
        long readyUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!s.path("status").asText().equals("READY")&&System.nanoTime()<readyUntil){Thread.sleep(10);s=service.get(id);}assertThat(s.path("status").asText()).isEqualTo("READY");
        s=service.action(id,"confirm",action(s));String question=s.path("messages").get(0).path("questionId").asText();long start=System.nanoTime();
        try{
            s=service.action(id,"answers",action(s).put("expectedQuestionId",question).put("content","合成回答：我负责查询接口接入，先检查缓存，未命中时回源。"));
            long ackMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertThat(ackMs).isLessThan(500);assertThat(s.path("status").asText()).isEqualTo("ANSWERING");
            assertThat(gateway.called.await(2,TimeUnit.SECONDS)).isTrue();assertThat(gateway.timeout).isEqualTo(30);
            long until=start+TimeUnit.SECONDS.toNanos(33);while(s.path("status").asText().equals("ANSWERING")&&System.nanoTime()<until){Thread.sleep(25);s=service.get(id);}
            long visibleMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertThat(s.path("metrics").path("lastFallback").asBoolean()).isTrue();assertThat(visibleMs).isBetween(30900L,32999L);
            assertThat(s.path("messages").get(s.path("messages").size()-1).path("fallback").asBoolean()).isTrue();
            System.out.println("生产截止实测：思考响应毫秒="+ackMs+"，备用问题可读取毫秒="+visibleMs);
        }finally{gateway.release.countDown();}
    }
    @TestConfiguration static class Configuration{@Bean @Primary BlockingModel blockingModel(ObjectMapper json){return new BlockingModel(json);}}
    static class BlockingModel implements InterviewModelGateway{
        final MockInterviewModel delegate;volatile CountDownLatch release=new CountDownLatch(1),called=new CountDownLatch(1);volatile int timeout;BlockingModel(ObjectMapper json){delegate=new MockInterviewModel(json);}
        public ObjectNode call(String stage,ObjectNode input,int timeout){if(stage.equals("FOLLOWUP")){this.timeout=timeout;called.countDown();try{release.await(45,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return delegate.call(stage,input);}
    }
}
