package cn.interview.validation;

import cn.interview.client.V2LlmClient;
import cn.interview.client.V2Task;
import cn.interview.common.ContentHash;
import cn.interview.exception.V2Exception;
import cn.interview.mapper.V2Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

/** 引用只能定位本次固定快照；不按模型返回的路径读取文件。 */
@Component
public class V2OutputValidator {
    final V2Store db;V2OutputValidator(V2Store db){this.db=db;}
    V2Exception invalid(){return V2LlmClient.failed("MODEL_INVALID_OUTPUT","模型输出结构或来源引用不合法，没有保存为有效结果。");}
    void keys(JsonNode object,String... allowed){if(!object.isObject())throw invalid();var set=Set.of(allowed);object.fieldNames().forEachRemaining(k->{if(!set.contains(k))throw invalid();});}
    void strings(JsonNode value){if(!value.isArray()||value.size()>30)throw invalid();for(JsonNode s:value)if(!s.isTextual()||s.asText().isBlank()||s.asText().length()>4000)throw invalid();}
    Set<String> rules(JsonNode input){var result=new HashSet<String>();for(JsonNode rule:input.path("rubric"))result.add(rule.path("checkId").asText());return result;}
    ObjectNode ref(JsonNode ref,JsonNode input,String onlySnapshot,Set<String> checkIds){
        keys(ref,"kind","snapshotId","sha256","start","end","quote","checkId");String sid=ref.path("snapshotId").asText();
        JsonNode source=null;for(JsonNode s:input.path("snapshots"))if(s.path("id").asText().equals(sid))source=s;
        if(source==null||(onlySnapshot!=null&&!sid.equals(onlySnapshot)))throw invalid();String text=source.path("text").asText(),hash=ContentHash.sha(text);
        if(ref.has("sha256")&&!ref.path("sha256").asText().equals(hash))throw invalid();
        var out=((ObjectNode)ref).deepCopy().put("sha256",hash);int start,end;
        if(ref.path("kind").asText().equals("QUOTE")){
            String quote=ref.path("quote").asText();start=text.indexOf(quote);end=start+quote.length();
            if(quote.isBlank()||start<0||text.indexOf(quote,start+1)>=0)throw invalid();
            if(ref.has("start")&&(!ref.path("start").isIntegralNumber()||ref.path("start").asInt()!=start)||ref.has("end")&&(!ref.path("end").isIntegralNumber()||ref.path("end").asInt()!=end))throw invalid();
        }else if(ref.path("kind").asText().equals("ABSENCE_CHECK")){
            if(ref.has("quote")||!checkIds.contains(ref.path("checkId").asText())||!ref.path("start").isIntegralNumber()||ref.path("start").asInt()!=0||!ref.path("end").isIntegralNumber()||ref.path("end").asInt()!=text.length())throw invalid();start=0;end=text.length();
        }else throw invalid();
        if(start>0&&Character.isLowSurrogate(text.charAt(start))||end<text.length()&&Character.isLowSurrogate(text.charAt(end)))throw invalid();
        return out.put("start",start).put("end",end);
    }
    JsonNode refs(JsonNode refs,JsonNode input,String onlySnapshot,Set<String> ids){if(!refs.isArray()||refs.size()>30)throw invalid();var out=db.array();for(JsonNode r:refs)out.add(ref(r,input,onlySnapshot,ids));return out;}
    ObjectNode evaluation(JsonNode raw,JsonNode input,String answerId){
        keys(raw,"checks","correct","incorrect","missing","uncertain","suggestions");var out=((ObjectNode)raw).deepCopy();var valid=rules(input);var seen=new HashSet<String>();
        if(!raw.path("checks").isArray()||raw.path("checks").size()!=valid.size())throw invalid();
        for(JsonNode check:out.path("checks")){
            keys(check,"checkId","status","explanation","evidenceRefs");String id=check.path("checkId").asText(),status=check.path("status").asText();
            if(!valid.contains(id)||!seen.add(id)||!Set.of("MET","GAP","UNCERTAIN").contains(status)||!check.path("explanation").isTextual()||check.path("explanation").asText().isBlank()||check.path("explanation").asText().length()>4000)throw invalid();
            var evidence=refs(check.path("evidenceRefs"),input,answerId,valid);((ObjectNode)check).set("evidenceRefs",evidence);
            if(!status.equals("UNCERTAIN")&&evidence.isEmpty())throw invalid();
            if(status.equals("MET")){boolean quote=false;for(JsonNode r:evidence)if(r.path("kind").asText().equals("QUOTE"))quote=true;if(!quote)throw invalid();}
            for(JsonNode r:evidence)if(r.path("kind").asText().equals("ABSENCE_CHECK")&&!r.path("checkId").asText().equals(id))throw invalid();
        }
        for(String key:List.of("correct","incorrect","missing","uncertain","suggestions"))strings(out.path(key));return out;
    }
    ObjectNode comparison(JsonNode raw,ObjectNode input){
        keys(raw,"fixed","remaining","newIssues","suggestions");var out=((ObjectNode)raw).deepCopy();var valid=rules(input);
        for(String category:List.of("fixed","remaining","newIssues")){
            if(!out.path(category).isArray()||out.path(category).size()>valid.size())throw invalid();var seen=new HashSet<String>();
            for(JsonNode issue:out.path(category)){
                keys(issue,"checkId","description","leftEvidenceRefs","rightEvidenceRefs");String id=issue.path("checkId").asText();if(!valid.contains(id)||!seen.add(id)||!issue.path("description").isTextual()||issue.path("description").asText().isBlank())throw invalid();
                String left=checkStatus(input.path("leftEvaluation"),id),right=checkStatus(input.path("rightEvaluation"),id);
                if(category.equals("fixed")&&(!left.equals("GAP")||!right.equals("MET"))||category.equals("newIssues")&&(!left.equals("MET")||!right.equals("GAP")))throw invalid();
                var l=refs(issue.path("leftEvidenceRefs"),input,input.path("leftSnapshotId").asText(),valid);var r=refs(issue.path("rightEvidenceRefs"),input,input.path("rightSnapshotId").asText(),valid);
                if(l.isEmpty()||r.isEmpty())throw invalid();((ObjectNode)issue).set("leftEvidenceRefs",l);((ObjectNode)issue).set("rightEvidenceRefs",r);
                for(JsonNode refs:List.of(l,r))for(JsonNode ref:refs)if(ref.path("kind").asText().equals("ABSENCE_CHECK")&&!ref.path("checkId").asText().equals(id))throw invalid();
                boolean leftQuote=false,rightQuote=false;for(JsonNode ref:l)leftQuote|=ref.path("kind").asText().equals("QUOTE");for(JsonNode ref:r)rightQuote|=ref.path("kind").asText().equals("QUOTE");
                if(category.equals("fixed")&&!rightQuote||category.equals("newIssues")&&!leftQuote||category.equals("remaining")&&left.equals("MET")&&right.equals("MET"))throw invalid();
                if(!category.equals("remaining")&&input.path("leftAnswer").asText().equals(input.path("rightAnswer").asText()))throw invalid();
            }
        }strings(out.path("suggestions"));return out;
    }
    String checkStatus(JsonNode evaluation,String id){for(JsonNode check:evaluation.path("checks"))if(check.path("checkId").asText().equals(id))return check.path("status").asText();return "UNKNOWN";}
    ObjectNode diagnostic(ObjectNode raw,ObjectNode input){
        keys(raw,"requirements","hasMore","coverageNotice");if(!raw.path("hasMore").isBoolean()||!raw.path("coverageNotice").isTextual())throw invalid();
        if(raw.path("hasMore").asBoolean()||raw.path("requirements").size()>12)throw new V2Exception(422,"V2_JD_SCOPE_TOO_LARGE","本次 JD 超过12条要求，请缩小范围并重新预览；没有覆盖旧诊断。","EDIT_INPUT");
        if(!raw.path("requirements").isArray()||raw.path("requirements").isEmpty())throw invalid();var out=raw.deepCopy();boolean hasMaterials=false,truncated=false;
        for(JsonNode s:input.path("snapshots"))if(s.path("purpose").asText().equals("MATERIAL")){hasMaterials=true;truncated|=s.path("truncated").asBoolean();}
        var seen=new HashSet<String>();for(JsonNode requirement:out.path("requirements")){
            keys(requirement,"jdQuote","importance","priorityReason","materialCoverage","trainingStatus","evidenceRefs","trainingEventIds","trainingBindings");String quote=requirement.path("jdQuote").asText();if(!seen.add(quote)||quote.length()>10000)throw invalid();
            var jdRef=ref(db.object().put("kind","QUOTE").put("snapshotId","jd").put("quote",quote),input,"jd",Set.of("JD_REQUIREMENT"));((ObjectNode)requirement).set("jdEvidence",jdRef);
            if(!Set.of("REQUIRED","PREFERRED","UNSPECIFIED").contains(requirement.path("importance").asText())||!requirement.path("priorityReason").isTextual()||requirement.path("priorityReason").asText().length()>2000)throw invalid();
            String coverage=requirement.path("materialCoverage").asText(),training=requirement.path("trainingStatus").asText();if(!Set.of("MENTIONED","NOT_MENTIONED","INCOMPLETE").contains(coverage)||!Set.of("UNVERIFIED","GAP","SUPPORTED","DISPUTED").contains(training))throw invalid();
            var evidence=refs(requirement.path("evidenceRefs"),input,null,Set.of("JD_REQUIREMENT"));boolean materialQuote=false;for(JsonNode r:evidence)if(r.path("kind").asText().equals("QUOTE"))for(JsonNode s:input.path("snapshots"))if(s.path("id").asText().equals(r.path("snapshotId").asText())&&s.path("purpose").asText().equals("MATERIAL"))materialQuote=true;
            if(coverage.equals("MENTIONED")&&(!hasMaterials||!materialQuote)||coverage.equals("NOT_MENTIONED")&&truncated)throw invalid();
            if(coverage.equals("NOT_MENTIONED")&&hasMaterials)for(JsonNode snapshot:input.path("snapshots"))if(snapshot.path("purpose").asText().equals("MATERIAL")){boolean checked=false;for(JsonNode r:evidence)checked|=r.path("kind").asText().equals("ABSENCE_CHECK")&&r.path("snapshotId").asText().equals(snapshot.path("id").asText());if(!checked)throw invalid();}
            ((ObjectNode)requirement).put("materialProvided",hasMaterials);((ObjectNode)requirement).set("evidenceRefs",evidence);
            if(!requirement.path("trainingEventIds").isArray()||requirement.path("trainingEventIds").size()>20||!requirement.path("trainingBindings").isArray()||requirement.path("trainingBindings").size()>30)throw invalid();
            var ids=new HashSet<String>();for(JsonNode eid:requirement.path("trainingEventIds"))if(!eid.isTextual()||!ids.add(eid.asText()))throw invalid();
            boolean support=false,gap=false;var boundIds=new HashSet<String>();var bindings=new HashSet<String>();
            for(JsonNode binding:requirement.path("trainingBindings")){
                keys(binding,"eventId","findingId");String eid=binding.path("eventId").asText(),fid=binding.path("findingId").asText();if(!ids.contains(eid)||!bindings.add(eid+"|"+fid))throw invalid();
                JsonNode match=null;for(JsonNode event:input.path("trainingEvents"))if(event.path("id").asText().equals(eid))for(JsonNode finding:event.path("findings"))if(finding.path("id").asText().equals(fid))match=finding;
                if(match==null||match.path("status").asText().equals("DISPUTED"))throw invalid();boundIds.add(eid);support|=match.path("status").asText().equals("SUPPORTED");gap|=match.path("status").asText().equals("GAP");
            }
            if(!ids.equals(boundIds)||training.equals("SUPPORTED")&&(!support||gap)||training.equals("GAP")&&!gap||training.equals("DISPUTED")||training.equals("UNVERIFIED")&&(support||gap))throw invalid();
        }return out;
    }
    ObjectNode story(ObjectNode raw,ObjectNode input){
        keys(raw,"fields","expressionDraft","pendingFacts");strings(raw.path("pendingFacts"));if(!raw.path("fields").isObject()||!raw.path("expressionDraft").isTextual()||raw.path("expressionDraft").asText().length()>6000)throw invalid();var out=raw.deepCopy();var expected=new HashSet<String>();input.path("templateFields").forEach(f->expected.add(f.asText()));var actual=new HashSet<String>();raw.path("fields").fieldNames().forEachRemaining(actual::add);if(!expected.equals(actual))throw invalid();int total=0;
        for(String key:expected){keys(out.path("fields").path(key),"text","originType","sourceRefs");var field=(ObjectNode)out.path("fields").path(key);if(!field.path("text").isTextual())throw invalid();String text=field.path("text").asText(),origin=field.path("originType").asText();total+=text.length();var refs=refs(field.path("sourceRefs"),input,null,Set.of());field.set("sourceRefs",refs);
            if(origin.equals("MISSING")){if(!text.isEmpty()||!refs.isEmpty())throw invalid();}
            else if(Set.of("MATERIAL_QUOTE","USER_ASSERTION").contains(origin)){if(text.isBlank()||refs.isEmpty())throw invalid();boolean exact=false;for(JsonNode ref:refs){if(!ref.path("kind").asText().equals("QUOTE"))throw invalid();for(JsonNode snapshot:input.path("snapshots"))if(snapshot.path("id").asText().equals(ref.path("snapshotId").asText())){String purpose=snapshot.path("purpose").asText();if(origin.equals("MATERIAL_QUOTE")&&!purpose.equals("MATERIAL")||origin.equals("USER_ASSERTION")&&!purpose.equals("USER_ASSERTION"))throw invalid();}exact|=text.equals(ref.path("quote").asText());}if(!exact)throw invalid();}
            else throw invalid();
        }if(total>6000)throw invalid();var numbers=new HashSet<String>();var p=java.util.regex.Pattern.compile("[0-9０-９]+(?:[.,][0-9０-９]+)?");for(JsonNode snapshot:input.path("snapshots")){var m=p.matcher(snapshot.path("text").asText());while(m.find())numbers.add(m.group());}var m=p.matcher(raw.path("expressionDraft").asText());while(m.find())if(!numbers.contains(m.group()))throw invalid();return out;
    }
    ObjectNode design(ObjectNode raw,ObjectNode input){
        keys(raw,"strengths","gaps","uncertain","tradeoffs","openIssues","evidenceRefs");
        for(String key:List.of("strengths","gaps","uncertain","tradeoffs","openIssues"))strings(raw.path(key));
        var evidence=refs(raw.path("evidenceRefs"),input,null,rules(input));
        for(JsonNode reference:evidence){boolean artifact=false;for(JsonNode snapshot:input.path("snapshots"))if(snapshot.path("id").asText().equals(reference.path("snapshotId").asText()))artifact=snapshot.path("purpose").asText().equals("ARTIFACT");if(!artifact)throw invalid();}
        if((!raw.path("strengths").isEmpty()||!raw.path("gaps").isEmpty()||!raw.path("tradeoffs").isEmpty())&&evidence.isEmpty())throw invalid();
        var out=raw.deepCopy();out.set("evidenceRefs",evidence);return out;
    }
    public ObjectNode validate(V2Task task,ObjectNode input,ObjectNode raw){return switch(task){case PROJECT_GAP_QUESTION->ProjectGapOutputValidator.validate(raw,input);
        case PRACTICE_EVALUATOR -> evaluation(raw,input,input.path("answerSnapshotId").asText());
        case PRACTICE_COMPARE -> comparison(raw,input);
        case PRACTICE_PAIR_EVALUATOR -> {keys(raw,"leftEvaluation","rightEvaluation","comparison");var out=db.object();var l=evaluation(raw.path("leftEvaluation"),input,input.path("leftSnapshotId").asText());var r=evaluation(raw.path("rightEvaluation"),input,input.path("rightSnapshotId").asText());var both=input.deepCopy();both.set("leftEvaluation",l);both.set("rightEvaluation",r);out.set("leftEvaluation",l);out.set("rightEvaluation",r);out.set("comparison",comparison(raw.path("comparison"),both));yield out;}
        case JD_DIAGNOSTIC -> diagnostic(raw,input);
        case STORY_DRAFTER -> story(raw,input);
        case DESIGN_STAGE_EVALUATOR -> design(raw,input);
    };}
}
