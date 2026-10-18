package cn.interview.client;

import cn.interview.mapper.V2Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 仅显式 Mock 模式使用，无法判断为默认，不充当真实模型质量证据。 */
final class V2MockTasks {
    static ObjectNode evaluation(JsonNode input,V2Store db) {
        var out=db.object();var checks=out.putArray("checks");for(JsonNode rule:input.path("rubric"))checks.addObject().put("checkId",rule.path("checkId").asText()).put("status","UNCERTAIN").put("explanation","Mock 仅验证流程，未判断该要求是否满足").putArray("evidenceRefs");
        for(String key:new String[]{"correct","incorrect","missing","uncertain","suggestions"})out.putArray(key);
        out.withArray("uncertain").add("Mock 流程结果，不能用于真实能力结论");out.withArray("suggestions").add("请在真实模型校准中核对原文与检查点");return out;
    }
    static ObjectNode comparison(V2Store db){var out=db.object();for(String key:new String[]{"fixed","remaining","newIssues","suggestions"})out.putArray(key);out.withArray("suggestions").add("Mock 只验证配对流程，不生成进步结论");return out;}
    static ObjectNode generate(V2Task task,ObjectNode input,V2Store db){return switch(task){
        case PRACTICE_EVALUATOR -> evaluation(input,db);
        case PRACTICE_COMPARE -> comparison(db);
        case PRACTICE_PAIR_EVALUATOR -> {var out=db.object();out.set("leftEvaluation",evaluation(input,db));out.set("rightEvaluation",evaluation(input,db));out.set("comparison",comparison(db));yield out;}
        case JD_DIAGNOSTIC -> {var out=db.object().put("hasMore",false).put("coverageNotice","Mock 仅验证流程，本次要求由合成文本按行展开，未经真实模型质量校准");var requirements=out.putArray("requirements");var lines=new java.util.LinkedHashSet<String>();for(String line:input.path("jdText").asText().split("[\\r\\n;；]+"))if(!line.isBlank())lines.add(line.strip());if(lines.size()>12)out.put("hasMore",true);int count=0;boolean incomplete=false;for(JsonNode s:input.path("snapshots"))if(s.path("purpose").asText().equals("MATERIAL")&&s.path("truncated").asBoolean())incomplete=true;for(String line:lines){if(count++>=12)break;var r=requirements.addObject().put("jdQuote",line).put("importance","UNSPECIFIED").put("priorityReason","Mock 建议，请用户自行核对重要性").put("materialCoverage",incomplete?"INCOMPLETE":"NOT_MENTIONED").put("trainingStatus","UNVERIFIED");var refs=r.putArray("evidenceRefs");if(!incomplete)for(JsonNode snapshot:input.path("snapshots"))if(snapshot.path("purpose").asText().equals("MATERIAL"))refs.addObject().put("kind","ABSENCE_CHECK").put("snapshotId",snapshot.path("id").asText()).put("checkId","JD_REQUIREMENT").put("start",0).put("end",snapshot.path("text").asText().length());r.putArray("trainingEventIds");r.putArray("trainingBindings");}yield out;}
        case STORY_DRAFTER -> {var out=db.object().put("expressionDraft","");out.putArray("pendingFacts").add("Mock 未补造事实，请逐字段核对本人声明或材料原文");var fields=out.putObject("fields");for(JsonNode name:input.path("templateFields")){String key=name.asText();var field=fields.putObject(key);JsonNode source=null;for(JsonNode s:input.path("snapshots"))if(s.path("id").asText().equals("user:"+key))source=s;if(source==null){field.put("text","").put("originType","MISSING").putArray("sourceRefs");}else{field.put("text",source.path("text").asText()).put("originType","USER_ASSERTION").putArray("sourceRefs").addObject().put("kind","QUOTE").put("snapshotId",source.path("id").asText()).put("quote",source.path("text").asText());}}yield out;}
        case DESIGN_STAGE_EVALUATOR -> {var out=db.object();for(String key:new String[]{"strengths","gaps","uncertain","tradeoffs","openIssues"})out.putArray(key);out.withArray("uncertain").add("Mock 只验证阶段流程，未评判设计质量");out.putArray("evidenceRefs");yield out;}
        case PROJECT_GAP_QUESTION -> {String brief=input.path("project").path("brief").asText();yield db.object().put("question","针对“"+input.path("project").path("name").asText()+"”的"+input.path("checkpoint").path("title").asText()+"，请说明你会补充哪项具体事实、如何验证，并指出验证边界？").put("checkpointId",input.path("checkpoint").path("checkpointId").asText()).put("projectQuote",brief.substring(0,Math.min(100,brief.length())));}
    };}
}
