package cn.interview.client.training;

import cn.interview.client.mock.MockTrainingTasks;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.common.ContentHash;
import cn.interview.common.Models;
import cn.interview.dto.ModelSettings;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 固定训练任务的提示词与评估协议适配；网络传输复用全站客户端。 */
@Component
public class TrainingModelClient {
    private final java.util.function.Supplier<ModelSettings> connections;
    private final OpenAiJsonClient transport;
    private final int timeout;
    final TrainingStore db;
    @org.springframework.beans.factory.annotation.Autowired
    public TrainingModelClient(cn.interview.service.model.ModelConfigurationService settings, TrainingStore db,
            OpenAiJsonClient transport, @org.springframework.beans.factory.annotation.Value("${app.llm.timeout-seconds}") int timeout) {
        this(settings::connection, db, transport, timeout);
    }
    public TrainingModelClient(java.util.function.Supplier<ModelSettings> connections, TrainingStore db,
            OpenAiJsonClient transport, int timeout) {
        this.connections=connections; this.db=db; this.transport=transport;
        this.timeout=Math.max(1,Math.min(300,timeout));
    }
    public ObjectNode protocol(ModelTask task) { return protocol(task, connections.get()); }
    private ObjectNode protocol(ModelTask task, ModelSettings settings) {
        try {
            String prompt=new ClassPathResource("prompts/training/"+task.file()+".txt").getContentAsString(StandardCharsets.UTF_8);
            var p=db.object().put("rubricVersion","v2.1").put("promptVersion","v2.1").put("outputSchemaVersion","v2.1")
                .put("promptHash",ContentHash.sha(prompt)).put("promptText",prompt).put("providerIdentity",ContentHash.sha(settings.provider()+"|"+settings.baseUrl()))
                .put("requestedModel",settings.isMock()?"EXPLICIT_MOCK_V2":settings.model()).put("mock",settings.isMock());
            p.putNull("returnedModel");p.putObject("parameters").put("temperature",0.4).put("max_tokens",task.tokens()).put("reasoning_effort",settings.effort(false));
            return p;
        }catch(Exception e){throw new IllegalStateException("专项训练 固定提示词缺失",e);}
    }
    public ObjectNode publicProtocol(JsonNode p){var out=((ObjectNode)p).deepCopy();out.remove("promptText");return out;}
    public boolean executable(ModelTask task,JsonNode p){return executable(task,p,connections.get());}
    private boolean executable(ModelTask task,JsonNode p,ModelSettings settings){var current=protocol(task,settings);var old=((ObjectNode)p).deepCopy();old.putNull("returnedModel");old.remove(List.of("startedAt","finishedAt","finishReason","systemFingerprint"));return db.hash(current).equals(db.hash(old));}
    public ObjectNode call(ModelTask task,ObjectNode input,ObjectNode protocol) {
        var settings=connections.get();
        if(input.toString().length()>60000)throw RecoveryException.invalid("本次输入超过60000字符，请缩小所选范围");
        if(!executable(task,protocol,settings))throw new RecoveryException(409,"V2_PROTOCOL_CHANGED","当前模型或规则已变化，请显式建立新的评估分组。","CREATE_EVALUATION_GROUP");
        protocol.put("startedAt",Models.now());
        if(settings.isMock()){protocol.put("returnedModel","EXPLICIT_MOCK_V2").put("finishedAt",Models.now()).put("finishReason","stop");return MockTrainingTasks.generate(task,input,db);}
        if(!"openai".equals(settings.provider())||settings.apiKey().isBlank()||settings.model().isBlank())throw new RecoveryException(503,"MODEL_NOT_CONFIGURED","模型未配置；请检查本机配置，没有使用 Mock。","CHECK_CONFIGURATION");
        try {
            var response=transport.exchange(settings,protocol.path("promptText").asText(),input,task.tokens(),timeout,false,usage -> {});
            String finish=response.finishReason();
            if(!"stop".equals(finish))throw failed("MODEL_INCOMPLETE_OUTPUT","模型未完整结束输出，没有保存为有效结果。");
            if(response.text()==null)throw failed("MODEL_INVALID_OUTPUT","模型未返回文本 JSON。");
            JsonNode parsed=db.json().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.text());if(parsed==null||!parsed.isObject())throw failed("MODEL_INVALID_OUTPUT","模型输出必须是完整 JSON 对象。");
            if(response.model()!=null&&response.model().length()<=200)protocol.put("returnedModel",response.model());
            if(response.fingerprint()!=null&&response.fingerprint().length()<=200)protocol.put("systemFingerprint",response.fingerprint());
            protocol.put("finishedAt",Models.now()).put("finishReason",finish);return (ObjectNode)parsed;
        }catch(RecoveryException e){throw e;}
        catch(cn.interview.exception.ApiException e){throw new RecoveryException(e.status,e.code,e.getMessage(),e.code.equals("MODEL_AUTH_ERROR")||e.code.equals("MODEL_NOT_CONFIGURED")?"CHECK_CONFIGURATION":"RETRY_OPERATION");}
        catch(Exception e){throw failed("MODEL_INVALID_OUTPUT","连接或结构化输出校验失败，输入已保存；没有使用替代结果。");}
    }
    public static RecoveryException failed(String code,String message){return new RecoveryException(503,code,message,"RETRY_OPERATION");}

    public boolean isMock() {return connections.get().isMock();}

}
