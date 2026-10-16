package cn.interview.service;

import cn.interview.client.V2Task;
import com.fasterxml.jackson.databind.node.ObjectNode;

interface V2ModelHandler {
    boolean supports(V2Task task);
    void running(ObjectNode row,ObjectNode operation);
    void completed(ObjectNode row,ObjectNode result,ObjectNode operation);
    void failed(ObjectNode row,ObjectNode operation,boolean interrupted);
}
