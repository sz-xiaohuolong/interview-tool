package cn.interview.service.training;

import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import static cn.interview.common.Models.now;

/** 删除练习入口，保留原报告及审计快照；同题关联在一个事务中移除。 */
@Service
public class TrainingRecordRemovalService {
    final TrainingStore db;final OperationService ops;
    TrainingRecordRemovalService(TrainingStore db,OperationService ops){this.db=db;this.ops=ops;}

    public ObjectNode practice(String id,JsonNode body){return ops.write("PRACTICE_DELETE",id,body,()->{
        var row=db.get("v2_practice_cases",id);db.expect(row,body);idle(row);
        remove("v2_practice_cases",row);
        return result(row);
    });}
    public ObjectNode review(String id,JsonNode body){return ops.write("REVIEW_DELETE",id,body,()->{
        var row=db.get("v2_review_items",id);db.expect(row,body);
        var related=new ArrayList<ObjectNode>();
        for(String cid:db.ids("v2_practice_cases","source_id",row.path("sourceSessionId").asText())){
            var c=db.get("v2_practice_cases",cid);
            if(c.path("sourceKind").asText().equals("SESSION")&&c.path("sourceSessionKind").asText("SESSION").equals("SESSION")
                &&c.path("questionIndex").asInt(-1)==row.path("questionIndex").asInt()&&c.path("mock").asBoolean()==row.path("mock").asBoolean()){
                idle(c);related.add(c);
            }
        }
        // 所有可写状态检查先完成，避免部分删除。
        related.forEach(c->remove("v2_practice_cases",c));remove("v2_review_items",row);
        return result(row);
    });}
    private void idle(ObjectNode c){
        if(Set.of("RUNNING","PENDING").contains(c.path("status").asText()))busy();
        for(String table:List.of("v2_practice_attempts","v2_practice_evaluation_groups","v2_practice_comparisons"))
            for(String id:db.ids(table,"case_id",c.path("id").asText()))
                if(Set.of("RUNNING","PENDING","EVALUATING","SUBMITTED").contains(db.get(table,id).path("status").asText()))busy();
    }
    private void busy(){throw RecoveryException.conflict("V2_RESOURCE_BUSY","这道题正在生成、评估或对比，请等待完成后再删除。");}
    private void remove(String table,ObjectNode row){row.put("deletedAt",now());db.save(table,row,row.path("revision").asInt());}
    private ObjectNode result(ObjectNode row){return db.object().put("id",row.path("id").asText()).put("deletedAt",row.path("deletedAt").asText()).put("revision",row.path("revision").asInt());}
}
