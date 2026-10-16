package cn.interview.controller;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
/** 仅资源创建使用；状态取决于本次请求是否实际登记了新操作。 */
final class V2Responses {
    static Object created(String kind,String scope,JsonNode request,Supplier<Object> action){
        Object result=action.get();var attributes=RequestContextHolder.getRequestAttributes();boolean created=attributes!=null&&request.path("requestId").asText().equals(attributes.getAttribute("v2.created:"+kind+":"+scope,0));return ResponseEntity.status(created?201:200).body(result);
    }
}
