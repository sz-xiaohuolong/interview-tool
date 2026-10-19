package cn.interview.service.training;

import cn.interview.common.ContentHash;
import cn.interview.exception.ApiException;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;

import static cn.interview.common.Models.*;

/** 两个固定输入清单：预览不调用模型，执行只发送已确认快照。 */
@Service
public class InputPreparationService {
    final TrainingStore db;final OperationService ops;
    InputPreparationService(TrainingStore db,OperationService ops){this.db=db;this.ops=ops;}
    ObjectNode prepareJd(JsonNode body){return prepare("JD_INPUT","LOCAL_USER",body,null);}
    ObjectNode prepare(String kind,String scope,JsonNode body,JsonNode story){return ops.write(kind,scope,body,()->{
        var result=db.object().put("id",id()).put("kind",kind);result.put("manifestId",result.path("id").asText());var snapshots=result.putArray("snapshots");var dependencies=result.putArray("dependencies");var model=result.putObject("modelInput");
        if(story==null){String jd;
            if(body.hasNonNull("jdMaterialId")){var material=db.database().material(required(body,"jdMaterialId",100));if(!material.kind().equals("JD"))throw RecoveryException.invalid("请选择 JD 类型资料");jd=material.content();for(var document:material.documents())jd+="\n"+document.text();dependency(dependencies,"MATERIAL",material.id(),db.json().valueToTree(material));model.put("jdSourceIdentity",material.id());}
            else{jd=PracticeService.text(body,"jdText",10000,true);model.put("jdSourceIdentity","MANUAL");}
            if(jd.isBlank()||jd.length()>10000)throw RecoveryException.invalid("JD 必须包含1～10000字符，请缩小输入范围");
            model.put("jdText",jd);snapshot(snapshots,"jd",jd,"JD",false,null,null);
        }else{dependency(dependencies,"STORY",story.path("id").asText(),story);model.put("templateKind",story.path("templateKind").asText());model.set("templateFields",story.path("templateFields").deepCopy());var acceptedFields=model.putObject("userFields");
            for(var it=story.path("fields").fields();it.hasNext();){var f=it.next();if(f.getValue().path("originType").asText().equals("USER_ASSERTION")&&!f.getValue().path("text").asText().isBlank()){acceptedFields.set(f.getKey(),f.getValue().deepCopy());snapshot(snapshots,"user:"+f.getKey(),f.getValue().path("text").asText(),"USER_ASSERTION",false,null,null);}}
            var prior=db.get("v2_story_versions",story.path("currentVersionId").asText());if(!changed(db.object().set("dependencies",prior.path("dependencies")))){for(JsonNode old:prior.path("sourceSnapshots"))if(old.path("purpose").asText().equals("MATERIAL")){var copy=((ObjectNode)old).deepCopy();copy.put("id","history:"+old.path("id").asText());snapshots.add(copy);}for(JsonNode dep:prior.path("dependencies"))dependencies.add(dep.deepCopy());}
        }
        JsonNode materialIds=body.path("materialIds");if(!materialIds.isMissingNode()&&(!materialIds.isArray()||materialIds.size()>5))throw RecoveryException.invalid("最多选择5份资料");var seen=new HashSet<String>();int[] size={0};
        for(JsonNode value:materialIds){String mid=value.asText();if(!seen.add(mid))throw RecoveryException.invalid("资料不能重复选择");var material=db.database().material(mid);if(!Set.of("RESUME","PROJECT").contains(material.kind()))throw RecoveryException.invalid("证据材料只接受简历或项目；JD 不能充当能力证据");
            dependency(dependencies,"MATERIAL",mid,db.json().valueToTree(material));addMaterial(snapshots,body,"material:"+mid,material.content(),false,mid,null,size);
            for(var d:material.documents())addMaterial(snapshots,body,"document:"+d.id(),d.text(),d.truncated(),mid,d.id(),size);
        }
        var events=model.putArray("trainingEvents");JsonNode eventIds=body.path("trainingEventIds");if(!eventIds.isMissingNode()&&(!eventIds.isArray()||eventIds.size()>20))throw RecoveryException.invalid("最多选20条已确认训练观察");var selectedEvents=new HashSet<String>();
        for(JsonNode id:eventIds){if(!selectedEvents.add(id.asText()))throw RecoveryException.invalid("训练观察不能重复");var source=trainingSource(id.asText());dependency(dependencies,"TRAINING_EVENT",id.asText(),source);events.add(source.path("observation").deepCopy());}
        model.set("snapshots",snapshots.deepCopy());if(model.toString().length()>60000)throw RecoveryException.invalid("总输入超过60000字符，请缩小范围");
        result.put("manifestHash",db.hash(result));return result;
    });}
    void addMaterial(ArrayNode snapshots,JsonNode body,String id,String text,boolean truncated,String mid,String did,int[] size){
        if(text.isBlank())return;JsonNode range=body.path("snapshotRanges").path(id);int start=0,end=text.length();
        if(!range.isMissingNode()){if(!range.path("start").isIntegralNumber()||!range.path("end").isIntegralNumber())throw RecoveryException.invalid("资料范围需为整数 UTF-16 位置");start=range.path("start").asInt();end=range.path("end").asInt();}
        if(start<0||end<start||end>text.length()||start>0&&start<text.length()&&Character.isLowSurrogate(text.charAt(start))||end<text.length()&&Character.isLowSurrogate(text.charAt(end)))throw RecoveryException.invalid("资料截取范围无效");
        size[0]+=end-start;if(size[0]>30000)throw RecoveryException.invalid("材料总计超过30000字符，请减少资料或明确缩小片段范围");if(start==end)return;
        var row=snapshot(snapshots,id,text.substring(start,end),"MATERIAL",truncated||start!=0||end!=text.length(),mid,did);row.put("originalStart",start).put("originalEnd",end).put("originalLength",text.length());
    }
    ObjectNode snapshot(ArrayNode snapshots,String id,String text,String purpose,boolean truncated,String mid,String did){var row=snapshots.addObject().put("id",id).put("text",text).put("purpose",purpose).put("truncated",truncated).put("sha256",ContentHash.sha(text));if(mid!=null)row.put("materialId",mid);if(did!=null)row.put("documentId",did);return row;}
    void dependency(ArrayNode rows,String kind,String id,JsonNode source){rows.addObject().put("kind",kind).put("id",id).put("hash",db.hash(source));}
    ObjectNode trainingSource(String id){
        var event=db.get("v2_skill_events",id);
        if(!event.hasNonNull("reviewItemId")||!Set.of("CONFIRM","CONFIRM_EVIDENCE","RESOLVE_EVIDENCE","RESOLVE","RESCHEDULE").contains(event.path("eventType").asText()))throw RecoveryException.invalid("请选择已确认发现或已确认训练观察；归类、自评和异议不是支持证据");
        var review=db.get("v2_review_items",event.path("reviewItemId").asText());var observation=db.object().put("id",id).put("reviewItemId",review.path("id").asText()).put("mock",review.path("mock").asBoolean());
        if(!event.path("sourceReportHash").asText().equals(review.path("reportHash").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","训练观察来源已变，请重新确认当前版本");
        boolean gap=false,support=false;var findings=observation.putArray("findings");var findingIds=observation.putArray("findingIds");var texts=observation.putArray("texts");
        for(JsonNode finding:review.path("findings"))if(finding.path("confirmed").asBoolean()&&!finding.path("disputed").asBoolean()){
            String fid=finding.path("id").asText();if(event.path("details").has("findingIds")&&!ReviewService.contains(event.path("details").path("findingIds"),fid))continue;
            if(event.has("observedFindingIds")&&!ReviewService.contains(event.path("observedFindingIds"),fid))continue;
            String status=currentFindingStatus(review,finding);gap|=status.equals("GAP");support|=status.equals("SUPPORTED");findingIds.add(fid);texts.add(finding.path("text").asText());
            var f=findings.addObject().put("id",fid).put("text",finding.path("text").asText()).put("kind",finding.path("kind").asText()).put("status",status);
            if(finding.has("currentEvidence"))f.set("evidence",finding.path("currentEvidence").deepCopy());
        }
        if(findings.isEmpty()||review.path("status").asText().equals("ARCHIVED"))throw RecoveryException.conflict("V2_EVIDENCE_DISPUTED","该观察当前无可接受目标或已归档，请返回来源核对");
        observation.put("status",gap?"GAP":support?"SUPPORTED":"UNVERIFIED");var result=db.object();result.set("event",event);result.set("review",review);result.set("observation",observation);return result;
    }
    String currentFindingStatus(JsonNode review,JsonNode finding){
        if(!finding.path("confirmed").asBoolean()||finding.path("disputed").asBoolean()||review.path("status").asText().equals("ARCHIVED"))return "DISPUTED";
        if(finding.path("currentEvidence").path("sourceReportHash").asText().equals(review.path("reportHash").asText()))return finding.path("currentEvidence").path("status").asText("UNVERIFIED");
        if(!review.path("currentSupportWithdrawn").asBoolean()&&review.path("lastEvidence").path("sourceReportHash").asText().equals(review.path("reportHash").asText())&&ReviewService.contains(review.path("lastEvidence").path("supportedFindingIds"),finding.path("id").asText()))return "SUPPORTED";
        return finding.path("kind").asText().equals("uncertain")?"UNVERIFIED":"GAP";
    }
    ObjectNode load(String kind,String scope,JsonNode body){String mid=required(body,"manifestId",100);var ids=db.manifestOperationIds(kind,scope,mid);if(ids.size()!=1)throw RecoveryException.invalid("输入清单不存在或不属于当前来源");var manifest=(ObjectNode)db.get("v2_operations",ids.getFirst()).path("result");if(!required(body,"manifestHash",100).equals(manifest.path("manifestHash").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","输入清单摘要不一致，请重新预览");check(manifest);return manifest;}
    void check(JsonNode manifest){for(JsonNode dep:manifest.path("dependencies")){
        JsonNode current;try{current=switch(dep.path("kind").asText()){case "MATERIAL"->db.json().valueToTree(db.database().material(dep.path("id").asText()));case "STORY"->db.get("v2_stories",dep.path("id").asText());case "TRAINING_EVENT"->trainingSource(dep.path("id").asText());default->throw RecoveryException.invalid("不支持的来源类型");};}catch(ApiException e){throw RecoveryException.conflict("V2_SOURCE_CHANGED","预览来源已删除、改变或存在争议；请重新预览，没有调用模型。");}
        if(!db.hash(current).equals(dep.path("hash").asText()))throw RecoveryException.conflict("V2_SOURCE_CHANGED","预览来源已更新；请重新预览，没有调用模型。");
    }}
    boolean changed(JsonNode manifest){try{check(manifest);return false;}catch(ApiException e){return true;}}
}
