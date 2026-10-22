package cn.interview.service.training;

import cn.interview.client.training.ModelTask;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;
import cn.interview.task.ModelTaskHandler;
import cn.interview.task.ModelTaskRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;

import static cn.interview.common.Models.*;

@Service
public class StoryService implements ModelTaskHandler {
    static final Map<String,List<String>> FIELDS=Map.of("INTRO",List.of("targetRole","background","experience","contribution","aspiration"),"STAR",List.of("situation","task","action","result","reflection"));
    static final Map<String,String> QUESTIONS=Map.of("SELF_INTRO","请结合真实经历做一段面向目标岗位的自我介绍。","PROJECT_VALUE","请说明你的真实经历为何与这个岗位相关。","PROJECT_CHALLENGE","请讲述一个真实项目中的困难，以及你的个人行动和结果。","TEAM_CONFLICT","请用真实经历说明你如何处理项目分歧，并说明个人行动和复盘。");
    final TrainingStore db;final OperationService ops;final ModelTaskRunner runner;final InputPreparationService inputs;
    StoryService(TrainingStore db,OperationService ops,ModelTaskRunner runner,InputPreparationService inputs){this.db=db;this.ops=ops;this.runner=runner;this.inputs=inputs;}
    public ObjectNode create(JsonNode body){return ops.write("STORY_CREATE","LOCAL_USER",body,()->{String kind=choice(required(body,"templateKind",20),"INTRO","STAR");var story=db.object().put("templateKind",kind).put("status","DRAFT").put("currentVersion",1).put("generationState","NONE").put("mock",runner.isMock());story.set("templateFields",db.json().valueToTree(FIELDS.get(kind)));story.set("fields",manualFields(kind,body.path("fields"),null));story.set("materialIds",body.has("materialIds")?body.path("materialIds").deepCopy():db.array());if(!story.path("materialIds").isArray()||story.path("materialIds").size()>5)throw RecoveryException.invalid("最多5份来源资料");db.insert("v2_stories",story);var version=version(story,1,story.path("fields"),db.array(),db.array(),optional(body,"expressionDraft","",6000));story.put("currentVersionId",version.path("id").asText());updateInserted(story);return story;});}
    void updateInserted(ObjectNode story){db.updatePayload("v2_stories",story);}
    ObjectNode manualFields(String kind,JsonNode raw,JsonNode previous){if(!raw.isMissingNode()&&!raw.isObject())throw RecoveryException.invalid("故事字段格式无效");if(raw.isObject())raw.fieldNames().forEachRemaining(key->{if(!FIELDS.get(kind).contains(key))throw RecoveryException.invalid("字段不属于所选模板");});var out=db.object();int count=0;
        for(String key:FIELDS.get(kind)){var source=raw.path(key);String text=source.isMissingNode()?"":PracticeService.text(source,"text",6000,false);count+=text.length();String origin=source.isMissingNode()||text.isBlank()?"MISSING":choice(required(source,"originType",30),"USER_ASSERTION","MATERIAL_QUOTE","MODEL_DRAFT","MISSING");
            // 已有引用经用户修改后按用户编辑保存，旧版本保留原文、引用和来源快照。
            if(origin.equals("MATERIAL_QUOTE")&&previous!=null&&previous.path(key).path("originType").asText().equals("MATERIAL_QUOTE")&&!source.path("text").equals(previous.path(key).path("text")))origin="USER_ASSERTION";
            var field=out.putObject(key).put("text",text).put("originType",origin);field.putArray("sourceRefs");field.putObject("confirmation").put("confirmed",false);
            if(origin.equals("MISSING")&&!text.isBlank())throw RecoveryException.invalid("待补充字段不能带未经声明的事实");
            if(origin.equals("MATERIAL_QUOTE")){if(previous==null||!source.path("text").equals(previous.path(key).path("text"))||!previous.path(key).path("originType").asText().equals("MATERIAL_QUOTE"))throw RecoveryException.invalid("材料引用请从已绑定来源的生成版本沿用；手动补充请标为本人声明");field.set("sourceRefs",previous.path(key).path("sourceRefs").deepCopy());}
            if(previous!=null&&previous.path(key).path("text").equals(field.path("text"))&&previous.path(key).path("originType").equals(field.path("originType"))&&previous.path(key).has("confirmation"))field.set("confirmation",previous.path(key).path("confirmation").deepCopy());
        }if(count>6000)throw RecoveryException.invalid("单版故事事实合计不超过6000字符");if(previous!=null){boolean changed=false;for(String k:FIELDS.get(kind))changed|=!previous.path(k).path("text").equals(out.path(k).path("text"))||!previous.path(k).path("originType").equals(out.path(k).path("originType"));if(changed)for(JsonNode f:out)((ObjectNode)f).putObject("confirmation").put("confirmed",false);}return out;
    }
    ObjectNode version(JsonNode story,int number,JsonNode fields,JsonNode snapshots,JsonNode dependencies,String expression){var row=db.object().put("storyId",story.path("id").asText()).put("version",number).put("status","DRAFT").put("expressionDraft",expression);row.set("fields",fields.deepCopy());row.set("sourceSnapshots",snapshots.deepCopy());row.set("dependencies",dependencies.deepCopy());return db.insert("v2_story_versions",row);}
    void editable(JsonNode story){if(story.path("status").asText().equals("ARCHIVED"))throw RecoveryException.conflict("V2_INVALID_STATE","归档故事保留历史，请先恢复再编辑");if(story.path("generationState").asText().equals("RUNNING"))throw RecoveryException.conflict("V2_RESOURCE_BUSY","该故事正在生成，请保留本页编辑并等待结果");}
    public ObjectNode save(String id,JsonNode body){return ops.write("STORY_VERSION",id,body,()->{var story=db.get("v2_stories",id);db.expect(story,body);editable(story);var prior=db.get("v2_story_versions",story.path("currentVersionId").asText());var fields=manualFields(story.path("templateKind").asText(),body.path("fields"),prior.path("fields"));int version=story.path("currentVersion").asInt()+1;var next=version(story,version,fields,prior.path("sourceSnapshots"),prior.path("dependencies"),optional(body,"expressionDraft","",6000));story.put("currentVersion",version).put("currentVersionId",next.path("id").asText()).put("status","DRAFT");story.set("fields",fields);return db.save("v2_stories",story,story.path("revision").asInt());});}
    public ObjectNode confirm(String id,JsonNode body){return ops.write("STORY_CONFIRM",id,body,()->{var story=db.get("v2_stories",id);db.expect(story,body);editable(story);if(!body.path("storyVersion").isIntegralNumber()||body.path("storyVersion").asInt()!=story.path("currentVersion").asInt())throw RecoveryException.conflict("V2_VERSION_CONFLICT","故事已出现新版本，请保留本页文本并查看当前版本");if(story.path("status").asText().equals("CONFIRMED"))throw RecoveryException.conflict("V2_INVALID_STATE","该版本已确认；修改请保存新版本");var version=db.get("v2_story_versions",story.path("currentVersionId").asText());String personal=story.path("templateKind").asText().equals("STAR")?"action":"contribution";
        if(inputs.changed(db.object().set("dependencies",version.path("dependencies")))&&!body.path("acknowledgeSourcesChanged").asBoolean())throw RecoveryException.conflict("V2_SOURCE_CHANGED","来源已变化，历史快照保留；请核对并明确接受该版本来源边界");
        var fields=((ObjectNode)version.path("fields")).deepCopy();for(String key:FIELDS.get(story.path("templateKind").asText())){var field=(ObjectNode)fields.path(key);var confirmation=body.path("confirmations").path(key);String origin=field.path("originType").asText();boolean na=confirmation.path("notApplicable").asBoolean();if(na){if(!field.path("text").asText().isBlank()||!origin.equals("MISSING"))throw RecoveryException.invalid("仅空缺事实可标为不适用；非空草稿请先核对或清空");if(key.equals(personal))throw RecoveryException.invalid("个人行动或个人贡献不能用不适用代替");required(confirmation,"reason",1000);}else if(!confirmation.path("confirmed").asBoolean()||field.path("text").asText().isBlank()||Set.of("MISSING","MODEL_DRAFT").contains(origin))throw RecoveryException.invalid("请逐字段确认事实；表达草稿须先改为本人声明或有依据的材料引用");
            field.set("confirmation",db.object().put("confirmed",!na).put("notApplicable",na).put("reason",na?confirmation.path("reason").asText():"").put("at",now()).put("origin","USER_CONFIRMED"));
        }
        version.set("fields",fields);version.put("status","CONFIRMED").put("confirmedAt",now());if(db.confirmStoryVersion(version)!=1)throw RecoveryException.conflict("V2_VERSION_CONFLICT","故事版本已确认或变化");story.set("fields",fields);story.put("status","CONFIRMED");return db.save("v2_stories",story,story.path("revision").asInt());});}
    public ObjectNode archive(String id,JsonNode body){return ops.write("STORY_ARCHIVE",id,body,()->{var story=db.get("v2_stories",id);db.expect(story,body);if(story.path("generationState").asText().equals("RUNNING"))throw RecoveryException.conflict("V2_RESOURCE_BUSY","请等待当前生成结束再归档");if(body.path("restore").asBoolean()){if(!story.path("status").asText().equals("ARCHIVED"))throw RecoveryException.conflict("V2_INVALID_STATE","故事尚未归档");story.put("status",db.get("v2_story_versions",story.path("currentVersionId").asText()).path("status").asText());}else story.put("status","ARCHIVED");return db.save("v2_stories",story,story.path("revision").asInt());});}
    public ObjectNode delete(String id,JsonNode body){return ops.write("STORY_DELETE",id,body,()->{
        var story=db.get("v2_stories",id);db.expect(story,body);
        if(story.path("generationState").asText().equals("RUNNING"))throw RecoveryException.conflict("V2_RESOURCE_BUSY","请等待当前生成结束再删除故事");
        // 保留版本快照，已有独立作答继续沿用创建时的事实依据。
        story.put("deletedAt",now());var deleted=db.save("v2_stories",story,story.path("revision").asInt());
        return db.object().put("id",id).put("deletedAt",deleted.path("deletedAt").asText()).put("revision",deleted.path("revision").asInt());
    });}
    public ObjectNode prepare(String id,JsonNode body){var replay=ops.replay("STORY_INPUT",id,body);if(replay!=null)return (ObjectNode)replay.path("result").deepCopy();var story=db.get("v2_stories",id);editable(story);db.expect(story,body);return inputs.prepare("STORY_INPUT",id,body,story);}
    public ObjectNode generate(String id,JsonNode body){return view(runner.run(ModelTask.STORY_DRAFTER,"STORY_GENERATION",id,body,()->{var manifest=inputs.load("STORY_INPUT",id,body);var story=db.get("v2_stories",id);db.expect(story,body);editable(story);story.set("generationManifest",manifest.deepCopy());return new ModelTaskRunner.Work("v2_stories",story,(ObjectNode)manifest.path("modelInput").deepCopy());}));}
    ObjectNode view(ObjectNode story){var out=story.deepCopy();var version=db.get("v2_story_versions",story.path("currentVersionId").asText());out.set("currentVersionDetail",version);out.put("sourcesChanged",inputs.changed(db.object().set("dependencies",version.path("dependencies"))));if(out.has("protocol"))out.set("protocol",runner.publicProtocol(out.path("protocol")));return out;}
    public ObjectNode history(String id,String cursor,int limit){db.get("v2_stories",id);var page=db.page("v2_story_versions",cursor,limit,null,"story_id",id);for(JsonNode row:page.path("items"))((ObjectNode)row).remove(List.of("fields","sourceSnapshots","dependencies","expressionDraft"));return page;}
    public ObjectNode getVersion(String id,String vid){db.get("v2_stories",id);var row=db.get("v2_story_versions",vid);if(!row.path("storyId").asText().equals(id))throw RecoveryException.invalid("版本不属于该故事");return row;}
    public boolean supports(ModelTask task){return task==ModelTask.STORY_DRAFTER;}
    public void running(ObjectNode row,ObjectNode op){row.put("generationState","RUNNING");}
    public void failed(ObjectNode row,ObjectNode op,boolean interrupted){row.put("generationState",interrupted?"INTERRUPTED":"FAILED");}
    public void completed(ObjectNode row,ObjectNode result,ObjectNode op){int number=row.path("currentVersion").asInt()+1;var fields=((ObjectNode)result.path("fields")).deepCopy();for(JsonNode field:fields)((ObjectNode)field).putObject("confirmation").put("confirmed",false);var manifest=row.path("generationManifest");var dependencies=db.array();for(JsonNode dep:manifest.path("dependencies"))if(!dep.path("kind").asText().equals("STORY"))dependencies.add(dep.deepCopy());var version=version(row,number,fields,manifest.path("snapshots"),dependencies,result.path("expressionDraft").asText());version.set("pendingFacts",result.path("pendingFacts").deepCopy());db.updatePayload("v2_story_versions",version);row.put("currentVersion",number).put("currentVersionId",version.path("id").asText()).put("status","DRAFT").put("generationState","READY");row.set("fields",fields);row.set("protocol",op.path("protocol").deepCopy());row.remove("generationManifest");}

    public ObjectNode list(String cursor,int limit) {var page=db.page("v2_stories",cursor,limit,null);for(JsonNode row:page.path("items"))((com.fasterxml.jackson.databind.node.ObjectNode)row).remove(java.util.List.of("fields","protocol"));return page;}

    public ObjectNode get(String id) {return view(db.get("v2_stories",id));}

    public Map<String,String> templates() {return QUESTIONS;}

}
