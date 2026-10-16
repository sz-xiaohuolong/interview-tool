package cn.interview.client;

import cn.interview.mapper.V2Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试专用：生成显式 Mock 样本，供跨包校验与故障测试使用。 */
public final class V2MockTasksTestAccess {
    private V2MockTasksTestAccess() {}
    public static ObjectNode evaluation(JsonNode input,V2Store db) {return V2MockTasks.evaluation(input,db);}
    public static ObjectNode comparison(V2Store db) {return V2MockTasks.comparison(db);}
    public static ObjectNode generate(V2Task task,ObjectNode input,V2Store db) {return V2MockTasks.generate(task,input,db);}
}
