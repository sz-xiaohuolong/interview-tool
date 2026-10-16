package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/** 唯一会话 Bean 的增量实现；普通 V1 请求完全沿用父类。 */
public class TrainingLinkedInterviewService extends InterviewService {
    private final Environment environment;
    private final ObjectProvider<V2TrainingBridge> bridge;

    public TrainingLinkedInterviewService(Store store, MaterialService materials, LlmClient llm, ObjectMapper json,
                                         Environment environment, ObjectProvider<V2TrainingBridge> bridge) {
        super(store, materials, llm, json);
        this.environment = environment;
        this.bridge = bridge;
    }

    @Override public synchronized ObjectNode create(JsonNode body) {
        if (!body.hasNonNull("trainingLinkId")) return super.create(body);
        if (!body.path("trainingLinkId").isTextual()) throw V2Exception.invalid("trainingLinkId 必须为文本");
        if (body.path("trainingLinkId").asText().isBlank()) return super.create(body);
        if (!V2BootstrapConfiguration.enabled(environment)) throw V2Exception.disabled();
        var extension = bridge.getIfAvailable();
        if (extension == null) throw new V2Exception(404, "V2_SOURCE_NOT_FOUND", "训练来源不存在，请返回原复习项重新选择。", "LOAD_LATEST");
        return extension.create(this, body);
    }

    interface V2TrainingBridge { ObjectNode create(TrainingLinkedInterviewService service, JsonNode body); }
}
