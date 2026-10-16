package cn.interview.service;

import cn.interview.Application;
import cn.interview.common.Models;
import cn.interview.exception.V2Exception;
import cn.interview.mapper.StoreTestAccess;
import cn.interview.mapper.V2Store;
import cn.interview.testsupport.V2TestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.*;

class V2ConcurrentRecoveryTest {
    ConfigurableApplicationContext start(java.nio.file.Path data, String url) {
        return new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE).run(
            "--app.data-dir="+data,"--app.v2.enabled=true","--app.llm.provider=openai",
            "--app.llm.base-url="+url,"--app.llm.api-key=synthetic-key","--app.llm.model=synthetic-model",
            "--app.llm.reasoning-effort=high","--app.llm.timeout-seconds=20",
            "--spring.main.banner-mode=off","--logging.level.root=ERROR");
    }

    @ParameterizedTest @CsvSource({"false,200","false,429","true,200","true,429"})
    void delayedHttpResultCannotReplaceRunningRetryAfterContextRestart(boolean practiceCase, int oldStatus) throws Exception {
        var firstArrived=new CountDownLatch(1);var releaseFirst=new CountDownLatch(1);var secondArrived=new CountDownLatch(1);var releaseSecond=new CountDownLatch(1);var calls=new AtomicInteger();
        var json=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            server.createContext("/v1/chat/completions",exchange->{
                try(exchange) {
                    exchange.getRequestBody().readAllBytes();int ordinal=calls.incrementAndGet();
                    var arrived=ordinal==1?firstArrived:secondArrived;var release=ordinal==1?releaseFirst:releaseSecond;
                    arrived.countDown();if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("合成响应未被释放");
                    var feedback=json.createObjectNode();String marker=ordinal==1?"第一代旧反馈":"第二代有效反馈";
                    if(practiceCase) {
                        var checks=feedback.putArray("checks");
                        for(String check:new String[]{"SCOPE","REASONING","COVERAGE","EXPRESSION"})checks.addObject().put("checkId",check).put("status","UNCERTAIN").put("explanation",marker).putArray("evidenceRefs");
                        for(String field:new String[]{"correct","incorrect","missing","uncertain","suggestions"})feedback.putArray(field);
                    } else {
                        for(String field:new String[]{"strengths","gaps","uncertain","tradeoffs","openIssues","evidenceRefs"})feedback.putArray(field);
                        feedback.withArray("uncertain").add(marker);
                    }
                    byte[] body=json.writeValueAsBytes(Map.of("model","synthetic-model","choices",new Object[]{Map.of("finish_reason","stop","message",Map.of("content",feedback.toString()))}));
                    exchange.sendResponseHeaders(ordinal==1?oldStatus:200,body.length);exchange.getResponseBody().write(body);
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            });
            server.start();String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            var data=V2TestData.migrated();var firstApp=start(data,url);
            try {
                var db=firstApp.getBean(V2Store.class);var design=firstApp.getBean(DesignPracticeService.class);var practice=firstApp.getBean(PracticeService.class);
                ObjectNode session,request;String table;
                if(practiceCase) {
                    var c=db.object().put("familyId",Models.id()).put("caseVersion",1).put("sourceKind","SESSION").put("sourceId","synthetic").put("question","合成问题：解释事务边界").put("context","").put("mock",false).put("analysisExposure","UNKNOWN");c.set("rubric",practice.rubric());c.putArray("priorDialogue");db.atomic(()->db.insert("v2_practice_cases",c));
                    session=practice.attempt(c.path("id").asText(),db.object().put("requestId",Models.id()).put("exposureDeclaration","UNKNOWN"));
                    request=db.object().put("requestId",Models.id()).put("expectedVersion",0).put("answer","不可覆盖的合成独立回答");table="v2_practice_attempts";
                } else {
                    session=design.create(db.object().put("requestId",Models.id()).put("scenarioId","ORDER_SERVICE"));
                    request=db.object().put("requestId",Models.id()).put("expectedVersion",0).put("stageId","CLARIFY");var fields=request.putObject("artifact");
                    session.path("stages").get(0).path("fieldDefinitions").forEach(f->fields.put(f.path("id").asText(),"仅用于恢复测试的合成产物"));table="v2_design_sessions";
                }
                String sid=session.path("id").asText();
                java.util.function.Function<ObjectNode,ObjectNode> submit=body->practiceCase?practice.submit(sid,body):design.submit(sid,body);
                Future<ObjectNode> oldRequest=workers.submit(()->submit.apply(request));
                assertThat(firstArrived.await(5,TimeUnit.SECONDS)).isTrue();
                var pending=db.get(table,sid);String gid=practiceCase?pending.path("primaryGroupId").asText():null;
                String operation=(practiceCase?db.get("v2_practice_evaluation_groups",gid):pending).path("operationId").asText();
                assertThatThrownBy(()->submit.apply(request)).isInstanceOfSatisfying(V2Exception.class,e->assertThat(e.code).isEqualTo("V2_RESOURCE_BUSY"));
                var competing=request.deepCopy().put("requestId",Models.id());
                assertThatThrownBy(()->submit.apply(competing)).isInstanceOfSatisfying(V2Exception.class,e->assertThat(e.code).isEqualTo("MODEL_CAPACITY_BUSY"));
                assertThat(calls).hasValue(1);
                // 关闭上下文时旧 HTTP 仍在等待；新上下文通过持久状态恢复，不伪造成功结果。
                firstApp.close();
                try(var secondApp=start(data,url)) {
                    var currentDb=secondApp.getBean(V2Store.class);var runner=secondApp.getBean(V2ModelRunner.class);
                    assertThat(currentDb.get("v2_operations",operation).path("state").asText()).isEqualTo("INTERRUPTED");
                    var recovered=currentDb.get(table,sid);
                    assertThat(practiceCase?recovered.path("status").asText():recovered.path("stages").get(0).path("state").asText()).isEqualTo("INTERRUPTED");
                    if(practiceCase)assertThat(currentDb.get("v2_practice_evaluation_groups",gid).path("status").asText()).isEqualTo("INTERRUPTED");
                    Future<ObjectNode> retry=workers.submit(()->runner.retry(operation,currentDb.object().put("requestId",Models.id()).put("expectedGeneration",0)));
                    assertThat(secondArrived.await(5,TimeUnit.SECONDS)).isTrue();
                    var running=currentDb.get("v2_operations",operation);var before=currentDb.get(table,sid);var groupBefore=practiceCase?currentDb.get("v2_practice_evaluation_groups",gid):null;
                    assertThat(running.path("state").asText()).isEqualTo("RUNNING");assertThat(running.path("generation").asInt()).isEqualTo(1);
                    releaseFirst.countDown();
                    assertThatThrownBy(()->oldRequest.get(5,TimeUnit.SECONDS)).isInstanceOfSatisfying(ExecutionException.class,e->assertThat(e.getCause()).isInstanceOfSatisfying(V2Exception.class,cause->assertThat(cause.code).isEqualTo("V2_OPERATION_SUPERSEDED")));
                    assertThat(currentDb.get("v2_operations",operation)).isEqualTo(running);
                    assertThat(currentDb.get(table,sid)).isEqualTo(before);
                    if(practiceCase)assertThat(currentDb.get("v2_practice_evaluation_groups",gid)).isEqualTo(groupBefore);
                    releaseSecond.countDown();retry.get(5,TimeUnit.SECONDS);
                    var accepted=currentDb.get(table,sid);
                    if(practiceCase) {
                        assertThat(accepted.path("status").asText()).isEqualTo("READY");assertThat(accepted.path("answerText").asText()).isEqualTo("不可覆盖的合成独立回答");assertThat(accepted.path("primaryGroupId").asText()).isEqualTo(gid);
                        accepted=currentDb.get("v2_practice_evaluation_groups",gid);
                        for(String t:new String[]{"v2_practice_attempts","v2_practice_evaluation_groups"})assertThat(StoreTestAccess.jdbc(currentDb.legacy()).queryForObject("SELECT count(*) FROM "+t,Integer.class)).isEqualTo(1);
                    } else {
                        assertThat(accepted.path("stages").get(0).path("currentArtifactVersion").asInt()).isEqualTo(1);
                        assertThat(accepted.path("stages").get(0).path("artifactVersions")).hasSize(1);
                    }
                    assertThat(accepted.toString()).contains("第二代有效反馈").doesNotContain("第一代旧反馈");
                    assertThat(currentDb.get("v2_operations",operation).path("generation").asInt()).isEqualTo(1);
                    assertThat(currentDb.get("v2_operations",operation).path("state").asText()).isEqualTo("SUCCEEDED");
                    assertThat(calls).hasValue(2);
                }
            } finally {releaseFirst.countDown();releaseSecond.countDown();firstApp.close();server.stop(0);}
        } finally {releaseFirst.countDown();releaseSecond.countDown();server.stop(0);}
    }
}
