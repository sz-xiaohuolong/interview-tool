package cn.interview.task;

import cn.interview.client.training.ModelTask;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ModelTaskHandler {
    boolean supports(ModelTask task);
    void running(ObjectNode row,ObjectNode operation);
    void completed(ObjectNode row,ObjectNode result,ObjectNode operation);
    void failed(ObjectNode row,ObjectNode operation,boolean interrupted);
}
