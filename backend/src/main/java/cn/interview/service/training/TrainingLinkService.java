package cn.interview.service.training;

import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;
import cn.interview.service.interview.FocusedInterviewService;
import cn.interview.task.ModelCapacity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

import static cn.interview.common.Models.*;

@Service
public class TrainingLinkService {
    final TrainingStore db;final OperationService ops;final ModelCapacity capacity;final ReviewService reviews;final JdService jdService;
    TrainingLinkService(TrainingStore db,OperationService ops,ModelCapacity capacity,ReviewService reviews,JdService jdService) {this.db=db;this.ops=ops;this.capacity=capacity;this.reviews=reviews;this.jdService=jdService;}

    public ObjectNode link(JsonNode body) {
        return ops.write("TRAINING_LINK","LOCAL_USER",body,()->{
            String kind=choice(required(body,"sourceKind",20),"REVIEW","JD"),sourceId=required(body,"sourceId",100);
            var source=db.get(kind.equals("REVIEW")?"v2_review_items":"v2_jd_diagnostics",sourceId);
            if(kind.equals("REVIEW")&&!Set.of("DUE","IN_PROGRESS","WAITING_EVIDENCE").contains(source.path("status").asText())||
                    kind.equals("JD")&&!source.path("status").asText().equals("CONFIRMED"))throw RecoveryException.conflict("V2_SOURCE_NOT_READY","请先确认来源目标，再开始专项练习。");
            var link=db.object().put("sourceKind",kind).put("sourceId",sourceId).put("mock",source.path("mock").asBoolean())
                    .put("sourceRevision",source.path("revision").asInt()).put("sourceReportHash",source.path("reportHash").asText());
            link.putNull("sessionId");var selected=link.putArray("findingIds");
            if(kind.equals("REVIEW")) {var chosen=reviews.selectedFindings(source,body,true);if(chosen.isEmpty())throw RecoveryException.invalid("请先确认至少一条训练发现");chosen.stream().sorted().forEach(selected::add);}
            if(kind.equals("JD")){jdService.inputs.check(source.path("manifest"));var chosen=jdService.selected(source,body.path("requirementIds"),true);var ids=link.putArray("requirementIds");chosen.stream().sorted().forEach(ids::add);}
            if(kind.equals("JD")&&body.hasNonNull("sprintId")){var sprint=db.get("v2_sprints",required(body,"sprintId",100));var action=jdService.findAction(sprint,required(body,"actionId",100));if(!sprint.path("status").asText().equals("ACTIVE")||action.path("obsolete").asBoolean()||!Set.of("TODO","IN_PROGRESS").contains(action.path("status").asText())||!action.path("sourceDiagnosticId").asText(sprint.path("diagnosticId").asText()).equals(sourceId)||!ReviewService.contains(link.path("requirementIds"),action.path("requirementId").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","清单行动已变化，请重新选择");link.put("sprintId",sprint.path("id").asText()).put("actionId",action.path("id").asText());}
            return db.insert("v2_training_links",link);
        });
    }
    public ObjectNode create(FocusedInterviewService service,JsonNode body) {
        db.enabled();String linkId=required(body,"trainingLinkId",100);
        var previous=ops.replay("LINKED_SESSION",linkId,body);
        if(previous!=null)return service.publicView(db.database().session(previous.path("resourceId").asText()));
        var configuration=((ObjectNode)body).deepCopy();configuration.remove("requestId");String configHash=db.hash(configuration);
        var link=db.get("v2_training_links",linkId);
        if(link.hasNonNull("sessionId")) {
            if(!configHash.equals(link.path("configurationHash").asText()))throw RecoveryException.conflict("V2_REQUEST_CONFLICT","该来源已创建另一配置的面试，请返回原场次或建立新的训练来源。");
            return service.publicView(db.database().session(link.path("sessionId").asText()));
        }
        choice(optional(body,"mode","QUICK",20),"QUICK");String role=optional(body,"role","大模型应用 / Agent 开发工程师",120);
        String level=choice(optional(body,"level","JUNIOR",20),"INTERN","JUNIOR","SENIOR"),style=choice(optional(body,"style","NORMAL",20),"NORMAL","PRESSURE");
        int duration=body.path("durationMinutes").isMissingNode()?10:body.path("durationMinutes").asInt(-1);
        if(body.has("durationMinutes")&&!body.path("durationMinutes").isIntegralNumber()||!List.of(5,10,20).contains(duration))throw RecoveryException.invalid("目标时长必须为5、10或20分钟");
        String focus=optional(body,"focus","",2000),jd=optional(body,"jd","",10000);
        if(link.path("mock").asBoolean()!=service.isMock())throw RecoveryException.conflict("V2_MODEL_MODE_CHANGED","训练来源与当前模型模式不同，请切回对应模式。");
        if(link.path("sourceKind").asText().equals("REVIEW")) {
            var source=db.get("v2_review_items",link.path("sourceId").asText());
            if(source.path("status").asText().equals("ARCHIVED")||!source.path("reportHash").asText().equals(link.path("sourceReportHash").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","来源已更新或归档，请重新选择。");
            for(JsonNode id:link.path("findingIds"))for(JsonNode finding:source.path("findings"))if(finding.path("id").asText().equals(id.asText())&&finding.path("disputed").asBoolean())throw RecoveryException.conflict("V2_EVIDENCE_DISPUTED","所选结论已存在异议，请重新确认训练范围。");
        }
        try(var ignored=capacity.acquire()) {
            ObjectNode session=db.atomic(()->{
                var replay=ops.replay("LINKED_SESSION",linkId,body);if(replay!=null)return db.database().session(replay.path("resourceId").asText());
                var currentLink=db.get("v2_training_links",linkId);
                if(currentLink.hasNonNull("sessionId"))throw RecoveryException.conflict("V2_RESOURCE_BUSY","此来源已在另一页面开始面试。");
                validateSource(currentLink);
                String now=now();var s=db.object().put("schemaVersion",1).put("id",id()).put("status","CREATED").put("mode","QUICK")
                        .put("role",role).put("level",level).put("style",style).put("durationMinutes",duration)
                        .put("createdAt",now).put("updatedAt",now).put("questionIndex",0).put("followUpCount",0).put("totalQuestions",duration==5?3:duration==10?5:8)
                        .put("currentTopic","").put("mock",service.isMock()).put("error","").put("pendingAction","PLAN").put("reportStatus","NONE")
                        .put("contextTruncated",false).put("context",jd).put("jd",jd).put("focus",focus);
                s.putNull("endedAt");s.putArray("messages");s.putObject("requests");s.putArray("plan");db.database().saveSession(s);
                currentLink.put("sessionId",s.path("id").asText()).put("configurationHash",configHash);db.save("v2_training_links",currentLink,currentLink.path("revision").asInt());
                ops.record("LINKED_SESSION",linkId,body,s,"SUCCEEDED");return s;
            });
            // 规划、失败保存与重启恢复沿用 原有功能；同一个来源不会再次规划。
            return service.startLinkedSession(session);
        }
    }
    void validateSource(ObjectNode link) {
        if(link.path("sourceKind").asText().equals("JD")){
            var source=db.get("v2_jd_diagnostics",link.path("sourceId").asText());if(!source.path("status").asText().equals("CONFIRMED")||source.path("revision").asInt()!=link.path("sourceRevision").asInt())throw RecoveryException.conflict("V2_SOURCE_CHANGED","诊断要求已变化，请重新选择");jdService.inputs.check(source.path("manifest"));jdService.selected(source,link.path("requirementIds"),true);return;
        }
        var source=db.get("v2_review_items",link.path("sourceId").asText());
        if(source.path("revision").asInt()!=link.path("sourceRevision").asInt()||!source.path("reportHash").asText().equals(link.path("sourceReportHash").asText())||!Set.of("DUE","IN_PROGRESS","WAITING_EVIDENCE").contains(source.path("status").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","训练来源状态或版本已变化，请重新选择范围。");
        var requested=db.object();requested.set("findingIds",link.path("findingIds"));reviews.selectedFindings(source,requested,true);
    }

    public ArrayNode bySession(String sessionId) {db.enabled();var out=db.array();for(String id:db.ids("v2_training_links","session_id",sessionId)){var row=db.get("v2_training_links",id);var dto=db.object();for(String key:java.util.List.of("id","sourceKind","sourceId","sprintId","actionId","sessionId"))if(row.has(key))dto.set(key,row.path(key));out.add(dto);}return out;}

    public ObjectNode get(String id) {return db.get("v2_training_links",id);}

}
