package cn.interview.service.material;

import cn.interview.exception.ApiException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 校验七项经历覆盖及原始问答引用，不以技术来源状态代替回答证据。 */
@Service
public class ProjectEvidenceService {
    public static final List<String> CHECKPOINT_IDS = List.of("BUSINESS", "RESPONSIBILITY", "ARCHITECTURE", "TECH_CHOICE", "FAILURE", "PERFORMANCE", "TRADEOFF");
    public static final List<String> TITLES = List.of("业务背景与指标", "个人真实职责", "架构与调用链路", "关键技术选型依据", "异常与故障处理", "性能/压力证据", "妥协与替代方案");
    private final ObjectMapper json;

    public ProjectEvidenceService(ObjectMapper json) { this.json = json; }

    private ApiException invalid() { return new ApiException(503, "MODEL_INVALID_OUTPUT", "项目矩阵结构或原始问答引用无效，未保存评估；回答已保留，可显式重试复盘。"); }
    private String text(JsonNode value, int limit) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit) throw invalid();
        return value.asText();
    }
    public boolean applicable(JsonNode project) {
        return project.isObject() && !project.path("hypothetical").asBoolean() && !project.path("brief").asText().isBlank();
    }
    private ObjectNode matrix(JsonNode project) {
        var out = json.createObjectNode().put("version", 1);
        out.putObject("project").put("id", project.path("id").asText()).put("name", project.path("name").asText("本场主项目"));
        out.put("notice", "仅反映本场已提交文字中的经历证据；未覆盖不代表能力不足，已讲清也不等于外部真实性认证。");
        out.putArray("items");
        return out;
    }

    /** 没有任何已提交回答时，只记录覆盖缺失，不调用模型评价能力。 */
    public ObjectNode uncovered(JsonNode project) {
        if (!applicable(project)) return null;
        var out = matrix(project);
        for (int i = 0; i < CHECKPOINT_IDS.size(); i++) {
            out.withArray("items").addObject().put("checkpointId", CHECKPOINT_IDS.get(i)).put("title", TITLES.get(i))
                    .put("status", "UNCOVERED").put("reason", "本场尚无已提交回答，未取得本项经历证据；不能据此判断能力。").putArray("evidence");
        }
        return out;
    }

    public ObjectNode normalize(JsonNode rawMatrix, JsonNode messages, JsonNode project) {
        if (!applicable(project)) return null;
        if (!rawMatrix.isObject() || !rawMatrix.path("items").isArray() || rawMatrix.path("items").size() != 7 || !messages.isArray()) throw invalid();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        Map<String, String> answerForQuestion = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i); String id = text(message.path("id"), 100);
            if (byId.put(id, message) != null) throw invalid();
            if (message.path("role").asText().equals("ASSISTANT") && i + 1 < messages.size()) {
                JsonNode answer = messages.get(i + 1);
                if (answer.path("role").asText().equals("USER")) {
                    if (message.hasNonNull("questionId") && !message.path("questionId").asText().equals(answer.path("questionId").asText())) throw invalid();
                    answerForQuestion.put(id, answer.path("id").asText());
                }
            }
        }
        var out = matrix(project); Set<String> seen = new HashSet<>(); Map<String, ObjectNode> rows = new LinkedHashMap<>();
        for (JsonNode raw : rawMatrix.path("items")) {
            String key = text(raw.path("checkpointId"), 40), status = text(raw.path("status"), 20);
            int index = CHECKPOINT_IDS.indexOf(key);
            if (index < 0 || !seen.add(key) || !Set.of("CLEAR", "PARTIAL", "UNCOVERED").contains(status)) throw invalid();
            if (!raw.path("evidence").isArray() || raw.path("evidence").size() > 8) throw invalid();
            if (status.equals("UNCOVERED") != raw.path("evidence").isEmpty()) throw invalid();
            var row = json.createObjectNode().put("checkpointId", key).put("title", TITLES.get(index)).put("status", status).put("reason", text(raw.path("reason"), 1500));
            var evidence = row.putArray("evidence"); Set<String> references = new HashSet<>();
            for (JsonNode ref : raw.path("evidence")) {
                String qid = text(ref.path("questionMessageId"), 100), aid = text(ref.path("answerMessageId"), 100);
                JsonNode question = byId.get(qid), answer = byId.get(aid);
                if (question == null || answer == null || !question.path("role").asText().equals("ASSISTANT") || !answer.path("role").asText().equals("USER")
                        || !aid.equals(answerForQuestion.get(qid)) || !references.add(qid + ":" + aid)) throw invalid();
                String qquote = text(ref.path("questionQuote"), 2000), aquote = text(ref.path("answerQuote"), 3000);
                if (!question.path("content").asText().contains(qquote) || !answer.path("content").asText().contains(aquote)) throw invalid();
                evidence.addObject().put("questionMessageId", qid).put("answerMessageId", aid).put("questionQuote", qquote).put("answerQuote", aquote);
            }
            if (status.equals("UNCOVERED")) row.put("reason", "本场未取得该检查点的已提交回答证据；未覆盖不代表能力不足。");
            rows.put(key, row);
        }
        for (String key : CHECKPOINT_IDS) out.withArray("items").add(rows.get(key));
        return out;
    }
}
