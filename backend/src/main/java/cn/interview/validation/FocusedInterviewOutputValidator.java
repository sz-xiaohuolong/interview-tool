package cn.interview.validation;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FocusedInterviewOutputValidator {
    public static final List<String> DIMENSIONS = List.of("技术准确性", "理解深度", "表达清晰度", "追问应对能力");

    static String text(JsonNode n, String key, int max) {
        JsonNode v = n.path(key);
        if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > max) throw invalid("缺少或无效的 " + key);
        String value = v.asText().strip();
        ((ObjectNode) n).put(key, value);
        return value;
    }

    static void array(JsonNode n, String key, int min, int max) {
        JsonNode a = n.path(key);
        if (!a.isArray() || a.size() < min || a.size() > max) throw invalid(key + " 数量不符合约束");
    }

    static void strings(JsonNode n, String key, int min, int max) {
        array(n, key, min, max);
        for (JsonNode v : n.path(key))
            if (!v.isTextual() || v.asText().isBlank() || v.asText().length() > 4000)
                throw invalid(key + " 必须为非空文本数组");
    }

    public static String question(JsonNode q, String field) {
        String text = text(q, field, 1800);
        if (text.matches("(?s).*(标准答案|参考答案|正确答案是|回答思路[:：]).*"))
            throw invalid("正式面试问题包含答案提示");
        return text;
    }

    static void evidence(String evidence, String context) {
        if (!evidence.equals("需要补充") && !context.contains(evidence))
            throw invalid("依据不在提供的资料或原始回答中");
    }

    public static void plan(ObjectNode p, int count, boolean project, String context) {
        array(p, "questions", count, count);
        Set<String> levels = new HashSet<>();
        for (JsonNode q : p.path("questions")) {
            question(q, "question");
            text(q, "topic", 120);
            String level = text(q, "level", 10);
            if (!List.of("L1", "L2", "L3").contains(level)) throw invalid("问题层次无效");
            levels.add(level);
            String e = text(q, "evidence", 2000);
            if (e.equals("假设场景") && !q.path("question").asText().contains("假设"))
                throw invalid("假设场景必须在问题中明确说明");
            if (!context.isBlank() && !e.equals("假设场景") && !e.equals("资料不足，需要补充")) evidence(e, context);
        }
        if (project && !levels.containsAll(List.of("L1", "L2", "L3"))) throw invalid("项目面试需覆盖 L1、L2、L3");
    }

    public static void decision(ObjectNode d) {
        String action = text(d, "action", 20);
        if (!List.of("FOLLOW_UP", "NEXT", "END").contains(action)) throw invalid("未知面试动作");
        if (!action.equals("END")) {
            question(d, "question");
            text(d, "topic", 120);
        }
        text(d, "reason", 2000);
    }

    public static void prediction(ObjectNode p, String context) {
        array(p, "questions", 10, 10);
        strings(p, "risks", 0, 20);
        for (JsonNode q : p.path("questions")) {
            question(q, "question");
            evidence(text(q, "evidence", 2000), context);
            text(q, "knowledgePoint", 300);
            String priority = text(q, "priority", 20);
            if (!List.of("高", "中", "低", "HIGH", "MEDIUM", "LOW").contains(priority)) throw invalid("押题优先级无效");
            text(q, "answerOutline", 4000);
            strings(q, "followUps", 1, 5);
        }
    }

    public static void report(ObjectNode r, ObjectNode s) {
        text(r, "summary", 6000);
        array(r, "dimensions", 4, 4);
        Set<String> names = new HashSet<>();
        for (JsonNode d : r.path("dimensions")) {
            String name = text(d, "name", 30);
            if (!DIMENSIONS.contains(name) || !names.add(name)) throw invalid("评价维度缺失或重复");
            if (!d.path("score").isIntegralNumber() || !d.path("score").canConvertToInt() || d.path("score").asInt() < 0 || d.path("score").asInt() > 4)
                throw invalid("评分必须为 0～4 的整数");
            text(d, "reason", 3000);
        }
        Set<Integer> answered = new HashSet<>();
        StringBuilder evidence = new StringBuilder(s.path("context").asText());
        for (JsonNode m : s.path("messages"))
            if (m.path("role").asText().equals("USER")) {
                answered.add(m.path("questionIndex").asInt());
                evidence.append('\n').append(m.path("content").asText());
            }
        array(r, "questions", answered.size(), answered.size());
        Set<Integer> seen = new HashSet<>();
        for (JsonNode q : r.path("questions")) {
            if (!q.path("questionIndex").isIntegralNumber() || !q.path("questionIndex").canConvertToInt())
                throw invalid("问题编号无效");
            int index = q.path("questionIndex").asInt();
            if (!answered.contains(index) || !seen.add(index)) throw invalid("报告问题未回答或重复");
            text(q, "question", 1800);
            for (String field : List.of("correct", "incorrect", "missing", "uncertain", "evidence"))
                strings(q, field, 0, 20);
            text(q, "answerOutline", 5000);
            text(q, "knowledgeExplanation", 5000);
            for (JsonNode e : q.path("evidence")) evidence(e.asText(), evidence.toString());
        }
        strings(r, "weaknesses", 0, 20);
        strings(r, "suggestions", 0, 20);
    }

    static ApiException invalid(String detail) {
        return ApiException.model("模型输出未通过结构或事实边界校验（" + detail + "），请重试");
    }
}
