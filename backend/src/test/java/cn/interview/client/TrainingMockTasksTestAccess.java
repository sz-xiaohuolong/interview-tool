package cn.interview.client;

import cn.interview.mapper.TrainingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试专用：生成显式 Mock 样本，供跨包校验与故障测试使用。 */
public final class TrainingMockTasksTestAccess {
    private TrainingMockTasksTestAccess() {}
    public static ObjectNode evaluation(JsonNode input,TrainingStore db) {return MockTrainingTasks.evaluation(input,db);}
    public static ObjectNode comparison(TrainingStore db) {return MockTrainingTasks.comparison(db);}
    public static ObjectNode generate(ModelTask task,ObjectNode input,TrainingStore db) {return MockTrainingTasks.generate(task,input,db);}
}
