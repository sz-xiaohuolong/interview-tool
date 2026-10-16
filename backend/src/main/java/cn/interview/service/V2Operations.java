package cn.interview.service;

import cn.interview.exception.V2Exception;
import cn.interview.mapper.V2Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

import static cn.interview.common.Models.*;

@Component
public class V2Operations {
    final V2Store db;
    V2Operations(V2Store db) { this.db=db; }

    ObjectNode replay(String kind,String scope,JsonNode request) {
        db.enabled();String requestId=required(request,"requestId",120);
        var ids=db.requestOperationIds(kind,scope,requestId);
        if(ids.isEmpty())return null;
        var op=db.get("v2_operations",ids.getFirst());
        if(!db.hash(request).equals(op.path("inputHash").asText()))
            throw V2Exception.conflict("V2_REQUEST_CONFLICT","相同请求 ID 对应不同输入，请恢复原输入，或使用新的操作。");
        return op;
    }
    ObjectNode write(String kind,String scope,JsonNode request,Supplier<ObjectNode> action) {
        return db.atomic(()->{
            var old=replay(kind,scope,request);if(old!=null)return (ObjectNode)old.path("result").deepCopy();
            var result=action.get();
            record(kind,scope,request,result,"SUCCEEDED");return result;
        });
    }
    ObjectNode record(String kind,String scope,JsonNode request,ObjectNode result,String state) {
        var op=db.object().put("id",id()).put("kind",kind).put("scopeKey",scope).put("resourceType",kind)
                .put("resourceId",result.path("id").asText("LOCAL_USER")).put("requestId",required(request,"requestId",120))
                .put("inputHash",db.hash(request)).put("state",state).put("generation",0).put("expectedRevision",result.path("revision").asInt());
        op.set("result",result.deepCopy());db.insert("v2_operations",op);
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();if(attributes!=null)attributes.setAttribute("v2.created:"+kind+":"+scope,request.path("requestId").asText(),0);
        return op;
    }
    ObjectNode history(String table,String scope,String cursor,int limit){
        if(!java.util.Set.of("v2_review_items","v2_sprints").contains(table))throw V2Exception.invalid("不支持的历史来源");db.get(table,scope);var page=db.page("v2_operations",cursor,limit,"SUCCEEDED","scope_key",scope);for(JsonNode row:page.path("items"))((ObjectNode)row).remove(java.util.List.of("result","input","protocol","inputHash","requestId"));return page;
    }
    ObjectNode historyDetail(String table,String scope,String operationId){db.get(table,scope);var op=db.get("v2_operations",operationId);if(!op.path("scopeKey").asText().equals(scope)||!op.path("state").asText().equals("SUCCEEDED"))throw V2Exception.invalid("操作不属于此历史来源");return (ObjectNode)op.path("result").deepCopy();}
    ObjectNode safe(String id) {
        var op=db.get("v2_operations",id);var out=db.object();
        for(String key:java.util.List.of("id","kind","resourceId","state","generation","createdAt","updatedAt","error"))if(op.has(key))out.set(key,op.get(key));
        return out;
    }
}
