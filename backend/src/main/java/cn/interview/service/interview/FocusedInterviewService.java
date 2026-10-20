package cn.interview.service.interview;

import cn.interview.client.interview.FocusedInterviewModelClient;
import cn.interview.dto.Context;
import cn.interview.entity.Material;
import cn.interview.exception.ApiException;
import cn.interview.mapper.SqliteStore;
import cn.interview.service.material.MaterialService;
import cn.interview.service.material.ProjectEvidenceService;
import cn.interview.service.training.TrainingLinkService;
import cn.interview.validation.FocusedInterviewOutputValidator;
import cn.interview.service.source.CodeExplorationService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

import static cn.interview.common.Models.*;

@Service
public class FocusedInterviewService {
    final SqliteStore store;final MaterialService materials;final FocusedInterviewModelClient llm;final ObjectMapper json;
    private org.springframework.beans.factory.ObjectProvider<TrainingLinkService> trainingLinks;
    @org.springframework.beans.factory.annotation.Autowired
    void trainingLinks(org.springframework.beans.factory.ObjectProvider<TrainingLinkService> links) { this.trainingLinks = links; }
    private CodeExplorationService source;
    @org.springframework.beans.factory.annotation.Autowired
    void source(CodeExplorationService service){this.source=service;}
    static final List<String> PUBLIC_FIELDS=List.of("id","status","mode","role","level","durationMinutes","style","createdAt","updatedAt","endedAt","questionIndex","followUpCount","totalQuestions","currentTopic","messages","mock","error","pendingAction","reportStatus","contextTruncated","experienceVersion","mainProject");
    public FocusedInterviewService(SqliteStore store,MaterialService materials,FocusedInterviewModelClient llm,ObjectMapper json){this.store=store;this.materials=materials;this.llm=llm;this.json=json;}
    public Map<String,Object> health(){return llm.health();}
    public boolean isMock(){return llm.isMock();}
    /** 规划已原子预留的关联场次，返回与普通创建相同的公开视图。 */
    public ObjectNode startLinkedSession(ObjectNode session){plan(session);return publicView(session);}
    @PostConstruct void recover(){for(ObjectNode s:store.sessions()){if(s.hasNonNull("deletedAt"))continue;boolean changed=false;String pending=s.path("pendingAction").asText();if(!pending.isEmpty()&&!s.path("status").asText().equals("COMPLETED")){s.put("status",pending.equals("PLAN")?"FAILED":"INTERRUPTED").put("error","上次生成因服务重启而中断，输入已保存，请重试");changed=true;}if(s.path("reportStatus").asText().equals("GENERATING")){s.put("reportStatus","FAILED").put("error","报告生成因服务重启而中断，请重试报告");changed=true;}if(changed)store.saveSession(s);}}
    public ObjectNode publicView(ObjectNode s){ObjectNode out=json.createObjectNode();for(String field:PUBLIC_FIELDS)if(s.has(field))out.set(field,s.get(field).deepCopy());
        if(s.path("sourceCode").isObject())out.set("sourceCode",CodeExplorationService.summary(s.path("sourceCode")));
        for(var message:out.path("messages"))if(message instanceof ObjectNode m){m.put("hasCodeEvidence",!m.path("codeEvidenceIds").isEmpty());m.remove("codeEvidenceIds");}
        return out;}
    public List<ObjectNode> list(){return store.sessions().stream().filter(s->!s.hasNonNull("deletedAt")).map(this::publicView).toList();}
    public ObjectNode get(String id){return publicView(activeSession(id));}
    ObjectNode activeSession(String id){ObjectNode s=store.session(id);if(s.hasNonNull("deletedAt"))throw ApiException.missing("面试不存在或已删除");return s;}
    public synchronized void delete(String id){
        ObjectNode s=store.session(id);if(s.hasNonNull("deletedAt"))return;
        // 保留来源快照，避免破坏已整理的复习项与原题重练。
        s.put("deletedAt",now());store.saveSession(s);
    }
    public synchronized ObjectNode create(JsonNode body){
        if (body.hasNonNull("trainingLinkId")) {
            if (!body.path("trainingLinkId").isTextual()) throw cn.interview.exception.RecoveryException.invalid("trainingLinkId 必须为文本");
            if (!body.path("trainingLinkId").asText().isBlank()) {
                var links = trainingLinks == null ? null : trainingLinks.getIfAvailable();
                if (links == null) throw new cn.interview.exception.RecoveryException(404, "V2_SOURCE_NOT_FOUND", "训练来源不存在，请返回原复习项重新选择。", "LOAD_LATEST");
                return links.create(this, body);
            }
        }
        String mode=choice(optional(body,"mode","QUICK",20),"QUICK","RESUME","PROJECT");String role=optional(body,"role","大模型应用 / Agent 开发工程师",120);
        String level=choice(optional(body,"level","JUNIOR",20),"INTERN","JUNIOR","SENIOR");String style=choice(optional(body,"style","NORMAL",20),"NORMAL","PRESSURE");
        int duration=body.path("durationMinutes").isMissingNode()?10:body.path("durationMinutes").asInt(-1);if(body.has("durationMinutes")&&!body.path("durationMinutes").isIntegralNumber()||!List.of(5,10,20).contains(duration))throw ApiException.bad("目标时长必须为 5、10 或 20 分钟");
        String jd=optional(body,"jd","",10000),focus=optional(body,"focus","",2000),materialId=optional(body,"materialId","",100),predictionId=optional(body,"predictionId","",100),questionId=optional(body,"questionId","",100);
        Context context=new Context(jd,false);ObjectNode selected=null;Material selectedProject=null;
        if(!predictionId.isBlank()){
            if(!mode.equals("RESUME")||questionId.isBlank())throw ApiException.bad("押题练习必须选择简历模式与具体问题");ObjectNode prediction=store.prediction(predictionId);
            if(prediction.path("mock").asBoolean()!=llm.isMock())throw ApiException.bad("该押题结果与当前模型模式不同，请重新生成押题后练习");
            for(JsonNode q:prediction.path("questions"))if(q.path("id").asText().equals(questionId))selected=(ObjectNode)q;
            if(selected==null)throw ApiException.bad("所选问题不属于该押题列表");FocusedInterviewOutputValidator.question(selected,"question");context=new Context(prediction.path("context").asText(),prediction.path("contextTruncated").asBoolean());role=prediction.path("role").asText();
        }else if(!questionId.isBlank())throw ApiException.bad("选择问题时必须提供押题记录");
        else if(!mode.equals("QUICK")){
            if(materialId.isBlank())throw ApiException.bad("请选择面试资料");Material m=store.material(materialId);
            if(!m.kind().equals(mode.equals("PROJECT")?"PROJECT":"RESUME"))throw ApiException.bad("所选资料类型与面试模式不匹配");ensureContent(m);context=materials.context(m,jd);if(mode.equals("PROJECT"))selectedProject=m;
        }
        String id=id(),now=now();ObjectNode s=json.createObjectNode().put("schemaVersion",1).put("id",id).put("status","CREATED").put("mode",mode).put("role",role).put("level",level).put("style",style).put("durationMinutes",duration).put("createdAt",now).put("updatedAt",now).put("questionIndex",0).put("followUpCount",0).put("totalQuestions",selected!=null?1:duration==5?3:duration==10?5:8).put("currentTopic","").put("mock",llm.isMock()).put("error","").put("pendingAction","PLAN").put("reportStatus","NONE").put("contextTruncated",context.truncated()).put("context",context.text()).put("jd",jd).put("focus",focus);
        if(selectedProject!=null){var association=CodeExplorationService.association(selectedProject.repository());if(association!=null)s.set("sourceCode",association);var projectContext=materials.context(selectedProject,"");s.put("experienceVersion",1);s.putObject("mainProject").put("id",selectedProject.id()).put("name",selectedProject.name()).put("brief",projectContext.text()).put("hypothetical",false).put("updatedAt",selectedProject.updatedAt()).put("truncated",projectContext.truncated());}
        s.putNull("endedAt");s.putArray("messages");s.putObject("requests");s.putArray("plan");store.saveSession(s);
        if(selected!=null){ObjectNode q=json.createObjectNode().put("question",selected.path("question").asText()).put("topic",selected.path("knowledgePoint").asText()).put("level","L2").put("evidence",selected.path("evidence").asText());((ArrayNode)s.path("plan")).add(q);begin(s);store.saveSession(s);}else plan(s);
        return publicView(s);
    }
    void ensureContent(Material m){if(m.content().isBlank()&&m.documents().isEmpty())throw ApiException.bad("资料没有可用正文，请先填写项目内容或上传文档");}
    ObjectNode input(ObjectNode s){ObjectNode in=json.createObjectNode();for(String key:List.of("mode","role","level","style","durationMinutes","totalQuestions","context","jd","focus","messages","questionIndex","followUpCount","currentTopic"))in.set(key,s.get(key));if(s.has("experienceVersion")){in.set("experienceVersion",s.path("experienceVersion"));in.set("mainProject",s.path("mainProject"));}if(s.path("sourceCode").isObject())in.set("sourceCode",s.path("sourceCode").deepCopy());int next=s.path("questionIndex").asInt()+1;if(next<s.path("plan").size())in.set("nextPlannedQuestion",s.path("plan").get(next));return in;}
    void plan(ObjectNode s) {
        if(s.path("sourceCode") instanceof ObjectNode code){
            try{var sourceContext=json.createObjectNode().put("role",s.path("role").asText()).put("project",s.path("mainProject").path("brief").asText().substring(0,Math.min(6000,s.path("mainProject").path("brief").asText().length())));source.prepare(code,sourceContext,snapshot->{s.set("sourceCode",snapshot);store.saveSession(s);});s.set("sourceCode",code);}
            catch(ApiException error){failed(s,error,"FAILED");return;}
        }
        ObjectNode before=s.deepCopy();
        try {
            ObjectNode p=llm.call("planner",input(s));
            FocusedInterviewOutputValidator.plan(p,s.path("totalQuestions").asInt(),s.path("mode").asText().equals("PROJECT"),s.path("context").asText());
            CodeExplorationService.bindQuestions(p,(ArrayNode)p.path("questions"),s.path("sourceCode"),false);
            s.set("plan",p.get("questions"));
            begin(s);
        } catch(RuntimeException e) {
            restore(s,before);
            failed(s,modelFailure(e),"FAILED");
            return;
        }
        store.saveSession(s);
    }
    void begin(ObjectNode s) {
        JsonNode q=s.path("plan").get(0);
        s.put("status","IN_PROGRESS").put("pendingAction","").put("error","").put("currentTopic",q.path("topic").asText());
        message(s,"ASSISTANT",q.path("question").asText(),false);codeReferences(s,q);
    }
    void restore(ObjectNode state,ObjectNode before) { state.removeAll();state.setAll(before); }
    ApiException modelFailure(RuntimeException error) {
        return error instanceof ApiException known?known:ApiException.model("模型结果处理失败，当前输入已保存，请重试");
    }
    void message(ObjectNode s,String role,String content,boolean followUp){((ArrayNode)s.path("messages")).addObject().put("id",id()).put("role",role).put("content",content).put("questionIndex",s.path("questionIndex").asInt()).put("followUp",followUp).put("createdAt",now());}
    void codeReferences(ObjectNode s,JsonNode question){
        var refs=CodeExplorationService.references(question,s.path("sourceCode"));
        ((ObjectNode)s.path("messages").get(s.path("messages").size()-1)).set("codeEvidenceIds",refs);
    }
    public synchronized ObjectNode continueWithoutSource(String id){
        var s=activeSession(id);if(!s.path("status").asText().equals("FAILED")||!s.path("pendingAction").asText().equals("PLAN"))throw ApiException.conflict("当前不能切换为资料面试");
        CodeExplorationService.skip((ObjectNode)s.path("sourceCode"));store.saveSession(s);plan(s);return publicView(s);
    }
    void failed(ObjectNode s,ApiException e,String status){s.put("status",status).put("error",e.getMessage());store.saveSession(s);}
    void sameProvider(ObjectNode s){if(s.path("mock").asBoolean()!=llm.isMock())throw ApiException.conflict("当前模型模式与本场面试不同，请切回原模式或新建面试");}
    public synchronized ObjectNode answer(String id,JsonNode body){ObjectNode s=activeSession(id);String content=required(body,"content",6000),requestId=required(body,"requestId",120),expected=required(body,"expectedMessageId",100);
        JsonNode previous=s.path("requests").path(requestId);if(!previous.isMissingNode()){if(!previous.path("content").asText().equals(content)||!previous.path("expectedMessageId").asText().equals(expected))throw ApiException.conflict("相同请求 ID 对应不同回答，请刷新页面后重试");return publicView(s);}
        if(!s.path("status").asText().equals("IN_PROGRESS")||!s.path("pendingAction").asText().isEmpty())throw ApiException.conflict("当前面试不能提交回答，请先重试未完成生成或查看报告");sameProvider(s);
        JsonNode last=s.path("messages").get(s.path("messages").size()-1);if(!last.path("id").asText().equals(expected)||!last.path("role").asText().equals("ASSISTANT"))throw ApiException.conflict("该问题已更新，请刷新页面后回答当前问题");
        int used=0;for(JsonNode m:s.path("messages"))if(m.path("role").asText().equals("USER"))used+=m.path("content").asText().length();if(used+content.length()>60000)throw ApiException.bad("本场回答总长度达到 60000 字符，请缩短回答或结束本场面试");
        ((ObjectNode)s.path("requests")).putObject(requestId).put("content",content).put("expectedMessageId",expected);
        message(s,"USER",content,s.path("followUpCount").asInt()>0);s.put("pendingAction","INTERVIEW").put("error","");store.saveSession(s);advance(s);return publicView(s);
    }
    void advance(ObjectNode s) {
        ObjectNode before=s.deepCopy();
        boolean finish;
        try {
            ObjectNode decision=llm.call("interviewer",input(s));
            FocusedInterviewOutputValidator.decision(decision);
            CodeExplorationService.validateQuestionPresentation(decision,s.path("sourceCode"));
            String action=decision.path("action").asText();
            int index=s.path("questionIndex").asInt(),follow=s.path("followUpCount").asInt(),count=s.path("totalQuestions").asInt();
            if(action.equals("FOLLOW_UP")&&follow>=2)action=index+1<count?"NEXT":"END";
            finish=action.equals("END")||action.equals("NEXT")&&index+1>=count;
            if(!finish) {
                if(action.equals("FOLLOW_UP")) {
                    s.put("followUpCount",follow+1).put("currentTopic",decision.path("topic").asText());
                    message(s,"ASSISTANT",decision.path("question").asText(),true);codeReferences(s,decision);
                } else {
                    JsonNode next=s.path("plan").get(index+1);
                    String question=decision.path("action").asText().equals("NEXT")?decision.path("question").asText():next.path("question").asText();
                    s.put("questionIndex",index+1).put("followUpCount",0).put("currentTopic",next.path("topic").asText());
                    message(s,"ASSISTANT",question,false);codeReferences(s,decision.path("action").asText().equals("NEXT")?decision:next);
                }
                s.put("status","IN_PROGRESS").put("pendingAction","").put("error","");
            }
        } catch(RuntimeException e) {
            restore(s,before);
            failed(s,modelFailure(e),"INTERRUPTED");
            return;
        }
        // 存储失败向上传播，不能伪装为模型错误或已成功保存。
        if(finish)complete(s);else store.saveSession(s);
    }
    public synchronized ObjectNode retry(String id){ObjectNode s=activeSession(id);sameProvider(s);if(!List.of("FAILED","INTERRUPTED","CREATED").contains(s.path("status").asText()))throw ApiException.conflict("当前没有可重试的面试生成");s.put("error","");String pending=s.path("pendingAction").asText();if(pending.equals("PLAN"))plan(s);else if(pending.equals("INTERVIEW"))advance(s);else throw ApiException.conflict("没有待恢复的生成任务");return publicView(s);}
    public synchronized ObjectNode finish(String id){ObjectNode s=activeSession(id);if(s.path("status").asText().equals("COMPLETED"))return publicView(s);sameProvider(s);complete(s);return publicView(s);}
    void complete(ObjectNode s){s.put("status","COMPLETED").put("endedAt",now()).put("pendingAction","").put("error","");store.saveSession(s);try{generateReport(s);}catch(ApiException ignored){}}
    public synchronized ObjectNode report(String id,boolean generate){ObjectNode s=activeSession(id);if(!s.path("status").asText().equals("COMPLETED"))throw ApiException.conflict("面试结束后才能生成报告");if(s.path("reportStatus").asText().equals("READY"))return store.report(id);if(!generate)throw ApiException.conflict("报告尚未生成或生成失败，请重试生成报告");sameProvider(s);return generateReport(s);}
    ObjectNode generateReport(ObjectNode s) {
        s.put("reportStatus","GENERATING");
        store.saveSession(s);
        ObjectNode report;
        try {
            boolean answered=false;
            for(JsonNode m:s.path("messages"))if(m.path("role").asText().equals("USER"))answered=true;
            report=answered?llm.call("evaluator",input(s)):emptyReport();
            if(answered)FocusedInterviewOutputValidator.report(report,s);
            if(s.path("experienceVersion").asInt()==1){var evidence=new ProjectEvidenceService(json);var matrix=answered?evidence.normalize(report.path("matrix"),s.path("messages"),s.path("mainProject")):evidence.uncovered(s.path("mainProject"));if(matrix!=null)report.set("matrix",matrix);else report.remove("matrix");}
            for(JsonNode q:report.path("questions")) {
                for(JsonNode m:s.path("messages")) {
                    if(m.path("role").asText().equals("ASSISTANT")&&!m.path("followUp").asBoolean()&&m.path("questionIndex").asInt()==q.path("questionIndex").asInt()) {
                        ((ObjectNode)q).put("question",m.path("content").asText());
                        break;
                    }
                }
            }
        } catch(RuntimeException e) {
            ApiException failure=modelFailure(e);
            s.put("reportStatus","FAILED").put("error",failure.getMessage());
            store.saveSession(s);
            throw failure;
        }
        CodeExplorationService.attachReport(report,s);
        report.put("sessionId",s.path("id").asText()).put("mock",s.path("mock").asBoolean()).put("createdAt",now());
        s.put("reportStatus","READY").put("error","");
        store.atomic(()->{store.saveReport(s.path("id").asText(),report);store.saveSession(s);return null;});
        return report;
    }
    ObjectNode emptyReport(){ObjectNode r=json.createObjectNode().put("summary","本次面试没有提交任何回答，无法评价技术能力。请重新开始并完成至少一道回答。");ArrayNode dims=r.putArray("dimensions");for(String name:FocusedInterviewOutputValidator.DIMENSIONS)dims.addObject().put("name",name).put("score",0).put("reason","没有回答，无法评价；0 仅表示没有可评分证据，并非能力结论。");r.putArray("questions");r.putArray("weaknesses");r.putArray("suggestions").add("选择一道问题，先完整表达你的思路，再查看复盘。");return r;}
    public synchronized ObjectNode predict(JsonNode body){String resumeId=required(body,"resumeId",100),role=optional(body,"role","大模型应用 / Agent 开发工程师",120),jd=optional(body,"jd","",10000);Material m=store.material(resumeId);if(!m.kind().equals("RESUME"))throw ApiException.bad("请选择简历资料");ensureContent(m);Context context=materials.context(m,jd);
        ObjectNode input=json.createObjectNode().put("context",context.text()).put("role",role).put("jd",jd);ObjectNode p=llm.call("predictor",input);FocusedInterviewOutputValidator.prediction(p,context.text());p.put("id",id()).put("resumeId",resumeId).put("role",role).put("createdAt",now()).put("mock",llm.isMock()).put("context",context.text()).put("contextTruncated",context.truncated());for(JsonNode q:p.path("questions"))((ObjectNode)q).put("id",id());store.savePrediction(p);return publicPrediction(p);
    }
    ObjectNode publicPrediction(ObjectNode p){ObjectNode result=p.deepCopy();result.remove("context");return result;}
    public List<ObjectNode> predictions(String resumeId){return store.predictions(resumeId).stream().map(this::publicPrediction).toList();}
}
