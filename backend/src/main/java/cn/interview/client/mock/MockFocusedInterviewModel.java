package cn.interview.client.mock;

import cn.interview.validation.FocusedInterviewOutputValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅在显式 LLM_PROVIDER=mock 时启用，验证编排与界面，不代表真实模型质量。 */
public final class MockFocusedInterviewModel {
    final ObjectMapper json;
    public MockFocusedInterviewModel(ObjectMapper json){this.json=json;}
    public ObjectNode generate(String task,ObjectNode input){return switch(task){case "planner"->plan(input);case "interviewer"->interview(input);case "predictor"->predict(input);case "evaluator"->evaluate(input);default->throw new IllegalArgumentException("未知测试任务");};}
    ObjectNode plan(ObjectNode in){ObjectNode out=json.createObjectNode();ArrayNode questions=out.putArray("questions");String context=in.path("context").asText();boolean project=in.path("mode").asText().equals("PROJECT");String[] topics={"整体设计","实现细节","异常处理","方案权衡","数据一致性","性能分析","可观测性","扩展设计"};
        for(int i=0;i<in.path("totalQuestions").asInt();i++){
            String q=project?switch(i%3){case 0->"请结合项目资料，说明项目解决的问题、整体架构以及你负责的部分？";case 1->"请选一个你负责的核心流程，描述数据如何流转以及异常如何处理？";default->"假设项目流量增长十倍，你会先验证哪些瓶颈，如何比较替代方案？";}:"针对"+in.path("role").asText()+"岗位，请用一个具体例子说明你对"+topics[i]+"的理解？";
            questions.addObject().put("question",q).put("topic",topics[i]).put("level","L"+(i%3+1)).put("evidence",context.isBlank()?"通用岗位知识":context.substring(0,Math.min(context.length(),100)));
        }return out;}
    ObjectNode interview(ObjectNode in){String answer=in.path("messages").get(in.path("messages").size()-1).path("content").asText();String quote=answer.substring(0,Math.min(35,answer.length())).replaceAll("[\r\n]"," ");
        String q=in.path("followUpCount").asInt()==0?"你提到“"+quote+"”，能用一个具体实现或验证过程说明吗？":"假设你刚才描述的方案执行失败，你会怎样定位原因并保证资源或数据得到正确处理？";
        return json.createObjectNode().put("action","FOLLOW_UP").put("question",q).put("topic",in.path("currentTopic").asText()).put("reason","测试模式固定模拟两次追问");}
    ObjectNode predict(ObjectNode in){ObjectNode out=json.createObjectNode();ArrayNode questions=out.putArray("questions");String context=in.path("context").asText();String evidence=context.substring(0,Math.min(120,context.length()));String[] topics={"项目职责","整体架构","技术选型","核心流程","数据一致性","异常处理","性能指标","测试验证","方案权衡","项目改进"};
        for(String topic:topics){ObjectNode q=questions.addObject().put("question","请结合简历中你的实际工作，说明"+topic+"，并提供可以核实的细节？").put("evidence",evidence).put("knowledgePoint",topic).put("priority","高").put("answerOutline","测试示意：说明背景、个人职责、实现过程、证据与局限；未记录的信息标记需要补充。");q.putArray("followUps").add("你的具体职责是什么？").add("如何验证该结论？");}
        out.putArray("risks").add("测试模式不进行真实风险分析；项目指标、职责与成果需要本人确认。");return out;}
    ObjectNode evaluate(ObjectNode in){ObjectNode out=json.createObjectNode().put("summary","【Mock 测试报告】已完成问答流程。本报告只验证展示和持久化，不用于判断真实面试能力。");ArrayNode dims=out.putArray("dimensions");
        for(String name:FocusedInterviewOutputValidator.DIMENSIONS)dims.addObject().put("name",name).put("score",2).put("reason","测试示意分数，不代表真实能力；请配置真实模型获得评估。");
        Map<Integer,JsonNode> mains=new LinkedHashMap<>();Map<Integer,Boolean> answered=new LinkedHashMap<>();for(JsonNode m:in.path("messages")){int index=m.path("questionIndex").asInt();if(m.path("role").asText().equals("ASSISTANT")&&!m.path("followUp").asBoolean())mains.putIfAbsent(index,m);if(m.path("role").asText().equals("USER"))answered.put(index,true);}
        ArrayNode qs=out.putArray("questions");for(int index:answered.keySet()){ObjectNode q=qs.addObject().put("questionIndex",index).put("question",mains.get(index).path("content").asText()).put("answerOutline","先说明问题背景，再讲个人做法、验证依据和方案限制。").put("knowledgeExplanation","测试模式没有执行真实知识判断，请在真实模型模式下复盘。");q.putArray("correct");q.putArray("incorrect");q.putArray("missing").add("可以补充可验证的实现细节与边界条件。");q.putArray("uncertain").add("Mock 无法判断技术准确性。");q.putArray("evidence");}
        out.putArray("weaknesses").add("使用具体案例阐述实现与验证过程");out.putArray("suggestions").add("配置真实模型后围绕相同问题重新练习，并对照原始回答检查评价。");
        if(in.path("experienceVersion").asInt()==1&&!in.path("mainProject").path("hypothetical").asBoolean()){
            var matrix=out.putObject("matrix");var items=matrix.putArray("items");
            for(String key:cn.interview.service.material.ProjectEvidenceService.CHECKPOINT_IDS){var item=items.addObject().put("checkpointId",key).put("status","UNCOVERED").put("reason","Mock仅演示覆盖状态，不判断真实表现");var refs=item.putArray("evidence");
                if(key.equals("RESPONSIBILITY"))for(int i=0;i+1<in.path("messages").size();i++){var q=in.path("messages").get(i);var a=in.path("messages").get(i+1);if(q.path("role").asText().equals("ASSISTANT")&&a.path("role").asText().equals("USER")){item.put("status","PARTIAL");refs.addObject().put("questionMessageId",q.path("id").asText()).put("answerMessageId",a.path("id").asText()).put("questionQuote",q.path("content").asText()).put("answerQuote",a.path("content").asText().substring(0,Math.min(100,a.path("content").asText().length())));break;}}
            }
        }return out;}
}
