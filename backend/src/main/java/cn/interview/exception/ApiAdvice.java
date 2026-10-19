package cn.interview.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiAdvice {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", e.code);
        body.put("message", e.getMessage());
        if (e instanceof RecoveryException recoverable) body.putAll(recoverable.recovery());
        return ResponseEntity.status(e.status).body(body);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size(){return ResponseEntity.badRequest().body(Map.of("code","FILE_TOO_LARGE","message","单文件不能超过 5 MiB"));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class,MissingServletRequestPartException.class}) ResponseEntity<?> input(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","请求格式无效或缺少必填字段"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e){return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","本地服务处理失败，请检查数据目录权限与运行状态"));}
}
