package cn.interview.common;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public final class Models {
    private Models() {}
    public static String id() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }
    public static String required(JsonNode body,String key,int max) {
        JsonNode n=body.path(key);
        if (!n.isTextual() || n.asText().isBlank()) throw ApiException.bad(key+" 不能为空");
        if(n.asText().length()>max) throw ApiException.bad(key+" 超过允许的长度 "+max);
        return n.asText().strip();
    }
    public static String optional(JsonNode body,String key,String fallback,int max) {
        if(!body.hasNonNull(key))return fallback;
        if(!body.path(key).isTextual())throw ApiException.bad(key+" 必须是文本");
        String value=body.path(key).asText().strip();
        if(value.length()>max)throw ApiException.bad(key+" 超过允许的长度 "+max);
        return value.isEmpty()?fallback:value;
    }
    public static String choice(String value,String... choices) {
        for(String c:choices)if(c.equals(value))return value;
        throw ApiException.bad("不支持的选项："+value);
    }
}
