package cn.interview.client.source;

import cn.interview.client.interview.FocusedInterviewModelClient;
import cn.interview.client.interview.InterviewModelClient;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.dto.ModelSettings;
import cn.interview.mapper.ModelConfigStore;
import cn.interview.service.model.ModelConfigurationService;
import cn.interview.service.source.CodeExplorationService;
import cn.interview.validation.FocusedInterviewOutputValidator;
import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

/** 显式联网的小批验收：一次源码探索与两种真实规划，共享固定证据，不使用日常资料。 */
@EnabledIfEnvironmentVariable(named="SOURCE_INTERVIEW_LIVE_TESTS",matches="true")
class SourceInterviewLiveTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void verifiesPublicSourceAndBothRealPlanningTasks() throws Exception {
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("DOCUMENT_MAP.md")))root=root.getParent();
        if(root==null)throw new IllegalStateException("无法确定仓库目录");
        String target=System.getenv("SOURCE_LIVE_EVIDENCE");
        if(target==null||target.isBlank())throw new IllegalStateException("必须指定新的独立验收目录");
        Path evidence=Path.of(target).toAbsolutePath().normalize();
        if(!evidence.startsWith(root.resolve(".runtime/source-poc")))throw new IllegalStateException("验收记录必须位于本任务的本机忽略目录");
        Files.createDirectory(evidence);
        Path data=Files.createTempDirectory("source-interview-live-");
        var env=new MockEnvironment().withProperty("app.llm.provider",System.getenv().getOrDefault("LLM_PROVIDER","openai"))
                .withProperty("app.llm.base-url",System.getenv().getOrDefault("LLM_BASE_URL","https://api.openai.com/v1"))
                .withProperty("app.llm.api-key",System.getenv().getOrDefault("LLM_API_KEY",""))
                .withProperty("app.llm.model",System.getenv().getOrDefault("LLM_MODEL",""))
                .withProperty("app.llm.reasoning-effort",System.getenv().getOrDefault("LLM_REASONING_EFFORT",""));
        var settings=new ModelConfigurationService(env,new ModelConfigStore(data.toString(),json),json);
        assertThat(settings.connection().isMock()).isFalse();assertThat(settings.connection().configured()).isTrue();
        var transport=new LedgerTransport(evidence,settings.connection().model());boolean passed=false;
        try {
            var github=new GitHubCodeClient(json,data.toString());
            var exploration=new CodeExplorationService(json,github,new GitMcpSearchClient(),new SourceToolModelClient(transport),settings,true);
            String project="这是合成学习项目，使用 Spring Petclinic 公开示例学习宠物诊所的主人、宠物与就诊记录管理，技术栈为 Spring MVC、Spring Data JPA 和关系数据库。学习关注请求参数校验、实体关联、数据访问与异常边界，不声明本人参与该开源仓库开发。";
            var source=json.createObjectNode().put("repository","spring-projects/spring-petclinic").put("status","PENDING");
            transport.phase="SOURCE";
            String saved=System.getenv("SOURCE_LIVE_SNAPSHOT");
            if(saved!=null&&!saved.isBlank()){
                Path previous=Path.of(saved).toAbsolutePath().normalize();
                if(!previous.startsWith(root.resolve(".runtime/source-poc")))throw new IllegalStateException("只能显式复用本任务保存的核验快照");
                source=(ObjectNode)json.readTree(Files.readAllBytes(previous));
                assertThat(source.path("repository").asText()).isEqualTo("spring-projects/spring-petclinic");
                assertThat(source.path("commitSha").asText()).matches("[a-f0-9]{40}");
                assertThat(source.path("explorationCompleted").asBoolean()).isTrue();
                transport.ledger.put("reusedSnapshot",root.relativize(previous).toString());
                write(evidence.resolve("source.json"),source);
            }else exploration.prepare(source,json.createObjectNode().put("role","Java 后端工程师").put("project",project),snapshot->write(evidence.resolve("source.json"),snapshot));
            assertThat(transport.failed).as("首次上游失败必须停止批次").isFalse();
            assertThat(source.path("evidence").size()).isGreaterThanOrEqualTo(3);
            var focusedInput=json.createObjectNode().put("role","Java 后端工程师").put("level","MIDDLE").put("mode","PROJECT")
                    .put("style","NORMAL").put("totalQuestions",3).put("experienceVersion",1).put("context",project).put("jd","").put("focus","");
            focusedInput.putObject("mainProject").put("name","Petclinic 学习项目").put("brief",project).put("hypothetical",false);
            focusedInput.set("sourceCode",source.deepCopy());
            transport.phase="FOCUSED_PLAN";
            var focused=new FocusedInterviewModelClient(json,settings,120,transport).call("planner",focusedInput);
            FocusedInterviewOutputValidator.plan(focused,3,true,project);
            CodeExplorationService.bindQuestions(focused,focused.withArray("questions"),source,false);
            write(evidence.resolve("focused-plan.json"),focused);
            var input=json.createObjectNode().put("role","Java 后端工程师").put("level","MIDDLE").put("style","NORMAL").put("sessionType","COMPREHENSIVE")
                    .put("experienceVersion",1).put("totalQuestions",8).put("plannedQuestionCount",8).put("resume",project).put("project",project).put("jd","").put("correction","");
            input.set("mainProject",focusedInput.path("mainProject").deepCopy());input.putArray("sources");
            var client=new InterviewModelClient(json,settings,transport);var validator=new InterviewOutputValidator(json);
            transport.phase="ANALYZE";
            var analysis=validator.analysis(client.call("ANALYZE",input,120));
            write(evidence.resolve("analysis.json"),analysis);input.set("summary",analysis.path("summary"));input.set("sourceCode",source.deepCopy());
            transport.phase="COMPREHENSIVE_PLAN";
            var comprehensive=client.call("PLAN",input,120);
            var clean=validator.questions(comprehensive,input.path("sources"),project,project,8);
            validator.stageQuestions(comprehensive,clean,input);CodeExplorationService.bindQuestions(comprehensive,clean,source,true);
            write(evidence.resolve("comprehensive-plan.json"),comprehensive);
            int sourceQuestions=0;
            for(var plan:List.of(focused,comprehensive))for(var q:plan.path("questions"))if(!q.path("codeEvidenceIds").isEmpty())sourceQuestions++;
            assertThat(sourceQuestions).as("仍须人工核对至少三道问题与真实方法的语义对应").isGreaterThanOrEqualTo(3);
            passed=true;
        } finally { transport.finish(passed); }
    }
    void write(Path path,com.fasterxml.jackson.databind.JsonNode value){
        try{Files.writeString(path,json.writerWithDefaultPrettyPrinter().writeValueAsString(value));}
        catch(Exception error){throw new IllegalStateException("无法保存隔离验收记录",error);}
    }
    class LedgerTransport extends OpenAiJsonClient {
        final Path directory;final ObjectNode ledger=json.createObjectNode();final ArrayNode attempts=ledger.putArray("attempts");
        String phase="SOURCE";boolean failed;
        LedgerTransport(Path directory,String model){super(SourceInterviewLiveTest.this.json);this.directory=directory;ledger.put("model",model);}
        ObjectNode begin(){
            if(failed)throw new IllegalStateException("当前批次已失败，禁止隐式重试");
            int number=attempts.size()+1;
            var record=attempts.addObject().put("number",number).put("phase",phase).put("startedAt",java.time.Instant.now().toString()).put("status","ATTEMPT");
            write(directory.resolve("ledger.json"),ledger);return record;
        }
        void end(ObjectNode record,long start,RuntimeException error){
            record.put("elapsedMs",(System.nanoTime()-start)/1_000_000).put("status",error==null?"RETURNED":"FAILED");
            if(error!=null){failed=true;record.put("error",error.getMessage()==null?"未知调用错误":error.getMessage());}
            write(directory.resolve("ledger.json"),ledger);
        }
        @Override public Reply exchange(ModelSettings settings,String prompt,ObjectNode input,int maxTokens,int timeout,boolean fast,Consumer<ObjectNode> usage){
            var record=begin();long started=System.nanoTime();
            try{
                var reply=super.exchange(settings,prompt,input,maxTokens,timeout,fast,actual->{record.set("usage",actual.deepCopy());usage.accept(actual);});
                record.put("returnedModel",reply.model()).put("finishReason",reply.finishReason());
                write(directory.resolve("response-"+record.path("number").asInt()+".json"),json.createObjectNode().put("text",reply.text()));
                end(record,started,null);return reply;
            }catch(RuntimeException error){end(record,started,error);throw error;}
        }
        @Override public ChatResponse exchangeTools(ModelSettings settings,List<Message> messages,List<ToolCallback> callbacks,int maxTokens,int timeout,Consumer<ObjectNode> usage){
            var record=begin();long started=System.nanoTime();
            try{
                var reply=super.exchangeTools(settings,messages,callbacks,maxTokens,timeout,actual->{record.set("usage",actual.deepCopy());usage.accept(actual);});
                record.put("returnedModel",reply.getMetadata().getModel());
                record.set("toolCalls",json.valueToTree(reply.getResult().getOutput().getToolCalls()));
                end(record,started,null);return reply;
            }catch(RuntimeException error){end(record,started,error);throw error;}
        }
        void finish(boolean passed){ledger.put("validationPassed",passed).put("stoppedAt",java.time.Instant.now().toString());write(directory.resolve("ledger.json"),ledger);}
    }
}
