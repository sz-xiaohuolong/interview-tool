package cn.interview.validation;

import cn.interview.client.training.TrainingModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 新题必须绑定当前项目原文和检查点，不能把原题重发作为定向新题。 */
public final class ProjectGapOutputValidator {
    private ProjectGapOutputValidator() {}
    public static ObjectNode validate(ObjectNode raw, ObjectNode input) {
        String question = raw.path("question").asText(), quote = raw.path("projectQuote").asText();
        String name = input.path("project").path("name").asText();
        if (!raw.path("question").isTextual() || question.isBlank() || question.length() > 1800
                || !raw.path("projectQuote").isTextual() || quote.isBlank() || quote.length() > 500
                || !input.path("project").path("brief").asText().contains(quote)
                || !input.path("checkpoint").path("checkpointId").asText().equals(raw.path("checkpointId").asText())
                || (!question.contains(name) && !question.contains(quote))) throw TrainingModelClient.failed("MODEL_INVALID_OUTPUT", "补练问题没有绑定本场主项目与检查点，未保存为有效问题。");
        FocusedInterviewOutputValidator.question(raw, "question");
        for (JsonNode prior : input.path("priorQuestions")) if (prior.asText().equals(question))
            throw TrainingModelClient.failed("MODEL_INVALID_OUTPUT", "补练返回了本场已问题目，未把原题冒充新题。");
        var out = raw.objectNode().put("question", question).put("checkpointId", raw.path("checkpointId").asText()).put("projectQuote", quote);
        return out;
    }
}
