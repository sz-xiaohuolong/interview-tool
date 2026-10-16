package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.interview.Models.*;

@Service
class PracticeService implements V2ModelHandler {
    final V2Store db;final V2Operations ops;final V2ModelRunner runner;
    PracticeService(V2Store db,V2Operations ops,V2ModelRunner runner){this.db=db;this.ops=ops;this.runner=runner;}
    static String text(JsonNode body,String key,int limit,boolean required){JsonNode value=body.path(key);if(!value.isTextual()||value.asText().length()>limit||required&&value.asText().isBlank())throw V2Exception.invalid(key+" 必须是"+(required?"非空":"允许为空的")+"文本，最多"+limit+"字符");return value.asText();}
    ObjectNode create(JsonNode body){return visibleCase(ops.write("PRACTICE_CASE","LOCAL_USER",body,()->{
        String kind=choice(required(body,"sourceKind",20),"SESSION","STORY");if(kind.equals("STORY"))return storyCase(body);
        String sid=required(body,"sourceId",100),mid=required(body,"messageId",100);var source=db.legacy.session(sid);var messages=source.path("messages");int position=-1;
        for(int i=0;i<messages.size();i++)if(messages.get(i).path("id").asText().equals(mid))position=i;
        if(position<0||position+1>=messages.size()||!messages.get(position).path("role").asText().equals("ASSISTANT")||!messages.get(position+1).path("role").asText().equals("USER"))throw V2Exception.invalid("请选择已有独立回答的主问题或追问");
        var question=messages.get(position);int qi=question.path("questionIndex").asInt();var prior=db.array();
        for(int i=Math.max(0,position-4);i<position;i++)if(messages.get(i).path("questionIndex").asInt(-1)==qi)prior.add(messages.get(i).deepCopy());
        var row=db.object().put("familyId",id()).put("caseVersion",1).put("sourceKind",kind).put("sourceId",sid).put("messageId",mid).put("questionIndex",qi)
            .put("question",question.path("content").asText()).put("context",source.path("context").asText()).put("contextOrigin","LEGACY_CONTEXT").put("contextTruncated",source.path("contextTruncated").asBoolean())
            .put("mock",source.path("mock").asBoolean()).put("analysisExposure",source.path("reportStatus").asText().equals("READY")?"PROVIDED":"UNKNOWN").put("originalExposure","UNKNOWN");
        row.set("priorDialogue",prior);row.set("rubric",rubric());row.put("questionHash",db.hash(question.path("content")));var context=db.object().put("text",row.path("context").asText());context.set("priorDialogue",prior);row.put("contextHash",db.hash(context)).put("rubricHash",db.hash(row.path("rubric")));
        row.set("initialProtocol",runner.llm.protocol(V2Task.PRACTICE_EVALUATOR));
        if(source.path("reportStatus").asText().equals("READY")){var report=db.legacy.report(sid);for(JsonNode q:report.path("questions"))if(q.path("questionIndex").asInt(-1)==qi)row.set("referenceAnalysis",q.deepCopy());}
        if(body.hasNonNull("revisedFromCaseId")){var priorCase=db.get("v2_practice_cases",required(body,"revisedFromCaseId",100));row.put("familyId",priorCase.path("familyId").asText());Integer version=db.legacy.jdbc.queryForObject("SELECT max(case_version) FROM v2_practice_cases WHERE family_id=?",Integer.class,priorCase.path("familyId").asText());row.put("caseVersion",version+1);}
        db.insert("v2_practice_cases",row);
        var original=db.object().put("caseId",row.path("id").asText()).put("ordinal",0).put("kind","ORIGINAL_SNAPSHOT").put("status","IMPORTED").put("answerText",messages.get(position+1).path("content").asText()).put("protocolStatus","UNKNOWN").put("effectiveExposure","UNKNOWN").put("mock",row.path("mock").asBoolean());original.putNull("submittedAt");original.putNull("evaluation");db.insert("v2_practice_attempts",original);return row;
    }));}
    ObjectNode storyCase(JsonNode body){
        String storyId=required(body,"sourceId",100),vid=required(body,"storyVersionId",100),template=required(body,"questionTemplateId",50);var story=db.get("v2_stories",storyId);var version=db.get("v2_story_versions",vid);
        if(story.path("status").asText().equals("ARCHIVED")||!version.path("storyId").asText().equals(storyId)||!version.path("status").asText().equals("CONFIRMED"))throw V2Exception.conflict("V2_SOURCE_NOT_READY","请选择未归档故事中的已确认版本");
        if(!StoryService.QUESTIONS.containsKey(template)||story.path("templateKind").asText().equals("INTRO")&&!Set.of("SELF_INTRO","PROJECT_VALUE").contains(template)||story.path("templateKind").asText().equals("STAR")&&!Set.of("PROJECT_CHALLENGE","TEAM_CONFLICT").contains(template))throw V2Exception.invalid("问题模板不属于该故事类型");
        String question=StoryService.QUESTIONS.get(template),context=version.path("fields").toString();var row=db.object().put("familyId",id()).put("caseVersion",1).put("sourceKind","STORY").put("sourceId",storyId).put("storyVersionId",vid).put("questionTemplateId",template).put("question",question).put("context",context).put("contextOrigin","CONFIRMED_STORY").put("mock",story.path("mock").asBoolean()).put("analysisExposure","PROVIDED").put("questionHash",V2SchemaPreflight.sha(question)).put("contextHash",V2SchemaPreflight.sha(vid+"|"+context));row.putArray("priorDialogue");var rules=rubric();((ObjectNode)rules.get(0)).put("description","回应所选故事问题，核对已确认故事快照中的事实、职责主体和数字；冲突需引用答案并明确指出来源，不作外部真实性保证");((ObjectNode)rules.get(2)).put("description",story.path("templateKind").asText().equals("STAR")?"覆盖情境、任务、个人行动、结果和复盘；区分个人行动与团队结果，未知事实不编造":"覆盖目标岗位、个人背景、相关经历、个人贡献与下一步诉求");row.set("rubric",rules);row.put("rubricHash",db.hash(row.path("rubric")));row.set("initialProtocol",runner.llm.protocol(V2Task.PRACTICE_EVALUATOR));if(body.hasNonNull("revisedFromCaseId")){var old=db.get("v2_practice_cases",required(body,"revisedFromCaseId",100));row.put("familyId",old.path("familyId").asText()).put("caseVersion",db.legacy.jdbc.queryForObject("SELECT max(case_version)+1 FROM v2_practice_cases WHERE family_id=?",Integer.class,old.path("familyId").asText()));}return db.insert("v2_practice_cases",row);
    }
    ArrayNode rubric(){var rules=db.array();rules.addObject().put("checkId","SCOPE").put("description","回应本题要求，区分事实、假设和未知");rules.addObject().put("checkId","REASONING").put("description","解释关键选择、依据及适用边界；无证据时不可断言技术正确");rules.addObject().put("checkId","COVERAGE").put("description","覆盖问题要求的主要环节、限制与风险");rules.addObject().put("checkId","EXPRESSION").put("description","结构清晰，个人行动或方案描述具体，不评价口语表现");return rules;}
    ObjectNode visibleCase(ObjectNode row){var out=row.deepCopy();out.remove("referenceAnalysis");if(out.has("initialProtocol"))out.set("initialProtocol",runner.llm.publicProtocol(out.path("initialProtocol")));return out;}
    ObjectNode caseList(String cursor,int limit,String sourceId){var page=db.page("v2_practice_cases",cursor,limit,null,sourceId==null?null:"source_id",sourceId);for(JsonNode item:page.path("items"))((ObjectNode)item).remove(List.of("context","priorDialogue","referenceAnalysis","initialProtocol","rubric"));return page;}
    ObjectNode reveal(String id,JsonNode body){return ops.write("CASE_REVEAL",id,body,()->{var c=db.get("v2_practice_cases",id);db.expect(c,body);String kind=choice(required(body,"revealKind",30),"SOURCE_ANALYSIS","TEMPLATE_GUIDE");c.put("analysisExposure","PROVIDED");c.withArray("exposureEvents").addObject().put("kind",kind).put("at",now());db.save("v2_practice_cases",c,c.path("revision").asInt());var result=db.object().put("id",id).put("provided",true);result.set("content",kind.equals("SOURCE_ANALYSIS")?c.path("referenceAnalysis"):c.path("rubric"));return result;});}
    ObjectNode attempts(String id,String cursor,int limit){db.get("v2_practice_cases",id);limit=Math.max(1,Math.min(100,limit));int ordinal=-1;
        if(cursor!=null&&!cursor.isBlank())try{ordinal=Integer.parseInt(cursor);if(ordinal<0)throw new NumberFormatException();}catch(NumberFormatException e){throw V2Exception.invalid("作答游标无效，请返回第一页");}
        var ids=db.legacy.jdbc.query("SELECT id FROM v2_practice_attempts WHERE case_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",(rs,n)->rs.getString(1),id,ordinal,limit+1);var page=db.object();var list=page.putArray("items");for(String aid:ids.subList(0,Math.min(limit,ids.size())))list.add(view(db.get("v2_practice_attempts",aid)));page.putNull("nextCursor");if(ids.size()>limit)page.put("nextCursor",list.get(list.size()-1).path("ordinal").asText());return page;}
    ObjectNode attempt(String cid,JsonNode body){return ops.write("PRACTICE_ATTEMPT",cid,body,()->{
        var c=db.get("v2_practice_cases",cid);String declaration=choice(optional(body,"exposureDeclaration","UNKNOWN",30),"SEEN","NOT_SEEN","UNKNOWN");
        int ordinal=db.legacy.jdbc.queryForObject("SELECT COALESCE(max(ordinal),0)+1 FROM v2_practice_attempts WHERE case_id=?",Integer.class,cid);
        var row=db.object().put("caseId",cid).put("ordinal",ordinal).put("kind","NEW_ANSWER").put("status","DRAFT").put("answerText",body.has("answer")?text(body,"answer",6000,false):"").put("exposureDeclaration",declaration).put("effectiveExposure",c.path("analysisExposure").asText().equals("PROVIDED")?"PROVIDED":declaration).put("mock",c.path("mock").asBoolean());row.putNull("submittedAt");return db.insert("v2_practice_attempts",row);
    });}
    ObjectNode draft(String id,JsonNode body){return ops.write("ATTEMPT_DRAFT",id,body,()->{var a=db.get("v2_practice_attempts",id);if(!a.path("status").asText().equals("DRAFT"))throw V2Exception.conflict("V2_ANSWER_SEALED","已提交回答不能修改，请建立新的尝试。");db.expect(a,body);a.put("answerText",text(body,"answer",6000,false));return db.save("v2_practice_attempts",a,a.path("revision").asInt());});}
    ObjectNode submit(String id,JsonNode body){var group=runner.run(V2Task.PRACTICE_EVALUATOR,"ATTEMPT_SUBMIT",id,body,()->{
        var a=db.get("v2_practice_attempts",id);if(!a.path("status").asText().equals("DRAFT"))throw V2Exception.conflict("V2_ANSWER_SEALED","答案已封存，请查看评估，或建立新尝试。");db.expect(a,body);
        String answer=text(body,"answer",6000,true);var c=db.get("v2_practice_cases",a.path("caseId").asText());checkMode(c);
        var row=db.object().put("caseId",c.path("id").asText()).put("status","RUNNING").put("initialAttemptId",id);row.putArray("attemptIds").add(id);db.insert("v2_practice_evaluation_groups",row);
        a.put("answerText",answer).put("submittedAt",now()).put("status","EVALUATING").put("primaryGroupId",row.path("id").asText());if(c.path("analysisExposure").asText().equals("PROVIDED"))a.put("effectiveExposure","PROVIDED");db.save("v2_practice_attempts",a,a.path("revision").asInt());
        return new V2ModelRunner.Work("v2_practice_evaluation_groups",row,evaluationInput(c,a));
    });return view(db.get("v2_practice_attempts",group.path("initialAttemptId").asText()));}
    ObjectNode evaluationInput(JsonNode c,JsonNode a){var input=db.object().put("question",c.path("question").asText()).put("answerText",a.path("answerText").asText()).put("answerSnapshotId","answer:"+a.path("id").asText());input.set("rubric",c.path("rubric").deepCopy());input.set("priorDialogue",c.path("priorDialogue").deepCopy());var snapshots=input.putArray("snapshots");snapshot(snapshots,"context",c.path("context").asText(),"CONTEXT");snapshot(snapshots,input.path("answerSnapshotId").asText(),a.path("answerText").asText(),"ANSWER");return input;}
    void snapshot(ArrayNode snapshots,String id,String text,String purpose){snapshots.addObject().put("id",id).put("text",text).put("purpose",purpose).put("sha256",V2SchemaPreflight.sha(text));}
    void checkMode(JsonNode c){if(c.path("mock").asBoolean()!=runner.llm.legacy.isMock())throw V2Exception.conflict("V2_MODEL_MODE_CHANGED","来源与当前 Mock 模式不同，请使用对应模式。");}
    ObjectNode view(ObjectNode a){var out=a.deepCopy();if(a.hasNonNull("primaryGroupId")){var group=db.get("v2_practice_evaluation_groups",a.path("primaryGroupId").asText());out.set("evaluation",groupView(group));}return out;}
    ObjectNode groupView(ObjectNode group){var out=group.deepCopy();if(out.has("protocol"))out.set("protocol",runner.llm.publicProtocol(out.path("protocol")));return out;}
    ObjectNode newGroup(String cid,JsonNode body){return groupView(runner.run(V2Task.PRACTICE_EVALUATOR,"EVALUATION_GROUP",cid,body,()->{
        var c=db.get("v2_practice_cases",cid);checkMode(c);var a=db.get("v2_practice_attempts",required(body,"attemptId",100));
        if(!a.path("caseId").asText().equals(cid)||!a.path("kind").asText().equals("NEW_ANSWER")||!Set.of("READY","FAILED","INTERRUPTED").contains(a.path("status").asText()))throw V2Exception.invalid("请选择该案例已封存的新回答；旧原答只允许双侧配对重评");
        var row=db.object().put("caseId",cid).put("status","RUNNING");row.putArray("attemptIds").add(a.path("id").asText());db.insert("v2_practice_evaluation_groups",row);return new V2ModelRunner.Work("v2_practice_evaluation_groups",row,evaluationInput(c,a));
    }));}
    ObjectNode selectedGroup(JsonNode body,String side,JsonNode a){String key=side+"EvaluationGroupId";String gid=body.hasNonNull(key)?required(body,key,100):a.path("primaryGroupId").asText();if(gid.isBlank())return null;var group=db.get("v2_practice_evaluation_groups",gid);if(!group.path("status").asText().equals("READY")||!ReviewService.contains(group.path("attemptIds"),a.path("id").asText()))throw V2Exception.invalid("所选评估组尚未就绪或不属于该回答");return group;}
    ObjectNode compare(JsonNode body){
        String mode=choice(required(body,"mode",30),"COMPARE","REEVALUATE_PAIR");String kind=mode.equals("COMPARE")?"PRACTICE_COMPARE":"PRACTICE_PAIR";var prior=ops.replay(kind,"LOCAL_USER",body);if(prior!=null)return prior.has("resourceTable")?comparisonView(runner.result(prior)):(ObjectNode)prior.path("result").deepCopy();
        String left=required(body,"leftAttemptId",100),right=required(body,"rightAttemptId",100);if(left.equals(right))throw V2Exception.invalid("请选择两次不同的独立作答");
        var l=db.get("v2_practice_attempts",left);var r=db.get("v2_practice_attempts",right);for(JsonNode a:List.of(l,r))if(!Set.of("READY","IMPORTED","FAILED","INTERRUPTED").contains(a.path("status").asText()))throw V2Exception.invalid("请选择已封存的两段原文");
        var lc=db.get("v2_practice_cases",l.path("caseId").asText());var rc=db.get("v2_practice_cases",r.path("caseId").asText());
        if(lc.path("mock").asBoolean()!=rc.path("mock").asBoolean())throw V2Exception.conflict("V2_MODEL_MODE_CHANGED","Mock 与真实记录不能合并或比较");
        String comparability="SAME_PROTOCOL";var lg=mode.equals("COMPARE")?selectedGroup(body,"left",l):null;var rg=mode.equals("COMPARE")?selectedGroup(body,"right",r):null;
        if(!l.path("caseId").asText().equals(r.path("caseId").asText())||!lc.path("questionHash").asText().equals(rc.path("questionHash").asText())||!lc.path("contextHash").asText().equals(rc.path("contextHash").asText()))comparability="DIFFERENT_CONTEXT";
        else if(lg==null||rg==null||protocolKey(lg.path("protocol")).equals("UNKNOWN")||protocolKey(rg.path("protocol")).equals("UNKNOWN")||!protocolKey(lg.path("protocol")).equals(protocolKey(rg.path("protocol"))))comparability="TEXT_ONLY";
        final String comparisonType=comparability;
        if(comparability.equals("DIFFERENT_CONTEXT")||mode.equals("COMPARE")&&comparability.equals("TEXT_ONLY")){
            if(mode.equals("REEVALUATE_PAIR"))throw V2Exception.conflict("V2_DIFFERENT_CONTEXT","题目或上下文不同，不能重评为进步");
            return ops.write(kind,"LOCAL_USER",body,()->db.insert("v2_practice_comparisons",comparisonRow(l,r,comparisonType).put("status",comparisonType)));
        }
        checkMode(lc);V2Task task=mode.equals("REEVALUATE_PAIR")?V2Task.PRACTICE_PAIR_EVALUATOR:V2Task.PRACTICE_COMPARE;
        var result=runner.run(task,kind,"LOCAL_USER",body,()->{
            var row=comparisonRow(l,r,mode.equals("REEVALUATE_PAIR")?"SAME_PROTOCOL":comparisonType).put("status","RUNNING");if(lg!=null)row.put("leftEvaluationGroupId",lg.path("id").asText());if(rg!=null)row.put("rightEvaluationGroupId",rg.path("id").asText());
            if(task==V2Task.PRACTICE_PAIR_EVALUATOR){var group=db.object().put("caseId",lc.path("id").asText()).put("status","RUNNING");group.putArray("attemptIds").add(left).add(right);db.insert("v2_practice_evaluation_groups",group);row.put("pairedGroupId",group.path("id").asText());}
            db.insert("v2_practice_comparisons",row);var input=db.object().put("question",lc.path("question").asText()).put("leftAnswer",l.path("answerText").asText()).put("rightAnswer",r.path("answerText").asText()).put("leftSnapshotId","left:"+left).put("rightSnapshotId","right:"+right);input.set("rubric",lc.path("rubric").deepCopy());input.set("priorDialogue",lc.path("priorDialogue").deepCopy());var snapshots=input.putArray("snapshots");snapshot(snapshots,input.path("leftSnapshotId").asText(),l.path("answerText").asText(),"ANSWER");snapshot(snapshots,input.path("rightSnapshotId").asText(),r.path("answerText").asText(),"ANSWER");snapshot(snapshots,"context",lc.path("context").asText(),"CONTEXT");if(lg!=null)input.set("leftEvaluation",groupEvaluation(lg,left));if(rg!=null)input.set("rightEvaluation",groupEvaluation(rg,right));return new V2ModelRunner.Work("v2_practice_comparisons",row,input);
        });return comparisonView(result);
    }
    JsonNode groupEvaluation(JsonNode group,String aid){if(group.path("result").has("checks"))return group.path("result");return group.path("attemptIds").path(0).asText().equals(aid)?group.path("result").path("leftEvaluation"):group.path("result").path("rightEvaluation");}
    String protocolKey(JsonNode protocol){if(!protocol.isObject())return "UNKNOWN";for(String field:List.of("rubricVersion","promptHash","outputSchemaVersion","providerIdentity","requestedModel"))if(!protocol.path(field).isTextual()||protocol.path(field).asText().isBlank()||protocol.path(field).asText().equals("UNKNOWN"))return "UNKNOWN";if(!protocol.path("parameters").isObject()||!protocol.path("mock").isBoolean())return "UNKNOWN";var p=((ObjectNode)protocol).deepCopy();p.remove(List.of("startedAt","finishedAt","finishReason","systemFingerprint","promptText"));return db.hash(p);}
    ObjectNode comparisonRow(JsonNode l,JsonNode r,String type){var row=db.object().put("caseId",l.path("caseId").asText()).put("leftAttemptId",l.path("id").asText()).put("rightAttemptId",r.path("id").asText()).put("comparability",type).put("scopeNotice","仅比较这两段文本在所示规则下的变化；辅助或未知暴露不能证明独立掌握，服务端可见参数不能排除供应商内部更新。");row.putObject("exposure").put("left",l.path("effectiveExposure").asText("UNKNOWN")).put("right",r.path("effectiveExposure").asText("UNKNOWN"));return row;}
    ObjectNode comparisonView(ObjectNode row){var out=row.deepCopy();out.put("leftAnswer",db.get("v2_practice_attempts",row.path("leftAttemptId").asText()).path("answerText").asText());out.put("rightAnswer",db.get("v2_practice_attempts",row.path("rightAttemptId").asText()).path("answerText").asText());if(out.has("protocol"))out.set("protocol",runner.llm.publicProtocol(out.path("protocol")));return out;}
    public boolean supports(V2Task task){return Set.of(V2Task.PRACTICE_EVALUATOR,V2Task.PRACTICE_COMPARE,V2Task.PRACTICE_PAIR_EVALUATOR).contains(task);}
    public void running(ObjectNode row,ObjectNode op){row.put("status","RUNNING");row.set("protocol",op.path("protocol").deepCopy());
        if(row.has("initialAttemptId")){var a=db.get("v2_practice_attempts",row.path("initialAttemptId").asText());if(!a.path("status").asText().equals("EVALUATING")){a.put("status","EVALUATING");db.save("v2_practice_attempts",a,a.path("revision").asInt());}}
        if(row.has("pairedGroupId")){var g=db.get("v2_practice_evaluation_groups",row.path("pairedGroupId").asText());g.put("status","RUNNING").put("operationId",op.path("id").asText());g.set("protocol",op.path("protocol").deepCopy());db.save("v2_practice_evaluation_groups",g,g.path("revision").asInt());}
    }
    public void completed(ObjectNode row,ObjectNode result,ObjectNode op){row.put("status","READY");row.set("result",result);row.set("protocol",op.path("protocol").deepCopy());
        if(row.has("initialAttemptId")){var a=db.get("v2_practice_attempts",row.path("initialAttemptId").asText());a.put("status","READY");db.save("v2_practice_attempts",a,a.path("revision").asInt());}
        if(row.has("pairedGroupId")){var group=db.get("v2_practice_evaluation_groups",row.path("pairedGroupId").asText());group.put("status","READY").put("operationId",op.path("id").asText());group.set("protocol",op.path("protocol").deepCopy());group.set("result",result.deepCopy());db.save("v2_practice_evaluation_groups",group,group.path("revision").asInt());}
    }
    public void failed(ObjectNode row,ObjectNode op,boolean interrupted){row.put("status",interrupted?"INTERRUPTED":"FAILED");
        if(row.has("initialAttemptId")){var a=db.get("v2_practice_attempts",row.path("initialAttemptId").asText());a.put("status",interrupted?"INTERRUPTED":"FAILED");db.save("v2_practice_attempts",a,a.path("revision").asInt());}
        if(row.has("pairedGroupId")){var g=db.get("v2_practice_evaluation_groups",row.path("pairedGroupId").asText());g.put("status",interrupted?"INTERRUPTED":"FAILED");db.save("v2_practice_evaluation_groups",g,g.path("revision").asInt());}
    }
}
