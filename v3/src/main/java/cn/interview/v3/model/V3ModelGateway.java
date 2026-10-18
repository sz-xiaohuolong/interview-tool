package cn.interview.v3.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;

/** 隔离模型接入，业务流程负责状态、预算和用户确认。 */
public interface V3ModelGateway {
  ObjectNode call(String stage, ObjectNode input, int timeoutSeconds);

  /** 用量独立于正文有效性上报；没有服务端用量时不估算。 */
  default ObjectNode call(String stage, ObjectNode input, int timeoutSeconds, Consumer<ObjectNode> reportUsage) {
    ObjectNode result = call(stage, input, timeoutSeconds);
    if (result.remove("_modelUsage") instanceof ObjectNode usage) reportUsage.accept(usage);
    return result;
  }
}
