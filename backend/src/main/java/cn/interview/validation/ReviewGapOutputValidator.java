package cn.interview.validation;

import cn.interview.client.V2LlmClient;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 补练必须是包含原答锚点的新题，来源观察不冒充标准答案。 */
public final class ReviewGapOutputValidator {
    private ReviewGapOutputValidator() {}
    public static ObjectNode validate(ObjectNode raw, ObjectNode input) {
        String question = raw.path("question").asText(), quote = raw.path("answerQuote").asText();
        int min = Math.min(8, input.path("originalAnswer").asText().length());
        if (!raw.path("question").isTextual() || question.isBlank() || question.length() > 1800
                || !raw.path("answerQuote").isTextual() || quote.isBlank() || quote.length() < min || quote.length() > 160
                || !input.path("originalAnswer").asText().contains(quote) || !question.contains(quote))
            throw V2LlmClient.failed("MODEL_INVALID_OUTPUT", "补练题缺少可逐字回查的原答锚点，未保存为有效新题。");
        ModelValidator.question(raw, "question");
        for (var prior : input.path("priorQuestions")) if (prior.asText().equals(question))
            throw V2LlmClient.failed("MODEL_INVALID_OUTPUT", "补练返回本场原题，未保存为新题。");
        return raw.objectNode().put("question", question).put("answerQuote", quote);
    }
}
