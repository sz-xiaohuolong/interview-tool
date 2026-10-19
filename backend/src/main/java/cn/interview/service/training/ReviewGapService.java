package cn.interview.service.training;

import cn.interview.client.training.ModelTask;
import cn.interview.common.ContentHash;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.TrainingStore;
import cn.interview.service.material.ProjectEvidenceSource;
import cn.interview.task.ModelTaskHandler;
import cn.interview.task.ModelTaskRunner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import static cn.interview.common.Models.required;

/** 从原答内容缺口生成新题，复用既有固定任务与单题恢复，不修改源报告。 */
@Service
public class ReviewGapService implements ModelTaskHandler {
    private final TrainingStore db;
    private final ModelTaskRunner runner;
    private final PracticeService practice;
    private final ObjectProvider<ProjectEvidenceSource> sources;

    public ReviewGapService(TrainingStore db, ModelTaskRunner runner, PracticeService practice, ObjectProvider<ProjectEvidenceSource> sources) {
        this.db = db; this.runner = runner; this.practice = practice; this.sources = sources;
    }
    private ObjectNode source(String kind, String id) {
        db.enabled();
        if (!kind.equals("V3")) throw RecoveryException.invalid("当前只支持综合面试综合复盘的内容补练");
        var provider = sources.stream().filter(s -> s.supports(kind)).findFirst()
                .orElseThrow(() -> RecoveryException.invalid("当前运行环境没有综合面试来源模块"));
        var source = provider.source(id);
        if (!source.path("status").asText().equals("REPORT_READY") || source.path("reviewVersion").asInt() != 2 || source.path("report").path("version").asInt() != 2)
            throw RecoveryException.conflict("V2_SOURCE_NOT_READY", "该场次没有新版复盘，不会自动重算历史");
        return source;
    }
    private JsonNode finding(JsonNode source, String qid) {
        for (var f : source.path("report").path("findings")) if (f.path("questionId").asText().equals(qid)) {
            if (!f.path("answerStatus").asText().equals("PARTIAL")) throw RecoveryException.invalid("请从回答待补强项开始定向补练");
            return f;
        }
        throw RecoveryException.invalid("该题没有本场已答的内容缺口");
    }
    private ObjectNode find(String sourceId, String qid) {
        for (String id : db.ids("v2_practice_cases", "source_id", sourceId)) {
            var row = db.get("v2_practice_cases", id);
            if (row.path("contextOrigin").asText().equals("REVIEW_GAP") && row.path("sourceSessionKind").asText().equals("V3")
                    && row.path("sourceQuestionId").asText().equals(qid)) return row;
        }
        return null;
    }
    private ObjectNode view(ObjectNode row) {
        var out = db.object().put("caseId", row.path("id").asText()).put("questionId", row.path("sourceQuestionId").asText())
                .put("sourceId", row.path("sourceId").asText()).put("status", row.path("status").asText());
        if (row.hasNonNull("operationId")) out.set("operationId", row.path("operationId"));
        var attempts = db.recentAttemptIds(row.path("id").asText());
        if (!attempts.isEmpty()) {
            var a = db.get("v2_practice_attempts", attempts.getFirst());
            out.putObject("latestAttempt").put("id", a.path("id").asText()).put("status", a.path("status").asText());
        }
        return out;
    }
    public ObjectNode list(String kind, String id) {
        source(kind, id); var out = db.object(); var items = out.putArray("items");
        for (String cid : db.ids("v2_practice_cases", "source_id", id)) {
            var row = db.get("v2_practice_cases", cid);
            if (row.path("contextOrigin").asText().equals("REVIEW_GAP") && row.path("sourceSessionKind").asText().equals(kind)) items.add(view(row));
        }
        return out;
    }
    public ObjectNode create(String kind, String id, String qid, JsonNode body) {
        required(body, "requestId", 120);
        var source = source(kind, id); var finding = finding(source, qid);
        var existing = find(id, qid); if (existing != null) return view(existing);
        if (source.path("mock").asBoolean() != runner.isMock())
            throw RecoveryException.conflict("V2_MODEL_MODE_CHANGED", "来源与当前模型模式不同，请切回原模式");
        var input = db.object().put("role", source.path("role").asText()).put("level", source.path("level").asText()).put("questionId", qid);
        var messages = source.path("messages"); boolean answered = false;
        for (int i = 0; i + 1 < messages.size(); i++) {
            var q = messages.get(i); var a = messages.get(i + 1);
            if (q.path("role").asText().equals("ASSISTANT") && a.path("role").asText().equals("USER")
                    && q.path("questionId").asText().equals(qid) && a.path("questionId").asText().equals(qid)) {
                input.put("originalQuestion", q.path("content").asText()).put("originalAnswer", a.path("content").asText()); answered = true; break;
            }
        }
        if (!answered) throw RecoveryException.invalid("未取得本题原始相邻问答，不生成补练");
        input.set("mainProject", source.path("mainProject").deepCopy()); input.set("finding", finding.deepCopy());
        var evidence = input.putArray("sources");
        for (var s : source.path("sources")) for (var ref : finding.path("sourceIds"))
            if (ref.asText().equals(s.path("id").asText())) evidence.add(s.deepCopy());
        var prior = input.putArray("priorQuestions");
        for (var m : messages) if (m.path("role").asText().equals("ASSISTANT") && m.hasNonNull("questionId")) prior.add(m.path("content").asText());
        var request = db.object().put("requestId", "review-gap-v2").put("sourceId", id).put("questionId", qid);
        var row = runner.run(ModelTask.REVIEW_GAP_QUESTION, "REVIEW_GAP", kind + ":" + id + ":" + qid, request, () ->
                new ModelTaskRunner.Work("v2_practice_cases", practice.reviewGapCase(id, source, input), input));
        return view(row);
    }
    @Override public boolean supports(ModelTask task) { return task == ModelTask.REVIEW_GAP_QUESTION; }
    @Override public void running(ObjectNode row, ObjectNode op) { row.put("status", "RUNNING"); row.set("generationProtocol", op.path("protocol").deepCopy()); }
    @Override public void completed(ObjectNode row, ObjectNode result, ObjectNode op) {
        row.put("status", "READY").put("question", result.path("question").asText()).put("answerQuote", result.path("answerQuote").asText())
                .put("questionHash", ContentHash.sha(result.path("question").asText())); row.set("generationProtocol", op.path("protocol").deepCopy());
    }
    @Override public void failed(ObjectNode row, ObjectNode op, boolean interrupted) { row.put("status", interrupted ? "INTERRUPTED" : "FAILED"); }
}
