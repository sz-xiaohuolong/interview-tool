package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 保持 V1 调用不变；新增调用在读取期间限制响应大小。 */
@Component
class V2LlmClient {
    final LlmClient legacy;final V2Store db;
    V2LlmClient(LlmClient legacy,V2Store db){this.legacy=legacy;this.db=db;}
    ObjectNode protocol(V2Task task) {
        try {
            String prompt=new ClassPathResource("v2/prompts/"+task.file()+".txt").getContentAsString(StandardCharsets.UTF_8);
            var p=db.object().put("rubricVersion","v2.1").put("promptVersion","v2.1").put("outputSchemaVersion","v2.1")
                .put("promptHash",V2SchemaPreflight.sha(prompt)).put("promptText",prompt).put("providerIdentity",V2SchemaPreflight.sha(legacy.provider+"|"+legacy.baseUrl))
                .put("requestedModel",legacy.isMock()?"EXPLICIT_MOCK_V2":legacy.model).put("mock",legacy.isMock());
            p.putNull("returnedModel");p.putObject("parameters").put("temperature",0.4).put("max_tokens",task.tokens()).put("reasoning_effort",legacy.reasoningEffort);
            return p;
        }catch(Exception e){throw new IllegalStateException("V2 固定提示词缺失",e);}
    }
    ObjectNode publicProtocol(JsonNode p){var out=((ObjectNode)p).deepCopy();out.remove("promptText");return out;}
    boolean executable(V2Task task,JsonNode p){var current=protocol(task);var old=((ObjectNode)p).deepCopy();old.putNull("returnedModel");old.remove(List.of("startedAt","finishedAt","finishReason","systemFingerprint"));return db.hash(current).equals(db.hash(old));}
    ObjectNode call(V2Task task,ObjectNode input,ObjectNode protocol) {
        if(input.toString().length()>60000)throw V2Exception.invalid("本次输入超过60000字符，请缩小所选范围");
        if(!executable(task,protocol))throw new V2Exception(409,"V2_PROTOCOL_CHANGED","当前模型或规则已变化，请显式建立新的评估分组。","CREATE_EVALUATION_GROUP");
        protocol.put("startedAt",Models.now());
        if(legacy.isMock()){protocol.put("returnedModel","EXPLICIT_MOCK_V2").put("finishedAt",Models.now()).put("finishReason","stop");return V2MockTasks.generate(task,input,db);}
        if(!"openai".equals(legacy.provider)||legacy.key.isBlank()||legacy.model.isBlank())throw new V2Exception(503,"MODEL_NOT_CONFIGURED","模型未配置；请检查本机配置，没有使用 Mock。","CHECK_CONFIGURATION");
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            String url=legacy.baseUrl.replaceAll("/+$","");if(!url.endsWith("/chat/completions"))url+="/chat/completions";
            URI uri=URI.create(url);if(!List.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new V2Exception(503,"MODEL_NOT_CONFIGURED","模型服务地址无效。","CHECK_CONFIGURATION");
            var payload=db.object().put("model",legacy.model).put("temperature",0.4).put("max_tokens",task.tokens());
            payload.putObject("response_format").put("type","json_object");
            if(!legacy.reasoningEffort.isBlank())payload.put("reasoning_effort",legacy.reasoningEffort);
            var messages=payload.putArray("messages");messages.addObject().put("role","system").put("content",protocol.path("promptText").asText());messages.addObject().put("role","user").put("content",input.toString());
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(legacy.timeout)).header("Content-Type","application/json").header("Authorization","Bearer "+legacy.key).POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            pending=legacy.http.sendAsync(request,info->new BoundedBody());var response=pending.get(legacy.timeout,TimeUnit.SECONDS);
            if(response.statusCode()==401||response.statusCode()==403)throw new V2Exception(503,"MODEL_AUTH_ERROR","模型认证失败，请检查本机密钥和权限。","CHECK_CONFIGURATION");
            if(response.statusCode()==429)throw new V2Exception(503,"MODEL_RATE_LIMITED","模型服务限流，输入已保存，请稍后显式重试。","RETRY_OPERATION");
            if(response.statusCode()<200||response.statusCode()>=300)throw failed("MODEL_HTTP_ERROR","模型服务请求失败，输入已保存。");
            JsonNode envelope=db.legacy.json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
            String finish=envelope.path("choices").path(0).path("finish_reason").asText();
            if(!finish.equals("stop"))throw failed("MODEL_INCOMPLETE_OUTPUT","模型未完整结束输出，没有保存为有效结果。");
            JsonNode content=envelope.path("choices").path(0).path("message").path("content");if(!content.isTextual())throw failed("MODEL_INVALID_OUTPUT","模型未返回文本 JSON。");
            JsonNode parsed=db.legacy.json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content.asText());if(parsed==null||!parsed.isObject())throw failed("MODEL_INVALID_OUTPUT","模型输出必须是完整 JSON 对象。");
            if(envelope.path("model").isTextual()&&envelope.path("model").asText().length()<=200)protocol.put("returnedModel",envelope.path("model").asText());
            if(envelope.path("system_fingerprint").isTextual()&&envelope.path("system_fingerprint").asText().length()<=200)protocol.put("systemFingerprint",envelope.path("system_fingerprint").asText());
            protocol.put("finishedAt",Models.now()).put("finishReason",finish);return (ObjectNode)parsed;
        }catch(V2Exception e){throw e;}catch(TimeoutException e){throw new V2Exception(504,"MODEL_TIMEOUT","模型超时，提交已保存；重试可能再次调用服务。","RETRY_OPERATION");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw failed("MODEL_INTERRUPTED","模型调用中断，输入已保存。");}
        catch(Exception e){throw failed("MODEL_INVALID_OUTPUT","连接或结构化输出校验失败，输入已保存；没有使用替代结果。");}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    static V2Exception failed(String code,String message){return new V2Exception(503,code,message,"RETRY_OPERATION");}
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> target=HttpResponse.BodySubscribers.ofByteArray();Flow.Subscription subscription;long count;boolean ended;
        public CompletionStage<byte[]> getBody(){return target.getBody();}
        public void onSubscribe(Flow.Subscription s){subscription=s;target.onSubscribe(s);}
        public void onNext(List<ByteBuffer> chunks){if(ended)return;for(ByteBuffer b:chunks)count+=b.remaining();if(count>2_000_000){ended=true;subscription.cancel();target.onError(new IllegalStateException("MODEL_RESPONSE_TOO_LARGE"));}else target.onNext(chunks);}
        public void onError(Throwable e){if(!ended){ended=true;target.onError(e);}}
        public void onComplete(){if(!ended){ended=true;target.onComplete();}}
    }
}
