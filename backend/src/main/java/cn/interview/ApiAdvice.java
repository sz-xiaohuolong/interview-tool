package cn.interview;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.Map;

@RestControllerAdvice
public class ApiAdvice {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage()));}
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> size(){return ResponseEntity.badRequest().body(Map.of("code","FILE_TOO_LARGE","message","单文件不能超过 5 MiB"));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MissingServletRequestParameterException.class,MissingServletRequestPartException.class}) ResponseEntity<?> input(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","请求格式无效或缺少必填字段"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> error(Exception e){return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","本地服务处理失败，请检查数据目录权限与运行状态"));}
}
