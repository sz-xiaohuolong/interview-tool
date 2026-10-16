package cn.interview;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.LinkedHashMap;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class V2ApiAdvice {
    @ExceptionHandler(V2Exception.class)
    ResponseEntity<?> handle(V2Exception error) {
        var body = new LinkedHashMap<String, Object>();
        body.put("code", error.code);
        body.put("message", error.getMessage());
        body.putAll(error.recovery);
        return ResponseEntity.status(error.status).body(body);
    }
}
