package cn.interview.domain.interview;

import cn.interview.exception.ApiException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** 按业务类型选择流程约束，复用同一会话编排与模型接入。 */
public enum InterviewFlowPolicy {
  COMPREHENSIVE {
    @Override public ObjectNode normalize(ObjectNode body) { return body.deepCopy(); }
    @Override public boolean autoStart() { return false; }
  },
  QUICK {
    @Override public ObjectNode normalize(ObjectNode body) {
      var out=body.deepCopy();
      for(String key:List.of("resumeId","jdId","jd","projectId","projectBrief"))
        if(out.has(key) && !out.path(key).isNull() && (!out.path(key).isTextual() || !out.path(key).asText().isBlank()))
          throw ApiException.bad("快速练习只使用岗位配置，不接收个人资料或JD");
      if(out.has("timeLimitMinutes") || (out.has("interviewMode") && !out.path("interviewMode").asText().equals("TRAINING")))
        throw ApiException.bad("快速练习不使用计时实战配置");
      if(!out.has("level"))out.put("level","JUNIOR");
      JsonNode count=out.path("plannedQuestionCount");
      if(out.has("plannedQuestionCount") && (!count.isIntegralNumber() || !count.canConvertToInt() || !Set.of(3,5,8).contains(count.asInt())))
        throw ApiException.bad("快速练习请选择3、5或8道主问题");
      if(!out.has("plannedQuestionCount"))out.put("plannedQuestionCount",5);
      return out;
    }
    @Override public boolean autoStart() { return true; }
  };

  public abstract ObjectNode normalize(ObjectNode body);
  public abstract boolean autoStart();
  public static InterviewFlowPolicy requested(JsonNode body) {
    if(!body.has("sessionType"))return COMPREHENSIVE;
    if(body.path("sessionType").isTextual()) {
      for(var policy:values())if(policy.name().equals(body.path("sessionType").asText()))return policy;
    }
    throw ApiException.bad("请选择有效面试类型");
  }
  public static InterviewFlowPolicy forSession(JsonNode session) {
    return session.path("sessionType").asText().equals("QUICK")?QUICK:COMPREHENSIVE;
  }
}
