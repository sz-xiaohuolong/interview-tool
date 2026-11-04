package cn.interview.client.mock;

import cn.interview.client.training.ModelTask;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试专用：生成显式 Mock 样本，供跨包校验与故障测试使用。 */
public final class TrainingMockTasksTestAccess {
    private TrainingMockTasksTestAccess() {}
    public static ObjectNode evaluation(JsonNode input,ObjectMapper json) {return MockTrainingTasks.evaluation(input,json);}
    public static ObjectNode comparison(ObjectMapper json) {return MockTrainingTasks.comparison(json);}
    public static ObjectNode generate(ModelTask task,ObjectNode input,ObjectMapper json) {return MockTrainingTasks.generate(task,input,json);}
}
