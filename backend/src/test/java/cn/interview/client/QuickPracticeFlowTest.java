package cn.interview.client;

import cn.interview.Application;
import cn.interview.exception.ApiException;
import cn.interview.mapper.InterviewSessionStore;
import cn.interview.service.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** 真正运行会话编排与持久化，测试替身仅隔离外部模型。 */
@SpringBootTest(classes=Application.class, properties={"app.llm.provider=mock", "app.knowledge.network-enabled=false"})
@Import(QuickPracticeFlowTest.Configuration.class)
class QuickPracticeFlowTest {
  static final Path DATA;
  static { try { DATA=Files.createTempDirectory("quick-practice-"); } catch(Exception e) {throw new IllegalStateException(e);} }
  @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", DATA::toString); }
  @Autowired InterviewService service;
  @Autowired InterviewSessionStore store;
  @Autowired ObjectMapper json;
  @Autowired ControlledModel model;
  @AfterEach void cleanup() {
    model.badQuote=false; model.next=true; model.repeat=false; model.failPlan=false;
    for(var s:store.list()) if(!List.of("REPORT_READY","CANCELLED").contains(s.path("status").asText()))
      service.action(s.path("id").asText(), "cancel", action(service.get(s.path("id").asText())));
  }
  ObjectNode body() { return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("sessionType","QUICK").put("role","Java 后端工程师"); }
  ObjectNode action(JsonNode s) { return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",s.path("revision").asInt()); }
  ObjectNode await(ObjectNode s,String... states)throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
    while(System.nanoTime()<deadline) {
      s=service.get(s.path("id").asText());
      if(List.of(states).contains(s.path("status").asText()))return s;
      if(s.path("status").asText().equals("FAILED"))throw new AssertionError(s.toString());
      Thread.sleep(10);
    }
    throw new AssertionError("等待状态超时："+s);
  }
  ObjectNode answer(ObjectNode s) {
    var q=s.path("messages").get(s.path("messages").size()-1);
    return service.action(s.path("id").asText(),"answers",action(s).put("expectedQuestionId",q.path("questionId").asText()).put("content","我负责请求校验，已验证重复提交；尚未测试依赖超时。"));
  }
  @Test void 无资料默认五题且准备后自动第一问()throws Exception {
    var s=await(service.create(body()),"IN_PROGRESS");
    assertThat(s.path("level").asText()).isEqualTo("JUNIOR");
    assertThat(s.path("plannedQuestionCount").asInt()).isEqualTo(5);
    assertThat(s.has("stages")).isFalse(); assertThat(s.has("timing")).isFalse();
    assertThat(s.path("messages")).hasSize(1);
    assertThat(s.path("messages").get(0).path("content").asText()).doesNotContain("介绍", "反问");
    assertThat(s.path("usage").path("calls").asInt()).isEqualTo(1);
    assertThat(s.path("allowedActions").toString()).contains("answers").doesNotContain("confirm","skip-stage");
  }
  @Test void 自定义非Java岗位不预设Java技术来源()throws Exception {
    var s=await(service.create(body().put("role","前端工程师")),"IN_PROGRESS");
    assertThat(model.planInput.path("role").asText()).isEqualTo("前端工程师");
    assertThat(store.get(s.path("id").asText()).path("topics")).isEmpty();
    assertThat(model.planInput.path("sources")).isEmpty();
    assertThat(s.path("warnings").toString()).contains("技术");
    assertThat(s.path("usage").path("calls").asInt()).isEqualTo(1);
  }
  @Test void 三五八道主问题精确规划且正常答满()throws Exception {
    for(int count:List.of(3,5,8)) {
      var s=await(service.create(body().put("plannedQuestionCount",count)),"IN_PROGRESS");
      assertThat(store.get(s.path("id").asText()).path("questions")).hasSize(count);
      for(int i=0;i<count;i++) { answer(s);s=await(s,"IN_PROGRESS","COMPLETED"); }
      assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
      assertThat(s.path("messages").findValuesAsText("questionKind").stream().filter("MAIN"::equals).count()).isEqualTo(count*2L);
    }
  }
  @Test void 拒绝非法题量类型资料与快速计时配置() {
    for(JsonNode value:List.of(json.getNodeFactory().numberNode(4),json.getNodeFactory().textNode("5"),json.nullNode())) {
      var b=body();b.set("plannedQuestionCount",value);assertThatThrownBy(()->service.create(b)).isInstanceOf(ApiException.class);
    }
    assertThatThrownBy(()->service.create(body().put("sessionType","OTHER"))).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->service.create(body().put("jd","个人资料不进入快速练习"))).isInstanceOf(ApiException.class);
    assertThatThrownBy(()->service.create(body().put("interviewMode","TIMED"))).isInstanceOf(ApiException.class);
  }
  @Test void 连续诊断绑定原答且问答隐藏复盘可回查()throws Exception {
    var s=await(service.create(body().put("plannedQuestionCount",3)),"IN_PROGRESS");
    String id=s.path("id").asText();answer(s);s=await(s,"IN_PROGRESS");
    var internal=store.get(id);assertThat(internal.path("diagnostics")).hasSize(1);
    var first=internal.path("diagnostics").get(0);
    assertThat(first.path("answerMessageId").asText()).isEqualTo(s.path("messages").get(1).path("id").asText());
    assertThat(first.path("questionId").asText()).isEqualTo(s.path("messages").get(1).path("questionId").asText());
    assertThat(s.has("diagnostics")).isFalse();assertThat(s.toString()).doesNotContain("nextGoal");
    answer(s);s=await(s,"IN_PROGRESS");assertThat(model.lastInput.path("diagnostics")).hasSize(1);
    s=service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
    assertThat(s.path("report").path("diagnostics")).hasSize(2);
    assertThat(s.path("report").path("findings")).hasSize(2);
    assertThat(s.path("report").has("stages")).isFalse();assertThat(s.path("report").has("matrix")).isFalse();
    assertThat(s.path("report").path("detailedAssessment").path("version").asInt()).isEqualTo(1);
    assertThat(service.list().findValuesAsText("sessionType")).contains("QUICK");
    var row=java.util.stream.StreamSupport.stream(service.list().spliterator(),false).filter(r->r.path("id").asText().equals(id)).findFirst().orElseThrow();
    assertThat(row.path("answerCount").asInt()).isEqualTo(2);
  }
  @Test void 伪造当前原话不保存为专业诊断()throws Exception {
    var s=await(service.create(body()),"IN_PROGRESS");model.badQuote=true;
    answer(s);s=await(s,"IN_PROGRESS","INTERRUPTED");
    assertThat(store.get(s.path("id").asText()).path("diagnostics").isEmpty()).isTrue();
    assertThat(s.path("metrics").path("lastFallback").asBoolean()).isTrue();
  }
  @Test void 每主问题最多追问两次且旧综合仍需确认()throws Exception {
    model.next=false;var s=await(service.create(body().put("plannedQuestionCount",3)),"IN_PROGRESS");
    for(int i=0;i<3;i++){answer(s);s=await(s,"IN_PROGRESS");}
    assertThat(s.path("messages").get(6).path("questionKind").asText()).isEqualTo("MAIN");
    var comprehensive=body();comprehensive.remove("sessionType");comprehensive.put("level","JUNIOR");
    var c=await(service.create(comprehensive),"READY");
    assertThat(c.path("stages")).hasSize(4);assertThat(c.path("messages")).isEmpty();
    assertThat(c.path("plannedQuestionCount").asInt()).isEqualTo(8);
  }
  @Test void 空答结束不调用复盘且重复创建幂等()throws Exception {
    var b=body();var s=await(service.create(b),"IN_PROGRESS");assertThat(service.create(b).path("id")).isEqualTo(s.path("id"));
    s=service.action(s.path("id").asText(),"finish",action(s));s=await(s,"REPORT_READY");
    assertThat(s.path("report").path("findings")).isEmpty();assertThat(s.path("usage").path("calls").asInt()).isEqualTo(1);
  }
  @Test void 完全重复的追问由服务端换题且保留原决策()throws Exception {
    model.next=false;model.repeat=true;
    var s=await(service.create(body().put("plannedQuestionCount",3)),"IN_PROGRESS");
    answer(s);s=await(s,"IN_PROGRESS");answer(s);s=await(s,"IN_PROGRESS");
    assertThat(s.path("messages").get(4).path("questionKind").asText()).isEqualTo("MAIN");
    var trace=store.get(s.path("id").asText()).path("diagnostics").get(1);
    assertThat(trace.path("action").asText()).isEqualTo("FOLLOW_UP");
    assertThat(trace.path("effectiveAction").asText()).isEqualTo("NEXT");
  }
  @Test void 规划失败显式重试后自动首问且重放不重复开考()throws Exception {
    int previousCalls=model.planCalls.get();model.failPlan=true;
    var s=await(service.create(body()),"FAILED");String id=s.path("id").asText();
    assertThat(s.path("messages")).isEmpty();
    assertThat(s.path("allowedActions").toString()).contains("retry").doesNotContain("confirm");
    assertThat(model.planCalls).hasValue(previousCalls+1);
    model.failPlan=false;var retry=action(s);
    s=await(service.action(id,"retry",retry),"IN_PROGRESS");
    assertThat(s.path("messages")).hasSize(1);
    assertThat(s.path("messages").get(0).path("role").asText()).isEqualTo("ASSISTANT");
    assertThat(s.path("allowedActions").toString()).contains("answers").doesNotContain("confirm","skip-stage");
    var replay=service.action(id,"retry",retry);
    assertThat(replay.path("messages")).isEqualTo(s.path("messages"));
    assertThat(replay.path("revision")).isEqualTo(s.path("revision"));
    assertThat(model.planCalls).hasValue(previousCalls+2);
  }
  @Test void 同一回答请求重放只保存一答一条诊断且追问调用一次()throws Exception {
    var s=await(service.create(body()),"IN_PROGRESS");String id=s.path("id").asText();
    var question=s.path("messages").get(0);
    var request=action(s).put("expectedQuestionId",question.path("questionId").asText())
        .put("content","我负责请求校验，已验证重复提交；尚未测试依赖超时。");
    int previousCalls=model.followupCalls.get();
    service.action(id,"answers",request);
    service.action(id,"answers",request);
    s=await(s,"IN_PROGRESS");var replay=service.action(id,"answers",request);
    assertThat(replay.path("messages")).isEqualTo(s.path("messages")).hasSize(3);
    assertThat(replay.path("messages").findValuesAsText("role").stream().filter("USER"::equals).count()).isEqualTo(1);
    assertThat(store.get(id).path("diagnostics")).hasSize(1);
    assertThat(model.followupCalls).hasValue(previousCalls+1);
    assertThat(replay.path("usage")).isEqualTo(s.path("usage"));
    assertThat(replay.path("usage").path("calls").asInt()).isEqualTo(2);
  }
  @Test void 已有诊断仅在复盘完成状态公开()throws Exception {
    var s=await(service.create(body()),"IN_PROGRESS");String id=s.path("id").asText();
    answer(s);s=await(s,"IN_PROGRESS");
    service.action(id,"cancel",action(s));var stable=store.get(id);
    assertThat(stable.path("diagnostics")).hasSize(1);
    for(String state:List.of("QUEUED","PREPARING","READY","IN_PROGRESS","ANSWERING","COMPLETED","REPORTING","FAILED","INTERRUPTED","CANCELLED","REPORT_READY")) {
      var snapshot=stable.deepCopy().put("id",UUID.randomUUID().toString()).put("status",state);
      store.save(snapshot);var view=service.get(snapshot.path("id").asText());
      if(state.equals("REPORT_READY")) assertThat(view.path("diagnostics")).hasSize(1);
      else {
        assertThat(view.has("diagnostics")).as("%s 状态不公开诊断",state).isFalse();
        assertThat(view.findValues("nextGoal")).as("%s 状态不泄露追问目标",state).isEmpty();
      }
    }
  }
  @TestConfiguration static class Configuration {
    @Bean @Primary ControlledModel controlled(ObjectMapper json){return new ControlledModel(json);}
  }
  static class ControlledModel implements InterviewModelGateway {
    final ObjectMapper json;volatile boolean next=true,badQuote=false,repeat=false,failPlan=false;volatile ObjectNode lastInput,planInput;
    final AtomicInteger planCalls=new AtomicInteger(),followupCalls=new AtomicInteger();
    ControlledModel(ObjectMapper json){this.json=json;}
    public ObjectNode call(String stage,ObjectNode input,int timeout) {
      if(stage.equals("PLAN")) {
        planInput=input.deepCopy();
        planCalls.incrementAndGet();
        if(failPlan)throw new ApiException(502,"MODEL_ERROR","合成规划失败，用于验证显式重试");
      }
      var out=new MockInterviewModel(json).call(stage,input);
      if(stage.equals("FOLLOWUP")) {
        followupCalls.incrementAndGet();
        lastInput=input.deepCopy();
        if(next)out.put("action","NEXT").put("question","");
        if(repeat)out.put("action","FOLLOW_UP").put("question","请说明超时后的验证方法？");
        if(badQuote)out.withObject("/diagnosis").put("answerQuote","候选人从未说过的指标");
      }
      return out;
    }
  }
}
