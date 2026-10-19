package cn.interview.service;

import cn.interview.client.ModelTask;
import com.fasterxml.jackson.databind.node.ObjectNode;

interface ModelTaskHandler {
    boolean supports(ModelTask task);
    void running(ObjectNode row,ObjectNode operation);
    void completed(ObjectNode row,ObjectNode result,ObjectNode operation);
    void failed(ObjectNode row,ObjectNode operation,boolean interrupted);
}
