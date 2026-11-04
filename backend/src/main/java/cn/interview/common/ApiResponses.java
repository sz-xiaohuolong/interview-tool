package cn.interview.common;
import org.springframework.http.ResponseEntity;
/** 仅将显式业务结果映射为 HTTP 创建或重放响应。 */
public final class ApiResponses {
    public static ResponseEntity<Object> created(CreationResult result){
        return ResponseEntity.status(result.created()?201:200).body(result.body());
    }
}
