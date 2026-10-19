package cn.interview.service;

import cn.interview.Application;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.llm.provider=mock","app.knowledge.network-enabled=false"})
class InterviewFlowTest {
    static final Path DATA;static {try{DATA=Files.createTempDirectory("v3-flow-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired cn.interview.mapper.InterviewSessionStore store;
    final ObjectMapper json=new ObjectMapper();final HttpClient http=HttpClient.newHttpClient();
    record Response(int code,JsonNode body){}
    Response send(String method,String path,JsonNode body)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(10));
        if(body!=null)b.header("Content-Type","application/json").header("Origin","http://127.0.0.1:"+port).method(method,HttpRequest.BodyPublishers.ofString(body.toString()));else b.GET();
        var r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());return new Response(r.statusCode(),json.readTree(r.body()));
    }
    ObjectNode request(){return json.createObjectNode().put("requestId",UUID.randomUUID().toString());}
    JsonNode create()throws Exception {var r=send("POST","/v3/sessions",request().put("role","Java 后端工程师").put("level","JUNIOR"));assertThat(r.code).isEqualTo(200);return r.body;}
    JsonNode waitFor(String id,String... states)throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();JsonNode s=null;
        while(System.nanoTime()<until){s=send("GET","/v3/sessions/"+id,null).body;for(String state:states)if(s.path("status").asText().equals(state))return s;Thread.sleep(25);}
        throw new AssertionError("等待状态失败："+s);
    }
    ObjectNode action(JsonNode s){return request().put("expectedRevision",s.path("revision").asInt());}
    @Test void 岗位职级必填且不把缺省资料伪装成个性化()throws Exception {
        assertThat(send("POST","/v3/sessions",request()).code).isEqualTo(400);
        var s=create();s=waitFor(s.path("id").asText(),"READY");
        assertThat(s.path("warnings").toString()).contains("通用");assertThat(s.has("questions")).isFalse();assertThat(s.has("input")).isFalse();
    }
    @Test void 简历快照准备纠偏确认与回答幂等完整闭环()throws Exception {
        var resume=send("POST","/materials",json.createObjectNode().put("kind","RESUME").put("name","合成接口开发简历").put("content","负责订单查询接口，使用Redis缓存；仅参与接口开发，不负责整体架构。")).body;
        var input=request().put("role","Java 后端工程师").put("level","JUNIOR").put("resumeId",resume.path("id").asText());
        var created=send("POST","/v3/sessions",input);assertThat(created.code).isEqualTo(200);
        assertThat(send("POST","/v3/sessions",input).body.path("id")).isEqualTo(created.body.path("id"));
        var s=waitFor(created.body.path("id").asText(),"READY");String id=s.path("id").asText();
        var corrected=send("POST","/v3/sessions/"+id+"/corrections",action(s).put("correction","Redis方案由同事设计，我负责接口接入"));assertThat(corrected.code).isEqualTo(200);
        s=waitFor(id,"READY");assertThat(s.path("summary").toString()).isNotBlank();
        var started=send("POST","/v3/sessions/"+id+"/confirm",action(s));assertThat(started.code).isEqualTo(200);s=started.body;
        assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");assertThat(s.path("messages").size()).isEqualTo(1);
        var answer=action(s).put("expectedQuestionId",s.path("messages").get(0).path("questionId").asText()).put("content","我负责读取缓存与回源查询，缓存失效后回数据库；没有参与整体架构设计。");
        assertThat(send("POST","/v3/sessions/"+id+"/answers",answer).code).isEqualTo(200);
        s=waitFor(id,"IN_PROGRESS","INTERRUPTED");assertThat(send("POST","/v3/sessions/"+id+"/answers",answer).code).isEqualTo(200);
        var changed=answer.deepCopy().put("content","另一个回答");assertThat(send("POST","/v3/sessions/"+id+"/answers",changed).code).isEqualTo(409);
        long count=0;for(var m:s.path("messages"))if(m.path("role").asText().equals("USER"))count++;assertThat(count).isEqualTo(1);
        assertThat(send("GET","/materials",null).body.toString()).doesNotContain("同事设计");
        assertThat(send("POST","/v3/sessions/"+id+"/finish",action(s)).code).isEqualTo(200);
        s=waitFor(id,"REPORT_READY");assertThat(s.path("report").path("findings").size()).isGreaterThan(0);
        for(var finding:s.path("report").path("findings"))assertThat(finding.path("status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(send("GET","/v3/sessions",null).body.toString()).contains(id);
    }
    @Test void 旧代次请求被拒绝且取消不会再自动调用()throws Exception {
        var s=create();s=waitFor(s.path("id").asText(),"READY");String id=s.path("id").asText();
        var outdated=action(s);var cancelled=send("POST","/v3/sessions/"+id+"/cancel",action(s));assertThat(cancelled.code).isEqualTo(200);
        assertThat(cancelled.body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(send("POST","/v3/sessions/"+id+"/confirm",outdated).code).isEqualTo(409);
    }
    @Test void 取消已选JD时不继续使用旧JD快照()throws Exception {
        var jd=send("POST","/materials",json.createObjectNode().put("kind","JD").put("name","合成岗位说明").put("content","Java接口开发，负责订单查询和缓存接入")).body;
        var created=send("POST","/v3/sessions",request().put("role","Java 后端工程师").put("level","JUNIOR").put("jdId",jd.path("id").asText())).body;
        var s=waitFor(created.path("id").asText(),"READY");String id=s.path("id").asText();
        assertThat(send("POST","/v3/sessions/"+id+"/corrections",action(s).put("jdId","")).code).isEqualTo(200);
        waitFor(id,"READY");assertThat(store.get(id).path("input").path("jd").asText()).isEmpty();
    }

}
