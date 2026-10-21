package cn.interview.service.training;

import cn.interview.common.ContentHash;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import static cn.interview.common.Models.*;

@Service
public class ReviewService {
    final TrainingStore db; final OperationService ops; final JsonNode catalog;
    ReviewService(TrainingStore db,OperationService ops) throws Exception {
        this.db=db;this.ops=ops;
        catalog=db.json().readTree(new ClassPathResource("training/skill-catalog.json").getContentAsString(StandardCharsets.UTF_8));
    }
    public ObjectNode preview(String sessionId) {
        db.enabled();var session=db.database().session(sessionId);
        if(!session.path("status").asText().equals("COMPLETED")||!session.path("reportStatus").asText().equals("READY"))
            throw RecoveryException.conflict("V2_SOURCE_NOT_READY","请先完成面试并生成报告，再整理复习项。");
        var report=db.database().report(sessionId);var result=db.object().put("sessionId",sessionId).put("reportHash",db.hash(report));
        var items=result.putArray("items");
        for(JsonNode question:report.path("questions")) {
            int index=question.path("questionIndex").asInt(-1);if(index<0)throw RecoveryException.invalid("旧报告的题目编号缺失，无法导入");
            var dialogue=db.array();boolean answered=false;
            for(JsonNode m:session.path("messages")) if(m.path("questionIndex").asInt(-1)==index) {
                dialogue.add(m.deepCopy());if(m.path("role").asText().equals("USER"))answered=true;
            }
            if(!answered)continue;
            var item=db.object().put("sourceSessionId",sessionId).put("questionIndex",index).put("question",question.path("question").asText())
                    .put("sourcePrecision","QUESTION_ONLY").put("sourceLimit","旧报告仅有整题依据，不能视为逐条精确归因")
                    .put("role",session.path("role").asText()).put("level",session.path("level").asText()).put("mock",session.path("mock").asBoolean())
                    .put("reportHash",result.path("reportHash").asText());
            item.set("dialogue",dialogue);item.set("sourceReport",question.deepCopy());item.putArray("skillIds");
            var findings=item.putArray("findings");
            for(String kind:List.of("incorrect","missing","uncertain")) {
                if(!question.path(kind).isArray())throw RecoveryException.invalid("旧报告缺少分类，请查看原报告；没有补造结论");
                int position=0;
                for(JsonNode text:question.path(kind)) {
                    if(!text.isTextual()||text.asText().isBlank())throw RecoveryException.invalid("报告结论格式不完整，无法导入");
                    String key=result.path("reportHash").asText()+"|"+index+"|"+kind+"|"+position+++"|"+text.asText();
                    findings.addObject().put("id",ContentHash.sha(key)).put("kind",kind).put("text",text.asText()).put("confirmed",false).put("disputed",false);
                }
            }
            boolean existing=db.reviewItemCount(sessionId,index)>0;
            if(!findings.isEmpty()||existing)items.add(item);
        }
        return result;
    }
    public ObjectNode importReport(JsonNode body) {
        return ops.write("REVIEW_IMPORT","LOCAL_USER",body,()->{
            String session=required(body,"sessionId",100);var preview=preview(session);
            if(!required(body,"expectedReportHash",100).equals(preview.path("reportHash").asText()))
                throw RecoveryException.conflict("V2_SOURCE_CHANGED","报告已更新，请重新查看候选后导入。");
            var result=db.object();var items=result.putArray("items");
            for(JsonNode source:preview.path("items")) {
                var ids=db.reviewItemIds(session,source.path("questionIndex").asInt());
                if(ids.isEmpty()) {
                    var item=((ObjectNode)source).deepCopy().put("status","PENDING_CONFIRMATION");item.putNull("nextReviewAt");item.putArray("sourceVersions");
                    items.add(db.insert("v2_review_items",item));
                } else {
                    var current=db.getStored("v2_review_items",ids.getFirst());
                    if(current.hasNonNull("deletedAt"))continue;
                    if(!current.path("reportHash").asText().equals(source.path("reportHash").asText())) {
                        var prior=current.deepCopy();prior.remove("sourceVersions");((ArrayNode)current.path("sourceVersions")).add(prior);
                        current.setAll((ObjectNode)source);current.put("confirmationPending",true);
                        if(!current.path("status").asText().equals("ARCHIVED"))current.put("status","PENDING_CONFIRMATION");
                        current.putNull("nextReviewAt");db.save("v2_review_items",current,current.path("revision").asInt());
                    }
                    items.add(current);
                }
            }
            return result;
        });
    }
    ArrayNode skillIds(JsonNode input) {
        if(!input.isArray()||input.size()>12)throw RecoveryException.invalid("请选择目录内的技能标签，最多12个");
        var result=db.array();var seen=new HashSet<String>();
        for(JsonNode value:input) {
            String mapped=null;
            for(JsonNode skill:catalog.path("skills")) {
                if(skill.path("id").asText().equals(value.asText())||skill.path("name").asText().equals(value.asText()))mapped=skill.path("id").asText();
                for(JsonNode alias:skill.path("aliases"))if(alias.asText().equalsIgnoreCase(value.asText()))mapped=skill.path("id").asText();
            }
            if(mapped==null)throw RecoveryException.invalid("未知技能标签，保留未归类或选择已有目录");
            if(seen.add(mapped))result.add(mapped);
        }
        return result;
    }
    void event(ObjectNode item,String type,JsonNode details,String key) {
        var skills=item.path("skillIds");var ids=new ArrayList<String>();skills.forEach(s->ids.add(s.asText()));if(ids.isEmpty())ids.add(null);
        for(String skill:ids) {
            var row=db.object().put("reviewItemId",item.path("id").asText()).put("sourceKey",key+":"+skill).put("eventType",type);
            if(skill==null)row.putNull("skillId");else row.put("skillId",skill);
            row.put("sourceReportHash",item.path("reportHash").asText());row.putNull("attemptId");row.set("details",details.deepCopy());var observed=row.putArray("observedFindingIds");for(JsonNode f:item.path("findings"))if(f.path("confirmed").asBoolean())observed.add(f.path("id").asText());row.put("mock",item.path("mock").asBoolean());
            db.insert("v2_skill_events",row);
        }
    }
    public ObjectNode action(String id,JsonNode body) {
        return ops.write("REVIEW_ACTION",id,body,()->{
            var item=db.get("v2_review_items",id);db.expect(item,body);int revision=item.path("revision").asInt();
            String action=required(body,"action",40),state=item.path("status").asText();
            if(state.equals("ARCHIVED")&&!Set.of("RESTORE","CONFIRM_EVIDENCE").contains(action))throw RecoveryException.conflict("V2_INVALID_STATE","请先恢复归档项。");
            switch(action) {
                case "CONFIRM" -> {
                    if(!Set.of("PENDING_CONFIRMATION","RESOLVED","DUE","WAITING_EVIDENCE").contains(state))throw RecoveryException.conflict("V2_INVALID_STATE","请先返回队列，再确认新发现。");
                    if(item.path("findings").isEmpty())throw RecoveryException.conflict("V2_NO_FINDINGS","这版报告没有待练发现，请核对来源版本；可归档保留历史。");
                    item.set("skillIds",skillIds(body.path("skillIds")));boolean errors=false;
                    var selected=selectedFindings(item,body,false);boolean newlyConfirmed=false;
                    for(JsonNode f:item.path("findings")) {
                        if(selected.contains(f.path("id").asText())){newlyConfirmed|=!f.path("confirmed").asBoolean();((ObjectNode)f).put("confirmed",true);}
                        if(f.path("confirmed").asBoolean()&&!f.path("disputed").asBoolean()&&!f.path("kind").asText().equals("uncertain"))errors=true;
                    }
                    item.put("confirmationPending",false);
                    if(!state.equals("RESOLVED")||newlyConfirmed) {
                        item.put("status",errors?"DUE":"WAITING_EVIDENCE");if(errors)item.put("nextReviewAt",now());else item.putNull("nextReviewAt");
                    }
                }
                case "CLASSIFY" -> item.set("skillIds",skillIds(body.path("skillIds")));
                case "DISPUTE" -> {
                    String reason=required(body,"reason",1000);var selected=new HashSet<String>();body.path("findingIds").forEach(n->selected.add(n.asText()));
                    if(selected.isEmpty())throw RecoveryException.invalid("请选择具体结论");
                    for(JsonNode finding:item.path("findings"))if(selected.remove(finding.path("id").asText()))((ObjectNode)finding).put("disputed",true).put("disputeReason",reason);
                    if(!selected.isEmpty())throw RecoveryException.invalid("所选结论不属于当前来源版本");
                    if(item.has("lastEvidence"))item.put("currentSupportWithdrawn",true);
                }
                case "START" -> {requireState(state,"DUE");item.put("status","IN_PROGRESS");}
                case "RETURN" -> {requireState(state,"IN_PROGRESS");item.put("status","DUE");}
                case "RESCHEDULE","RESOLVE" -> {
                    requireState(state,"IN_PROGRESS");var evidence=validEvidence(item,body);storeEvidence(item,evidence);
                    if(action.equals("RESOLVE"))item.put("status","RESOLVED").putNull("nextReviewAt").put("resolutionReason","USER_ENDED_ROUND");
                    else {int days=body.path("intervalDays").asInt();if(days!=1&&days!=3)throw RecoveryException.invalid("复习间隔只能为1或3天");item.put("status","DUE").put("nextReviewAt",Instant.now().plus(days,ChronoUnit.DAYS).toString());}
                }
                case "CONFIRM_EVIDENCE","RESOLVE_EVIDENCE" -> {
                    var evidence=validEvidence(item,body);storeEvidence(item,evidence);
                    boolean gaps=evidence.path("hasGaps").asBoolean(),support=evidence.path("hasSupport").asBoolean();
                    if(action.equals("RESOLVE_EVIDENCE")) {
                        requireState(state,"WAITING_EVIDENCE");
                        boolean unhandled=false;for(JsonNode finding:item.path("findings"))if(finding.path("confirmed").asBoolean()&&!finding.path("disputed").asBoolean()&&(!finding.path("kind").asText().equals("uncertain")||!contains(evidence.path("supportedFindingIds"),finding.path("id").asText())))unhandled=true;
                        if(!support||gaps||unhandled)throw RecoveryException.conflict("V2_EVIDENCE_INSUFFICIENT","仍有未知或未处理发现，不能结束待证事项。");
                        item.put("status","RESOLVED").put("resolutionReason","SUPPORT_CONFIRMED").putNull("nextReviewAt");
                    } else if(gaps) {
                        if(state.equals("ARCHIVED"))item.put("newEvidenceAvailable",true);else item.put("status","DUE").put("nextReviewAt",now());
                    }
                }
                case "ARCHIVE" -> item.put("beforeArchive",state).put("status","ARCHIVED");
                case "RESTORE" -> {requireState(state,"ARCHIVED");item.put("status",item.path("confirmationPending").asBoolean()?"PENDING_CONFIRMATION":item.path("beforeArchive").asText("PENDING_CONFIRMATION"));}
                default -> throw RecoveryException.invalid("不支持的复习动作");
            }
            db.save("v2_review_items",item,revision);event(item,action,body,id+":"+required(body,"requestId",120));return item;
        });
    }
    void requireState(String actual,String expected) {if(!actual.equals(expected))throw RecoveryException.conflict("V2_INVALID_STATE","当前状态不允许此操作，请返回列表查看最新状态。");}
    static boolean contains(JsonNode values,String id){for(JsonNode v:values)if(v.asText().equals(id))return true;return false;}
    Set<String> selectedFindings(JsonNode item,JsonNode body,boolean confirmedOnly) {
        var valid=new HashSet<String>();for(JsonNode f:item.path("findings"))if(!f.path("disputed").asBoolean()&&(!confirmedOnly||f.path("confirmed").asBoolean()))valid.add(f.path("id").asText());
        if(!body.has("findingIds"))return valid;
        if(!body.path("findingIds").isArray()||body.path("findingIds").isEmpty())throw RecoveryException.invalid("请选择至少一条当前有效发现");
        var selected=new HashSet<String>();for(JsonNode f:body.path("findingIds"))if(!valid.contains(f.asText())||!selected.add(f.asText()))throw RecoveryException.invalid("所选发现不存在、重复、未确认或有争议");return selected;
    }
    ObjectNode validEvidence(ObjectNode item,JsonNode body) {
        var selectedFindings=selectedFindings(item,body,true);
        if(selectedFindings.isEmpty()||!body.path("bindings").isArray()||body.path("bindings").size()!=selectedFindings.size())throw RecoveryException.invalid("请逐条选择发现并绑定对应检查点或报告结论，确认实际覆盖范围");
        var result=db.object();var supported=result.putArray("supportedFindingIds");var statuses=result.putArray("findingStatuses");boolean gaps=false,support=false;
        JsonNode checks=null,reportQuestion=null;Set<String> allowed=null;
        if(body.hasNonNull("attemptId")) {
            String id=required(body,"attemptId",100);var attempt=db.get("v2_practice_attempts",id);
            var source=db.get("v2_practice_cases",attempt.path("caseId").asText());
            if(!attempt.path("status").asText().equals("READY")||!source.path("sourceKind").asText().equals("SESSION")||
                    !source.path("sourceId").asText().equals(item.path("sourceSessionId").asText())||source.path("questionIndex").asInt(-1)!=item.path("questionIndex").asInt()||source.path("mock").asBoolean()!=item.path("mock").asBoolean())
                throw RecoveryException.conflict("V2_EVIDENCE_INSUFFICIENT","尝试尚未就绪、与原题无关或模型模式不同。");
            var group=db.get("v2_practice_evaluation_groups",attempt.path("primaryGroupId").asText());
            if(!group.path("status").asText().equals("READY"))throw RecoveryException.conflict("V2_EVIDENCE_INSUFFICIENT","评估尚未完成");
            checks=group.path("result").path("checks");
            result.put("attemptId",id).put("evaluationGroupId",group.path("id").asText());
        } else {
            var link=db.get("v2_training_links",required(body,"trainingLinkId",100));
            if(!link.path("sourceKind").asText().equals("REVIEW")||!link.path("sourceId").asText().equals(item.path("id").asText())||link.path("mock").asBoolean()!=item.path("mock").asBoolean())
                throw RecoveryException.invalid("训练来源不对应当前复习项");
            if(!link.path("sourceReportHash").asText().equals(item.path("reportHash").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","原报告版本已变化，请重新建立训练范围");
            allowed=new HashSet<>();for(JsonNode f:link.path("findingIds"))allowed.add(f.asText());
            var session=db.database().session(link.path("sessionId").asText());
            if(!session.path("status").asText().equals("COMPLETED")||!session.path("reportStatus").asText().equals("READY"))throw RecoveryException.conflict("V2_EVIDENCE_INSUFFICIENT","请先完成练习及报告，再确认对应证据。");
            var report=db.database().report(session.path("id").asText());
            int index=body.path("evidenceQuestionIndex").asInt(-1);JsonNode selected=null;for(JsonNode q:report.path("questions"))if(q.path("questionIndex").asInt()==index)selected=q;
            if(selected==null)throw RecoveryException.invalid("请选择本场报告中实际覆盖该目标的一题，不能关联整场结论");
            reportQuestion=selected;
            result.put("trainingLinkId",link.path("id").asText()).put("evidenceQuestionIndex",index).put("reportHash",db.hash(report));result.set("reportEvidence",selected.deepCopy());
        }
        var seen=new HashSet<String>();
        for(JsonNode binding:body.path("bindings")) {
            String fid=required(binding,"findingId",100);if(!selectedFindings.contains(fid)||!seen.add(fid)||(allowed!=null&&!allowed.contains(fid)))throw RecoveryException.invalid("证据绑定超出本次有效训练范围");
            if(!binding.path("coverageConfirmed").asBoolean())throw RecoveryException.invalid("请明确确认该证据覆盖了所选发现，不能仅凭整题正确结论自动推断");
            required(binding,"reason",1000);String verdict;
            if(checks!=null) {
                String cid=required(binding,"checkId",100);JsonNode match=null;for(JsonNode check:checks)if(check.path("checkId").asText().equals(cid))match=check;
                if(match==null)throw RecoveryException.invalid("检查点不属于所选评估");verdict=match.path("status").asText();
            }else {
                String kind=choice(required(binding,"reportKind",30),"correct","incorrect","missing","uncertain"),text=required(binding,"reportText",4000);
                if(!contains(reportQuestion.path(kind),text))throw RecoveryException.invalid("结论不属于所选报告题目");
                verdict=kind.equals("correct")?"MET":kind.equals("uncertain")?"UNCERTAIN":"GAP";
            }
            statuses.addObject().put("findingId",fid).put("status",verdict.equals("MET")?"SUPPORTED":verdict.equals("GAP")?"GAP":"UNVERIFIED");
            if(verdict.equals("MET")){support=true;supported.add(fid);}if(verdict.equals("GAP"))gaps=true;
        }
        result.set("bindings",body.path("bindings").deepCopy());result.put("sourceReportHash",item.path("reportHash").asText());
        result.put("hasGaps",gaps).put("hasSupport",support).put("confirmedAt",now());return result;
    }
    void storeEvidence(ObjectNode item,ObjectNode evidence){
        item.set("lastEvidence",evidence);for(JsonNode status:evidence.path("findingStatuses"))for(JsonNode finding:item.path("findings"))if(finding.path("id").equals(status.path("findingId"))){var current=((ObjectNode)finding).putObject("currentEvidence").put("status",status.path("status").asText()).put("sourceReportHash",item.path("reportHash").asText()).put("confirmedAt",evidence.path("confirmedAt").asText());for(String key:List.of("attemptId","evaluationGroupId","trainingLinkId","reportHash","evidenceQuestionIndex"))if(evidence.has(key))current.set(key,evidence.path(key).deepCopy());for(JsonNode binding:evidence.path("bindings"))if(binding.path("findingId").equals(status.path("findingId")))current.set("binding",binding.deepCopy());}
        item.remove("currentSupportWithdrawn");
    }
    public ObjectNode list(String cursor,int limit,String status) {
        var out=db.reviewPage(cursor,limit,status);
        for(JsonNode n:out.path("items")) {var item=(ObjectNode)n;attachTraining(item);item.remove(List.of("dialogue","sourceReport","sourceVersions","lastEvidence","evidenceHistory"));}
        return out;
    }
    /** 只读关联练习，供界面恢复作答；不改变复习状态或自动确认能力证据。 */
    void attachTraining(ObjectNode item) {
        var ids=db.ids("v2_training_links","source_id",item.path("id").asText());
        for(String id:ids.reversed()) {
            var link=db.get("v2_training_links",id);
            if(!link.path("sourceKind").asText().equals("REVIEW")||!link.path("sourceReportHash").equals(item.path("reportHash"))||link.path("mock").asBoolean()!=item.path("mock").asBoolean())continue;
            boolean valid=!link.path("findingIds").isEmpty();
            for(JsonNode selected:link.path("findingIds")) {
                boolean found=false;
                for(JsonNode finding:item.path("findings"))if(finding.path("id").equals(selected)&&finding.path("confirmed").asBoolean()&&!finding.path("disputed").asBoolean())found=true;
                if(!found)valid=false;
            }
            if(!valid)continue;
            if(!link.hasNonNull("sessionId")&&(item.has("latestTraining")||link.path("sourceRevision").asInt()!=item.path("revision").asInt()))continue;
            var summary=db.object();for(String key:List.of("id","sessionId","sourceRevision","findingIds"))summary.set(key,link.path(key).deepCopy());
            if(link.hasNonNull("sessionId")) {
                var session=db.database().session(link.path("sessionId").asText());
                summary.set("sessionStatus",session.path("status"));summary.set("reportStatus",session.path("reportStatus"));
                summary.set("endedAt",session.path("endedAt"));summary.set("updatedAt",session.path("updatedAt"));
            }
            if(!item.has("latestTraining"))item.set("latestTraining",summary);
            if(!summary.path("sessionStatus").asText().equals("COMPLETED")) {item.set("latestTraining",summary);return;}
        }
    }
    public ObjectNode skills() {
        db.enabled();var result=db.object();result.set("catalog",catalog);var items=result.putArray("items");
        for(JsonNode skill:catalog.path("skills")) {
            var summary=((ObjectNode)skill).deepCopy();String skillId=skill.path("id").asText();
            summary.put("eventCount",db.skillEventCount(skillId)).put("selfRating","UNRATED");
            var ratings=db.latestSelfRating(skillId);
            if(!ratings.isEmpty())summary.put("selfRating",ratings.getFirst().path("details").path("value").asText());
            items.add(summary);
        }
        result.put("notice","自评与模型观察分开，完成本轮不表示能力提升。");return result;
    }
    public ObjectNode rating(String skill,JsonNode body) {
        var selected=skillIds(db.array().add(skill));
        return ops.write("SELF_RATING",selected.get(0).asText(),body,()->{
            choice(required(body,"value",40),"UNRATED","NEEDS_PRACTICE","BASIC_UNDERSTANDING","SELF_MASTERED");
            optional(body,"note","",1000);
            var row=db.object().put("skillId",selected.get(0).asText()).put("eventType","SELF_RATING").put("sourceKey","rating:"+skill+":"+required(body,"requestId",120));
            row.putNull("reviewItemId");row.putNull("attemptId");row.set("details",body.deepCopy());return db.insert("v2_skill_events",row);
        });
    }

    public ObjectNode source(String sessionId,int questionIndex) {db.enabled();var ids=db.reviewItemIds(sessionId,questionIndex).stream().filter(id->!db.getStored("v2_review_items",id).hasNonNull("deletedAt")).toList();var out=db.object();if(ids.isEmpty())out.putNull("id");else out.put("id",ids.getFirst());return out;}

    public ObjectNode get(String id) {var item=db.get("v2_review_items",id);attachTraining(item);return item;}

    public ObjectNode history(String id,String cursor,int limit) {return ops.history("v2_review_items",id,cursor,limit);}

    public ObjectNode historyDetail(String id,String operationId) {return ops.historyDetail("v2_review_items",id,operationId);}

    public ObjectNode events(String id,String cursor,int limit) {
        var canonical=skillIds(db.array().add(id)).get(0).asText();
        return db.page("v2_skill_events",cursor,limit,null,"skill_id",canonical);
    }

    public ObjectNode operation(String id) {return ops.safe(id);}

}
