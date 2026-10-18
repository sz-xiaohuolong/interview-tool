package cn.interview.v3.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 隔离模型接入，业务流程负责状态、预算和用户确认。 */
public interface V3ModelGateway {
  ObjectNode call(String stage, ObjectNode input, int timeoutSeconds);
}
