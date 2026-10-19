package cn.interview.task;

import cn.interview.client.training.ModelTask;
import cn.interview.client.training.TrainingModelClient;
import cn.interview.exception.ApiException;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;
import cn.interview.service.training.OperationService;
import cn.interview.validation.TrainingOutputValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static cn.interview.common.Models.*;

/** 仅协调固定任务；数据库事务不跨越网络等待。 */
@Component
public class ModelTaskRunner {
    public record Work(String table,ObjectNode resource,ObjectNode input){}
    private final TrainingStore db;private final OperationService ops;private final TrainingModelClient llm;private final TrainingOutputValidator validator;private final ModelCapacity capacity;private final ObjectProvider<ModelTaskHandler> handlers;
    public ModelTaskRunner(TrainingStore db,OperationService ops,TrainingModelClient llm,TrainingOutputValidator validator,ModelCapacity capacity,ObjectProvider<ModelTaskHandler> handlers){this.db=db;this.ops=ops;this.llm=llm;this.validator=validator;this.capacity=capacity;this.handlers=handlers;}
    public boolean isMock() { return llm.isMock(); }
    public ObjectNode protocol(ModelTask task) { return llm.protocol(task); }
    public ObjectNode publicProtocol(JsonNode protocol) { return llm.publicProtocol(protocol); }
    ModelTaskHandler handler(ModelTask task){return handlers.stream().filter(h->h.supports(task)).findFirst().orElseThrow(()->new IllegalStateException("缺少固定任务处理器"));}
    public ObjectNode run(ModelTask task,String kind,String scope,JsonNode request,Supplier<Work> reserve){
        var replay=ops.replay(kind,scope,request);if(replay!=null)return result(replay);
        try(var lease=capacity.acquire()){
            ObjectNode op=db.atomic(()->{
                var prior=ops.replay(kind,scope,request);if(prior!=null)return prior;
                Work work=reserve.get();if(work.input.toString().length()>60000)throw RecoveryException.invalid("输入超过60000字符，请缩小范围；没有封存提交");
                var created=ops.record(kind,scope,request,db.object().put("id",work.resource.path("id").asText()).put("revision",work.resource.path("revision").asInt()),"RUNNING");
                created.put("task",task.name()).put("resourceTable",work.table);created.set("input",work.input.deepCopy());created.set("protocol",llm.protocol(task));
                var row=work.resource;int revision=row.path("revision").asInt();row.put("operationId",created.path("id").asText());handler(task).running(row,created);
                db.save(work.table,row,revision);created.put("expectedRevision",row.path("revision").asInt());saveOperation(created,"RUNNING",0);return created;
            });
            if(!op.path("state").asText().equals("RUNNING"))return result(op);
            return execute(op);
        }
    }
    ObjectNode execute(ObjectNode op){
        ModelTask task=ModelTask.valueOf(op.path("task").asText());int generation=op.path("generation").asInt();
        if(current(op,generation)==null)throw RecoveryException.conflict("V2_OPERATION_SUPERSEDED","操作代次已变化，未发起旧请求。");
        ObjectNode validated;var protocol=((ObjectNode)op.path("protocol")).deepCopy();
        try {var raw=llm.call(task,(ObjectNode)op.path("input"),protocol);validated=validator.validate(task,(ObjectNode)op.path("input"),raw);}
        catch(RuntimeException failure){
            ApiException error=failure instanceof ApiException known?known:TrainingModelClient.failed("MODEL_INVALID_OUTPUT","模型处理未完成，没有保存有效结果；可显式重试。 ");
            ObjectNode failed;
            try {failed=db.atomic(()->{var current=current(op,generation);if(current==null)return null;var resource=db.get(op.path("resourceTable").asText(),op.path("resourceId").asText());checkResource(resource,op);handler(task).failed(resource,current,false);db.save(op.path("resourceTable").asText(),resource,resource.path("revision").asInt());current.put("expectedRevision",resource.path("revision").asInt()).put("state","FAILED");current.set("error",db.object().put("code",error.code).put("message",error.getMessage()).put("status",error.status).put("nextAction",error instanceof RecoveryException e?String.valueOf(e.recovery().get("nextAction")):"RETRY_OPERATION"));saveOperation(current,"RUNNING",generation);return current;});}catch(RuntimeException storage){throw new RecoveryException(503,"V2_STORAGE_FAILED","评估失败且状态未能完整保存，请检查存储并重启恢复。","CHECK_STORAGE").detail("operationId",op.path("id").asText()).detail("resourceId",op.path("resourceId").asText());}
            if(failed==null)throw RecoveryException.conflict("V2_OPERATION_SUPERSEDED","操作代次已变化，旧失败不影响当前评估。");
            throw recovery(failed);
        }
        try { return db.atomic(()->{
            var current=current(op,generation);if(current==null)throw RecoveryException.conflict("V2_OPERATION_SUPERSEDED","该结果已过期，未覆盖当前版本。");
            var row=db.get(op.path("resourceTable").asText(),op.path("resourceId").asText());checkResource(row,op);int revision=row.path("revision").asInt();
            current.set("protocol",protocol);handler(task).completed(row,validated,current);db.save(op.path("resourceTable").asText(),row,revision);
            current.put("state","SUCCEEDED");current.remove("error");saveOperation(current,"RUNNING",generation);return row;
        }); }catch(RuntimeException storage){
            if(storage instanceof RecoveryException error&&error.code.equals("V2_OPERATION_SUPERSEDED"))throw error;
            try {db.atomic(()->{var current=current(op,generation);if(current!=null){var row=db.get(op.path("resourceTable").asText(),op.path("resourceId").asText());checkResource(row,op);handler(task).failed(row,current,false);db.save(op.path("resourceTable").asText(),row,row.path("revision").asInt());current.put("expectedRevision",row.path("revision").asInt()).put("state","FAILED");current.set("error",db.object().put("code","V2_STORAGE_FAILED").put("status",503).put("message","结果未能完整保存；请检查本机存储，再显式重试评估。").put("nextAction","CHECK_STORAGE"));saveOperation(current,"RUNNING",generation);}return null;});}catch(RuntimeException ignored){ /* 存储不可写时仍返回已持久化操作的恢复指针。 */ }
            throw new RecoveryException(503,"V2_STORAGE_FAILED","结果未能完整保存。请保留输入，检查存储；若操作仍执行中，停止服务后修复存储并重启，再显式重试。","CHECK_STORAGE").detail("operationId",op.path("id").asText()).detail("resourceId",op.path("resourceId").asText());
        }
    }
    void checkResource(JsonNode row,JsonNode op){if(row.path("revision").asInt()!=op.path("expectedRevision").asInt()||!row.path("operationId").asText().equals(op.path("id").asText()))throw RecoveryException.conflict("V2_OPERATION_SUPERSEDED","资源已变化，旧结果不能提交。");}
    ObjectNode current(JsonNode expected,int generation){var current=db.get("v2_operations",expected.path("id").asText());return current.path("state").asText().equals("RUNNING")&&current.path("generation").asInt()==generation&&current.path("inputHash").asText().equals(expected.path("inputHash").asText())?current:null;}
    void saveOperation(ObjectNode op,String expectedState,int expectedGeneration){
        op.put("updatedAt",now());int count=db.updateOperation(op,expectedState,expectedGeneration);
        if(count!=1)throw RecoveryException.conflict("V2_OPERATION_SUPERSEDED","操作代次已变化，请读取当前状态。");
    }
    public ObjectNode result(ObjectNode op){
        if(op.path("state").asText().equals("SUCCEEDED"))return db.get(op.path("resourceTable").asText(),op.path("resourceId").asText());
        if(List.of("FAILED","INTERRUPTED").contains(op.path("state").asText()))throw recovery(op);
        throw new RecoveryException(409,"V2_RESOURCE_BUSY","正在评估同一资源，请查看当前操作。","WAIT_OPERATION").detail("operationId",op.path("id").asText()).detail("resourceId",op.path("resourceId").asText());
    }
    RecoveryException recovery(ObjectNode op){return new RecoveryException(op.path("error").path("status").asInt(503),op.path("error").path("code").asText("V2_OPERATION_INTERRUPTED"),op.path("error").path("message").asText("上次操作中断，输入已保存；请显式重试。"),op.path("error").path("nextAction").asText("RETRY_OPERATION")).detail("retryable",true).detail("operationId",op.path("id").asText()).detail("resourceId",op.path("resourceId").asText());}
    public ObjectNode retry(String id,JsonNode body){
        db.enabled();var old=ops.replay("MODEL_RETRY",id,body);if(old!=null)return ops.safe(id);
        try(var lease=capacity.acquire()){
            var op=db.atomic(()->{
                var duplicate=ops.replay("MODEL_RETRY",id,body);if(duplicate!=null)return (ObjectNode)null;
                var current=db.get("v2_operations",id);String state=current.path("state").asText();
                if(!List.of("FAILED","INTERRUPTED").contains(state))throw RecoveryException.conflict("V2_INVALID_STATE","只有失败或中断的评估可以重试；已完成结果请显式新建评估组。");
                int generation=current.path("generation").asInt();if(!body.path("expectedGeneration").isIntegralNumber()||body.path("expectedGeneration").asInt()!=generation)throw RecoveryException.conflict("V2_GENERATION_CONFLICT","操作代次已变化，请查看最新状态。");
                ModelTask task=ModelTask.valueOf(current.path("task").asText());if(!llm.executable(task,current.path("protocol")))throw new RecoveryException(409,"V2_PROTOCOL_CHANGED","模型或规则已变化，请建立新的评估分组。","CREATE_EVALUATION_GROUP");
                var resource=db.get(current.path("resourceTable").asText(),current.path("resourceId").asText());checkResource(resource,current);int revision=resource.path("revision").asInt();
                current.put("state","RUNNING").put("generation",generation+1);handler(task).running(resource,current);db.save(current.path("resourceTable").asText(),resource,revision);current.put("expectedRevision",resource.path("revision").asInt());current.remove("error");saveOperation(current,state,generation);
                ops.record("MODEL_RETRY",id,body,db.object().put("id",id),"SUCCEEDED");return current;
            });
            if(op!=null)execute(op);return ops.safe(id);
        }
    }
    @EventListener(ApplicationReadyEvent.class) void recover(){
        if(!db.isEnabled())return;
        db.atomic(()->{var ids=db.pendingOperationIds();for(String id:ids){var op=db.get("v2_operations",id);if(!op.has("task"))continue;String state=op.path("state").asText();int generation=op.path("generation").asInt();var resource=db.get(op.path("resourceTable").asText(),op.path("resourceId").asText());handler(ModelTask.valueOf(op.path("task").asText())).failed(resource,op,true);db.save(op.path("resourceTable").asText(),resource,resource.path("revision").asInt());op.put("expectedRevision",resource.path("revision").asInt()).put("state","INTERRUPTED");saveOperation(op,state,generation);}return null;});
    }
}
