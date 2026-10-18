package cn.interview.v3.model;

import cn.interview.Application;
import cn.interview.v3.service.V3InterviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用生产七秒预算验证实际截止，不缩短调度时间，也不调用远程模型。 */
@SpringBootTest(classes=Application.class,properties={"app.llm.provider=mock","app.v3.enabled=true","app.v3.knowledge.network-enabled=false"})
@Import(V3ProductionDeadlineTest.Configuration.class)
class V3ProductionDeadlineTest {
    static final Path DATA;static{try{DATA=Files.createTempDirectory("v3-deadline-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired V3InterviewService service;@Autowired ObjectMapper json;@Autowired BlockingModel gateway;
    ObjectNode action(ObjectNode s){return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",s.path("revision").asInt());}
    @Test void 生产预算在八秒前可见相关备用且立即返回思考状态()throws Exception{
        var s=service.create(json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("role","Java 后端工程师").put("level","JUNIOR"));String id=s.path("id").asText();
        long readyUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!s.path("status").asText().equals("READY")&&System.nanoTime()<readyUntil){Thread.sleep(10);s=service.get(id);}assertThat(s.path("status").asText()).isEqualTo("READY");
        s=service.action(id,"confirm",action(s));String question=s.path("messages").get(0).path("questionId").asText();long start=System.nanoTime();
        try{
            s=service.action(id,"answers",action(s).put("expectedQuestionId",question).put("content","合成回答：我负责查询接口接入，先检查缓存，未命中时回源。"));
            long ackMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertThat(ackMs).isLessThan(500);assertThat(s.path("status").asText()).isEqualTo("ANSWERING");
            long until=start+TimeUnit.SECONDS.toNanos(8);while(s.path("status").asText().equals("ANSWERING")&&System.nanoTime()<until){Thread.sleep(25);s=service.get(id);}
            long visibleMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);assertThat(s.path("metrics").path("lastFallback").asBoolean()).isTrue();assertThat(visibleMs).isBetween(6900L,7999L);
            assertThat(s.path("messages").get(s.path("messages").size()-1).path("fallback").asBoolean()).isTrue();
            System.out.println("生产截止实测：思考响应毫秒="+ackMs+"，备用问题可读取毫秒="+visibleMs);
        }finally{gateway.release.countDown();}
    }
    @TestConfiguration static class Configuration{@Bean @Primary BlockingModel blockingModel(ObjectMapper json){return new BlockingModel(json);}}
    static class BlockingModel implements V3ModelGateway{
        final V3MockModel delegate;final CountDownLatch release=new CountDownLatch(1);BlockingModel(ObjectMapper json){delegate=new V3MockModel(json);}
        public ObjectNode call(String stage,ObjectNode input,int timeout){if(stage.equals("FOLLOWUP"))try{release.await(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return delegate.call(stage,input);}
    }
}
