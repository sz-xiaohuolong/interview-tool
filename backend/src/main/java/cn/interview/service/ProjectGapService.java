package cn.interview.service;

import cn.interview.client.ModelTask;
import cn.interview.common.ContentHash;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.SqliteStore;
import cn.interview.mapper.TrainingStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import static cn.interview.common.Models.*;

/** 将本场缺口转换为现有单题案例；原报告和矩阵始终只读。 */
@Service
public class ProjectGapService implements ModelTaskHandler {
    private final SqliteStore legacy;
    private final TrainingStore db;
    private final ModelTaskRunner runner;
    private final PracticeService practice;
    private final ProjectEvidenceService evidence;
    private final ObjectProvider<ProjectEvidenceSource> sources;

    public ProjectGapService(SqliteStore legacy, TrainingStore db, ModelTaskRunner runner, PracticeService practice,
                             ProjectEvidenceService evidence, ObjectProvider<ProjectEvidenceSource> sources) {
        this.legacy = legacy; this.db = db; this.runner = runner; this.practice = practice; this.evidence = evidence; this.sources = sources;
    }

    private ObjectNode source(String kind, String id) {
        db.enabled(); choice(kind, "SESSION", "V3");
        ObjectNode source;
        if (kind.equals("SESSION")) {
            source = legacy.session(id);
            if (!source.path("reportStatus").asText().equals("READY")) throw RecoveryException.conflict("V2_SOURCE_NOT_READY", "请等待原场次复盘完成后补练");
            source.set("matrix", legacy.report(id).path("matrix").deepCopy());
        } else {
            var provider = sources.stream().filter(s -> s.supports(kind)).findFirst().orElseThrow(() -> RecoveryException.invalid("当前运行环境没有该来源模块"));
            source = provider.source(id);
            if (!source.has("matrix")) source.set("matrix", source.path("report").path("matrix").deepCopy());
            if (!source.path("status").asText().equals("REPORT_READY")) throw RecoveryException.conflict("V2_SOURCE_NOT_READY", "请等待原场次复盘完成后补练");
        }
        if (source.path("experienceVersion").asInt() != 1 || !evidence.applicable(source.path("mainProject")) || !source.path("matrix").path("items").isArray())
            throw RecoveryException.conflict("V2_SOURCE_NOT_READY", "该历史或假设场景没有真实项目证据矩阵，不会自动补算");
        return source;
    }

    private JsonNode checkpoint(JsonNode source, String checkpointId) {
        if (!ProjectEvidenceService.CHECKPOINT_IDS.contains(checkpointId)) throw RecoveryException.invalid("检查点不存在");
        for (JsonNode item : source.path("matrix").path("items")) if (item.path("checkpointId").asText().equals(checkpointId)) return item;
        throw RecoveryException.invalid("本场矩阵没有该检查点");
    }

    private ObjectNode find(String kind, String sourceId, String checkpointId) {
        for (String id : db.ids("v2_practice_cases", "source_id", sourceId)) {
            var row = db.get("v2_practice_cases", id);
            if (row.path("contextOrigin").asText().equals("PROJECT_GAP") && row.path("sourceSessionKind").asText().equals(kind) && row.path("checkpointId").asText().equals(checkpointId)) return row;
        }
        return null;
    }

    private ObjectNode view(ObjectNode row) {
        var out = db.object().put("caseId", row.path("id").asText()).put("checkpointId", row.path("checkpointId").asText())
                .put("status", row.path("status").asText()).put("sourceKind", row.path("sourceSessionKind").asText()).put("sourceId", row.path("sourceId").asText());
        if (row.hasNonNull("operationId")) out.set("operationId", row.path("operationId"));
        var ids = db.recentAttemptIds(row.path("id").asText());
        if (!ids.isEmpty()) {
            var attempt = db.get("v2_practice_attempts", ids.getFirst());
            out.putObject("latestAttempt").put("id", attempt.path("id").asText()).put("status", attempt.path("status").asText());
        }
        return out;
    }

    public ObjectNode list(String kind, String sourceId) {
        source(kind, sourceId); var out = db.object(); var items = out.putArray("items");
        for (String id : db.ids("v2_practice_cases", "source_id", sourceId)) {
            var row = db.get("v2_practice_cases", id);
            if (row.path("contextOrigin").asText().equals("PROJECT_GAP") && row.path("sourceSessionKind").asText().equals(kind)) items.add(view(row));
        }
        return out;
    }

    public ObjectNode get(String kind, String sourceId, String checkpointId) {
        checkpoint(source(kind, sourceId), checkpointId); var row = find(kind, sourceId, checkpointId);
        return row == null ? db.object().put("checkpointId", checkpointId).putNull("caseId") : view(row);
    }

    public ObjectNode create(String kind, String sourceId, String checkpointId, JsonNode body) {
        required(body, "requestId", 120);
        var source = source(kind, sourceId); var item = checkpoint(source, checkpointId);
        if (item.path("status").asText().equals("CLEAR")) throw RecoveryException.invalid("本项已讲清，请从待补强或未覆盖项开始定向补练");
        var existing = find(kind, sourceId, checkpointId); if (existing != null) return view(existing);
        if (source.path("mock").asBoolean() != runner.llm.isMock()) throw RecoveryException.conflict("V2_MODEL_MODE_CHANGED", "来源与当前模型模式不同，请切回原模式");
        // 操作键由来源和检查点确定，用户换请求标识或刷新也不会重复生成同一缺口题。
        String scope = kind + ":" + sourceId + ":" + checkpointId;
        var request = db.object().put("requestId", "project-gap-v1").put("sourceKind", kind).put("sourceId", sourceId).put("checkpointId", checkpointId);
        var row = runner.run(ModelTask.PROJECT_GAP_QUESTION, "PROJECT_GAP", scope, request, () -> {
            var created = practice.projectGapCase(kind, sourceId, source, source.path("mainProject"), item);
            var input = db.object().put("role", source.path("role").asText()).put("level", source.path("level").asText());
            input.set("project", source.path("mainProject").deepCopy());
            input.putObject("checkpoint").put("checkpointId", checkpointId).put("title", item.path("title").asText()).put("status", item.path("status").asText()).put("reason", item.path("reason").asText());
            var questions = input.putArray("priorQuestions");
            for (JsonNode message : source.path("messages")) if (message.path("role").asText().equals("ASSISTANT")) questions.add(message.path("content").asText());
            return new ModelTaskRunner.Work("v2_practice_cases", created, input);
        });
        return view(row);
    }

    @Override public boolean supports(ModelTask task) { return task == ModelTask.PROJECT_GAP_QUESTION; }
    @Override public void running(ObjectNode row, ObjectNode operation) { row.put("status", "RUNNING"); row.set("generationProtocol", operation.path("protocol").deepCopy()); }
    @Override public void completed(ObjectNode row, ObjectNode result, ObjectNode operation) {
        row.put("status", "READY").put("question", result.path("question").asText()).put("projectQuote", result.path("projectQuote").asText())
                .put("questionHash", ContentHash.sha(result.path("question").asText()));
        row.set("generationProtocol", operation.path("protocol").deepCopy());
    }
    @Override public void failed(ObjectNode row, ObjectNode operation, boolean interrupted) { row.put("status", interrupted ? "INTERRUPTED" : "FAILED"); }
}
