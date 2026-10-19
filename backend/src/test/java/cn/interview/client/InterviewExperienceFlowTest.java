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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

/** 可控时钟推进用户时间，阻塞仅位于模型边界，不使用墙钟等待模拟二十分钟。 */
@SpringBootTest(classes=Application.class, properties={"app.llm.provider=mock","app.knowledge.network-enabled=false"})
@Import(InterviewExperienceFlowTest.Configuration.class)
class InterviewExperienceFlowTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("v3-experience-"); } catch(Exception e){throw new IllegalStateException(e);} }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @Autowired InterviewService service; @Autowired InterviewSessionStore store; @Autowired ObjectMapper json;
    @Autowired cn.interview.service.MaterialService materials;
    @Autowired MutableClock clock; @Autowired ControlledModel model;
    @AfterEach void cleanup(){model.release.countDown();model.completeStage=false;model.omitDetail=false;for(var s:store.list())if(!List.of("REPORT_READY","CANCELLED").contains(s.path("status").asText()))service.action(s.path("id").asText(),"cancel",action(service.get(s.path("id").asText())));}
    ObjectNode body(){return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("role","Java 后端").put("level","JUNIOR");}
    ObjectNode action(JsonNode s){return json.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("expectedRevision",s.path("revision").asInt());}
    ObjectNode ready(ObjectNode config)throws Exception{return await(service.create(config),"READY");}
    ObjectNode start(ObjectNode config)throws Exception{var s=ready(config);return service.action(s.path("id").asText(),"confirm",action(s));}
    ObjectNode answer(ObjectNode s){var q=s.path("messages").get(s.path("messages").size()-1);return service.action(s.path("id").asText(),"answers",action(s).put("expectedQuestionId",q.path("questionId").asText()).put("content","合成回答：我负责订单查询接口，数据库超时后返回明确错误，使用请求编号排查。"));}
    ObjectNode await(ObjectNode s,String... states)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){s=service.get(s.path("id").asText());if(List.of(states).contains(s.path("status").asText()))return s;if(s.path("status").asText().equals("FAILED"))throw new AssertionError(s.toString());Thread.onSpinWait();}throw new AssertionError("合成任务未完成："+s);}
    @Test void 新报告统计主追问且早退不评未答题()throws Exception{
        var s=start(body());String id=s.path("id").asText();
        assertThat(s.path("reviewVersion").asInt()).isEqualTo(2);
        String root=s.path("messages").get(0).path("rootQuestionId").asText();assertThat(root).isNotBlank();
        answer(s);s=await(s,"IN_PROGRESS");
        assertThat(s.path("messages").get(2).path("rootQuestionId").asText()).isEqualTo(root);
        assertThat(s.path("messages").get(2).path("questionKind").asText()).isEqualTo("FOLLOW_UP");
        answer(s);s=await(s,"IN_PROGRESS");
        s=service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
        var report=s.path("report");assertThat(report.path("version").asInt()).isEqualTo(2);
        assertThat(report.path("overview").path("answeredCount").asInt()).isEqualTo(2);
        assertThat(report.path("overview").path("mainQuestionCount").asInt()).isEqualTo(1);
        assertThat(report.path("overview").path("followUpCount").asInt()).isEqualTo(1);
        assertThat(report.path("overview").path("endReason").asText()).isEqualTo("USER_FINISHED");
        assertThat(report.path("findings")).hasSize(2);
        assertThat(report.path("detailedAssessment").path("version").asInt()).isEqualTo(1);
        assertThat(report.path("detailedAssessment").path("nextSteps")).hasSize(1);
        String persisted=store.get(id).toString();service.get(id);
        assertThat(store.get(id).toString()).isEqualTo(persisted);
    }
    @Test void 旧报告读取与显式复盘不自动升级()throws Exception{
        var s=start(body());String id=s.path("id").asText();var stored=store.get(id);stored.remove(java.util.List.of("reviewVersion", "assessmentVersion"));store.save(stored);
        answer(s);s=await(s,"IN_PROGRESS");s=service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
        assertThat(s.path("report").has("version")).isFalse();String before=store.get(id).toString();service.get(id);
        assertThat(store.get(id).toString()).isEqualTo(before);
    }
    @Test void 新报告缺详细评估时保留回答并等待显式重试()throws Exception{
        var s=start(body());String id=s.path("id").asText();answer(s);s=await(s,"IN_PROGRESS");
        model.omitDetail=true;s=service.action(id,"finish",action(s));s=await(s,"FAILED");
        assertThat(s.has("report")).isFalse();
        assertThat(s.path("messages").findValuesAsText("role")).contains("USER");
        assertThat(store.get(id).path("pendingStage").asText()).isEqualTo("REVIEW");
        assertThat(s.path("allowedActions").toString()).contains("retry");
        int calls=s.path("usage").path("calls").asInt();service.get(id);
        assertThat(service.get(id).path("usage").path("calls").asInt()).isEqualTo(calls);
    }
    @Test void 旧版二报告首次复盘仍保留原结构()throws Exception{
        var s=start(body());String id=s.path("id").asText();var stored=store.get(id);stored.remove("assessmentVersion");store.save(stored);
        answer(s);s=await(s,"IN_PROGRESS");s=service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
        assertThat(s.path("report").path("version").asInt()).isEqualTo(2);
        assertThat(s.path("report").has("detailedAssessment")).isFalse();
    }
    @Test void 两种模式按同一计划题量准备且默认八题()throws Exception{
        for(String mode:List.of("TRAINING","TIMED"))for(int count:List.of(8,12)){
            var s=ready(body().put("interviewMode",mode).put("plannedQuestionCount",count));
            assertThat(s.path("plannedQuestionCount").asInt()).isEqualTo(count);
            assertThat(store.get(s.path("id").asText()).path("questions").size()).isEqualTo(count);
            assertThat(s.path("stages").size()).isEqualTo(4);
        }
        assertThat(ready(body()).path("plannedQuestionCount").asInt()).isEqualTo(8);
    }
    @Test void 计划题量拒绝非法值且开考前纠偏保留原计划()throws Exception{
        for(var invalid:List.of(json.getNodeFactory().numberNode(5),json.getNodeFactory().numberNode(8.5),json.getNodeFactory().textNode("12"),json.nullNode())){
            var config=body();config.set("plannedQuestionCount",invalid);
            assertThatThrownBy(()->service.create(config)).isInstanceOf(ApiException.class);
        }
        var s=ready(body().put("plannedQuestionCount",12));
        s=service.action(s.path("id").asText(),"corrections",action(s).put("role","大模型应用 / Agent 开发工程师"));
        s=await(s,"READY");
        assertThat(s.path("plannedQuestionCount").asInt()).isEqualTo(12);
        assertThat(store.get(s.path("id").asText()).path("questions").size()).isEqualTo(12);
    }
    @Test void 阶段已充分时可少于计划完成但保留四阶段()throws Exception{
        model.completeStage=true;
        for(String mode:List.of("TRAINING","TIMED")){
            var s=start(body().put("interviewMode",mode).put("plannedQuestionCount",12));
            for(String stage:List.of("INTRO","PROJECT","TECHNICAL","QUESTIONS")){
                assertThat(s.path("stage").asText()).isEqualTo(stage);
                answer(s);s=await(s,"IN_PROGRESS","COMPLETED");
            }
            assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(s.path("stages").findValuesAsText("status")).containsOnly("COMPLETED");
            assertThat(s.path("messages").findValuesAsText("role").stream().filter("USER"::equals).count()).isEqualTo(4);
        }
    }
    @Test void 追问可让实际作答轮数超过八题计划()throws Exception{
        var s=start(body().put("plannedQuestionCount",8));
        for(int i=0;i<9;i++){answer(s);s=await(s,"IN_PROGRESS","COMPLETED");}
        assertThat(s.path("messages").findValuesAsText("role").stream().filter("USER"::equals).count()).isEqualTo(9);
        assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");
    }
    @Test void 旧场次不因新阶段完成标记跳过原题且读取不写盘()throws Exception{
        var s=ready(body().put("durationMinutes",20).put("plannedQuestionCount",8));String id=s.path("id").asText();
        var saved=store.get(id);saved.remove("plannedQuestionCount");((ObjectNode)saved.path("input")).remove("plannedQuestionCount");store.save(saved);
        String before=store.get(id).toString();s=service.get(id);
        assertThat(s.has("plannedQuestionCount")).isFalse();assertThat(store.get(id).toString()).isEqualTo(before);
        s=service.action(id,"corrections",action(s).put("role","大模型应用 / Agent 开发工程师"));s=await(s,"READY");
        assertThat(s.has("plannedQuestionCount")).isFalse();
        assertThat(store.get(id).path("input").has("plannedQuestionCount")).isFalse();
        assertThat(store.get(id).path("questions").size()).isEqualTo(8);
        model.completeStage=true;s=service.action(id,"confirm",action(s));
        answer(s);s=await(s,"IN_PROGRESS");assertThat(s.path("stage").asText()).isEqualTo("PROJECT");
        answer(s);s=await(s,"IN_PROGRESS");assertThat(s.path("stage").asText()).isEqualTo("PROJECT");
        s=service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
        assertThat(s.path("report").path("stages").get(3).path("status").asText()).isEqualTo("NOT_STARTED");
    }
    @Test void 新场次标版本并提供四阶段而不把短训练减少为三阶段()throws Exception{
        var s=start(body().put("durationMinutes",5));
        assertThat(s.path("experienceVersion").asInt()).isEqualTo(1);
        assertThat(s.path("stage").asText()).isEqualTo("INTRO");
        assertThat(s.path("stages").size()).isEqualTo(4);
        assertThat(s.path("timing").path("enabled").asBoolean()).isFalse();
        for(int i=0;i<30&&!s.path("status").asText().equals("COMPLETED");i++){answer(s);s=await(s,"IN_PROGRESS","COMPLETED");}
        assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(s.path("stages").findValuesAsText("id")).containsExactly("INTRO","PROJECT","TECHNICAL","QUESTIONS");
        assertThat(s.path("stages").findValuesAsText("status")).containsOnly("COMPLETED");
        int before=s.path("usage").path("calls").asInt();
        assertThat(service.get(s.path("id").asText()).path("usage").path("calls").asInt()).isEqualTo(before);
        assertThat(s.has("report")).isFalse();
    }
    @Test void 实战只累计用户时间且读取不改版本()throws Exception{
        var s=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String id=s.path("id").asText();
        int revision=s.path("revision").asInt();clock.advance(15_000);s=service.get(id);
        assertThat(s.path("timing").path("elapsedMs").asLong()).isEqualTo(15_000);
        assertThat(s.path("timing").path("remainingMs").asLong()).isEqualTo(1_185_000);
        assertThat(s.path("revision").asInt()).isEqualTo(revision);
        model.block=true;model.entered=new CountDownLatch(1);model.release=new CountDownLatch(1);
        try{answer(s);assertThat(model.entered.await(2,TimeUnit.SECONDS)).isTrue();clock.advance(300_000);s=service.get(id);
            assertThat(s.path("timing").path("elapsedMs").asLong()).isEqualTo(15_000);
            assertThat(s.path("timing").path("running").asBoolean()).isFalse();
        }finally{model.block=false;model.release.countDown();}
        s=await(s,"IN_PROGRESS");clock.advance(1_000);
        assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(16_000);
    }
    @Test void 到点保留最后回答但不生成新题或隐式复盘()throws Exception{
        var s=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String id=s.path("id").asText();int calls=s.path("usage").path("calls").asInt();
        clock.advance(1_200_000);s=service.get(id);
        assertThat(s.path("timing").path("expired").asBoolean()).isTrue();assertThat(s.path("timing").path("remainingMs").asLong()).isZero();
        s=answer(s);assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(s.path("messages").size()).isEqualTo(2);assertThat(s.path("usage").path("calls").asInt()).isEqualTo(calls);
        assertThat(s.has("report")).isFalse();
    }
    @Test void 识别等待有界且错误题号不能暂停()throws Exception{
        var s=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String id=s.path("id").asText();String q=s.path("messages").get(0).path("questionId").asText();
        var wrong=action(s).put("expectedQuestionId","过期问题");assertThatThrownBy(()->service.action(id,"transcription-wait",wrong)).isInstanceOf(ApiException.class);
        clock.advance(2_000);s=service.action(id,"transcription-wait",action(s).put("expectedQuestionId",q));clock.advance(6_000);
        assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(2_000);
        clock.advance(6_000);s=service.get(id);assertThat(s.path("timing").path("elapsedMs").asLong()).isEqualTo(4_000);
        s=service.action(id,"transcription-wait",action(s).put("expectedQuestionId",q));
        clock.advance(2_000);assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(4_000);
    }
    @Test void 无标识旧场次读取保持原字段与调用行为()throws Exception{
        var s=ready(body());String id=s.path("id").asText();var internal=store.get(id);internal.remove(List.of("experienceVersion","mainProject","stage","stages","interviewMode","timeLimitMinutes","timingState"));store.save(internal);String baseline=store.get(id).toString();
        s=service.get(id);assertThat(s.has("timing")).isFalse();assertThat(s.has("stages")).isFalse();assertThat(s.has("matrix")).isFalse();assertThat(store.get(id).toString()).isEqualTo(baseline);
    }
    @Test void 实战档位严格校验且默认三十分钟()throws Exception{
        assertThatThrownBy(()->service.create(body().put("interviewMode","TIMED").put("timeLimitMinutes",5))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.create(body().put("interviewMode","OTHER"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.create(body().put("interviewMode","TIMED").put("timeLimitMinutes",20.5))).isInstanceOf(ApiException.class);
        var s=ready(body().put("interviewMode","TIMED"));assertThat(s.path("timeLimitMinutes").asInt()).isEqualTo(30);
        assertThat(s.path("mainProject").path("hypothetical").asBoolean()).isTrue();
    }
    @Test void 反问阶段回应不会变为新题且提前结束如实呈现阶段()throws Exception{
        var s=start(body().put("durationMinutes",5));
        while(!s.path("stage").asText().equals("QUESTIONS")){answer(s);s=await(s,"IN_PROGRESS","COMPLETED");}
        int before=s.path("usage").path("calls").asInt();answer(s);s=await(s,"COMPLETED");
        assertThat(s.path("messages").get(s.path("messages").size()-1).path("kind").asText()).isEqualTo("REPLY");
        assertThat(s.path("messages").get(s.path("messages").size()-1).has("questionId")).isFalse();
        assertThat(s.path("usage").path("calls").asInt()).isEqualTo(before+1);
        var early=start(body());answer(early);early=await(early,"IN_PROGRESS");
        service.action(early.path("id").asText(),"finish",action(early));early=await(early,"REPORT_READY");
        assertThat(early.path("report").path("stages").get(0).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(early.path("report").path("stages").get(3).path("status").asText()).isEqualTo("NOT_STARTED");
    }
    @Test void 项目快照独立且同次复盘带七项矩阵()throws Exception{
        var material=materials.create(json.createObjectNode().put("kind","PROJECT").put("name","合成订单项目").put("content","我负责订单查询接口，使用Java与Redis；架构由同事设计，自己负责接口接入与异常排查。"));
        var s=start(body().put("projectId",material.id()));String id=s.path("id").asText();
        materials.update(material.id(),json.createObjectNode().put("name","后续修改").put("content","已变更的资料"));
        assertThat(service.get(id).path("mainProject").path("brief").asText()).contains("订单查询接口").doesNotContain("已变更");
        answer(s);s=await(s,"IN_PROGRESS");int calls=s.path("usage").path("calls").asInt();
        service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
        assertThat(s.path("report").path("matrix").path("items").size()).isEqualTo(7);
        assertThat(s.path("usage").path("calls").asInt()).isEqualTo(calls+1);
        assertThat(service.source(id).path("matrix")).isEqualTo(s.path("report").path("matrix"));
    }
    @Test void 确认时补述真实项目立即绑定项目题且不额外准备()throws Exception{
        var s=ready(body().put("durationMinutes",20));String id=s.path("id").asText();int calls=s.path("usage").path("calls").asInt();
        s=service.action(id,"confirm",action(s).put("projectBrief","我参与真实订单项目，个人负责查询接口与缓存超时处理。"));
        assertThat(s.path("mainProject").path("hypothetical").asBoolean()).isFalse();
        assertThat(s.path("usage").path("calls").asInt()).isEqualTo(calls);
        assertThat(store.get(id).path("input").path("materialSnapshots").path("project").path("id")).isEqualTo(s.path("mainProject").path("id"));
        for(var q:store.get(id).path("questions")) if(q.path("stage").asText().equals("PROJECT")) assertThat(q.path("question").asText()).contains("个人负责查询接口");
        while(!s.path("stage").asText().equals("PROJECT")){answer(s);s=await(s,"IN_PROGRESS");}
        assertThat(s.path("messages").get(s.path("messages").size()-1).path("content").asText()).contains("个人负责查询接口");
    }
    @Test void 识别提前结束恢复计时且重复通知不会再次扣除等待()throws Exception{
        var s=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String id=s.path("id").asText(),q=s.path("messages").get(0).path("questionId").asText();
        clock.advance(1_000);var wait=action(s).put("expectedQuestionId",q);s=service.action(id,"transcription-wait",wait);
        clock.advance(2_000);s=service.action(id,"transcription-wait",wait);assertThat(s.path("timing").path("elapsedMs").asLong()).isEqualTo(1_000);
        s=service.action(id,"transcription-resume",action(s).put("expectedQuestionId",q));clock.advance(3_000);
        assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(4_000);
    }
    @Test void 重启从持久化用户用时恢复不扣除宕机时间()throws Exception{
        var s=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String id=s.path("id").asText();
        clock.advance(12_000);answer(s);s=await(s,"IN_PROGRESS");
        clock.advance(3_600_000);service.recover();s=service.get(id);
        assertThat(s.path("timing").path("elapsedMs").asLong()).isEqualTo(12_000);
        assertThat(s.path("timing").path("expired").asBoolean()).isFalse();
        assertThat(s.path("warnings").toString()).contains("计时");
        clock.advance(2_000);assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(14_000);
    }
    @Test void 同题分段听写各有界且同一等待窗口不能被重复通知延长()throws Exception{
        var s=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String id=s.path("id").asText(),q=s.path("messages").get(0).path("questionId").asText();
        clock.advance(1_000);s=service.action(id,"transcription-wait",action(s).put("expectedQuestionId",q));
        var duplicate=action(s).put("expectedQuestionId",q);assertThatThrownBy(()->service.action(id,"transcription-wait",duplicate)).isInstanceOf(ApiException.class);
        clock.advance(2_000);s=service.action(id,"transcription-resume",action(s).put("expectedQuestionId",q));clock.advance(3_000);
        s=service.action(id,"transcription-wait",action(s).put("expectedQuestionId",q));clock.advance(2_000);
        assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(4_000);
        clock.advance(10_000);assertThat(service.get(id).path("timing").path("elapsedMs").asLong()).isEqualTo(6_000);
    }
    @Test void 简历中的候选项目须逐字来源并保存相应项目快照()throws Exception{
        var resume=materials.create(json.createObjectNode().put("kind","RESUME").put("name","合成简历").put("content","订单项目：我负责查询接口，使用Java和Redis，架构由同事设计。"));
        var s=ready(body().put("resumeId",resume.id()));String id=s.path("id").asText();
        assertThat(s.path("mainProject").path("hypothetical").asBoolean()).isFalse();
        assertThat(s.path("mainProject").path("brief").asText()).contains("订单项目");
        assertThat(store.get(id).path("input").path("resume").asText()).contains(s.path("mainProject").path("brief").asText());
        assertThat(store.get(id).path("input").path("materialSnapshots").path("project").path("id")).isEqualTo(s.path("mainProject").path("id"));
    }
    @Test void 仅有真实主项目时针对项目准备而无任何资料才标通用摸底()throws Exception{
        var project=ready(body().put("projectBrief","合成真实订单项目：我负责查询接口，使用Java和Redis，排查数据库超时。"));
        assertThat(project.path("warnings").toString()).doesNotContain("本场为无资料的通用基准摸底");
        assertThat(project.path("summary").path("clarifications").toString()).doesNotContain("本场为通用基准摸底");
        var generic=ready(body());
        assertThat(generic.path("warnings").toString()).contains("本场为无资料的通用基准摸底");
    }
    @Test void 显式跳过仅进入相邻阶段并幂等且不调用模型()throws Exception{
        var s=start(body());String id=s.path("id").asText();int calls=s.path("usage").path("calls").asInt();
        assertThat(s.path("allowedActions").toString()).contains("skip-stage");
        var request=action(s).put("expectedStage","INTRO");
        s=service.action(id,"skip-stage",request);
        assertThat(s.path("stage").asText()).isEqualTo("PROJECT");
        assertThat(s.path("stages").get(0).path("status").asText()).isEqualTo("SKIPPED");
        assertThat(s.path("stages").get(0).path("answeredCount").asInt()).isZero();
        assertThat(s.path("usage").path("calls").asInt()).isEqualTo(calls);
        assertThat(service.action(id,"skip-stage",request)).isEqualTo(s);
        var stale=action(s).put("expectedStage","INTRO");
        assertThatThrownBy(()->service.action(id,"skip-stage",stale)).isInstanceOf(ApiException.class);
    }
    @Test void 部分回答后跳过保留原答且复盘区分跳过()throws Exception{
        var s=start(body());String id=s.path("id").asText();
        s=service.action(id,"skip-stage",action(s).put("expectedStage","INTRO"));
        answer(s);s=await(s,"IN_PROGRESS");
        int calls=s.path("usage").path("calls").asInt();
        s=service.action(id,"skip-stage",action(s).put("expectedStage","PROJECT"));
        assertThat(s.path("stage").asText()).isEqualTo("TECHNICAL");
        assertThat(s.path("stages").get(1).path("status").asText()).isEqualTo("SKIPPED");
        assertThat(s.path("stages").get(1).path("answeredCount").asInt()).isEqualTo(1);
        assertThat(s.path("usage").path("calls").asInt()).isEqualTo(calls);
        s=service.action(id,"skip-stage",action(s).put("expectedStage","TECHNICAL"));
        s=service.action(id,"skip-stage",action(s).put("expectedStage","QUESTIONS"));
        assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(s.path("endReason").asText()).isEqualTo("USER_SKIPPED_FINAL_STAGE");
        service.action(id,"finish",action(s));s=await(s,"REPORT_READY");
        assertThat(s.path("report").path("stages").findValuesAsText("status")).containsOnly("SKIPPED");
        assertThat(s.path("report").path("findings")).hasSize(1);
    }
    @Test void 跳过后迟到追问不能改回已跳过阶段()throws Exception{
        var s=start(body());String id=s.path("id").asText();
        model.block=true;model.entered=new CountDownLatch(1);model.release=new CountDownLatch(1);
        try{
            answer(s);assertThat(model.entered.await(2,TimeUnit.SECONDS)).isTrue();s=service.get(id);
            s=service.action(id,"skip-stage",action(s).put("expectedStage","INTRO"));
            assertThat(s.path("stage").asText()).isEqualTo("PROJECT");
            String saved=s.path("messages").toString();model.block=false;model.release.countDown();
            Thread.sleep(100);
            assertThat(service.get(id).path("messages").toString()).isEqualTo(saved);
            assertThat(service.get(id).path("stages").get(0).path("status").asText()).isEqualTo("SKIPPED");
        }finally{model.block=false;model.release.countDown();}
    }
    @Test void 未开考或已到点或旧协议场次不能跳过阶段()throws Exception{
        var ready=ready(body());String readyId=ready.path("id").asText();var unstarted=action(ready).put("expectedStage","INTRO");
        assertThatThrownBy(()->service.action(readyId,"skip-stage",unstarted)).isInstanceOf(ApiException.class);
        var timed=start(body().put("interviewMode","TIMED").put("timeLimitMinutes",20));String timedId=timed.path("id").asText();clock.advance(1_200_000);timed=service.get(timedId);
        assertThat(timed.path("allowedActions").toString()).doesNotContain("skip-stage");
        var expired=action(timed).put("expectedStage","INTRO");assertThatThrownBy(()->service.action(timedId,"skip-stage",expired)).isInstanceOf(ApiException.class);
        var old=start(body());String oldId=old.path("id").asText();var stored=store.get(oldId);stored.remove("experienceVersion");store.save(stored);
        old=service.get(oldId);var legacy=action(old).put("expectedStage","INTRO");
        assertThat(old.path("allowedActions").toString()).doesNotContain("skip-stage");
        assertThatThrownBy(()->service.action(oldId,"skip-stage",legacy)).isInstanceOf(ApiException.class);
    }
    @TestConfiguration static class Configuration{
        @Bean @Primary MutableClock clock(){return new MutableClock();}
        @Bean @Primary ControlledModel model(ObjectMapper json){return new ControlledModel(json);}
    }
    static final class MutableClock extends Clock{
        volatile long value=Instant.parse("2026-10-18T10:00:00Z").toEpochMilli();void advance(long ms){value+=ms;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(value);}
    }
    static final class ControlledModel implements InterviewModelGateway{
        final MockInterviewModel delegate;volatile boolean block,completeStage,omitDetail;volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ControlledModel(ObjectMapper json){delegate=new MockInterviewModel(json);}
        public ObjectNode call(String stage,ObjectNode input,int timeout){if(stage.equals("FOLLOWUP")&&block){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}var result=delegate.call(stage,input);if(stage.equals("REVIEW")&&omitDetail)result.remove("detailedAssessment");if(stage.equals("FOLLOWUP")&&completeStage)result.put("action","NEXT").put("stageComplete",true);return result;}
    }
}
