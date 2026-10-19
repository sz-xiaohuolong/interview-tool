package cn.interview.client.interview;

import cn.interview.exception.ApiException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class InterviewModelClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger redirected = new AtomicInteger();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private volatile int responseCode = 200;
    private volatile String content = "{\"action\":\"FOLLOW_UP\",\"question\":\"如何核对失败边界？\",\"sourceIds\":[]}";
    private volatile String finishReason = "stop";
    private volatile boolean delayed;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            if (delayed) {
                try { Thread.sleep(1800); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] body;
            if (responseCode == 200) {
                ObjectNode envelope = json.createObjectNode().put("id", "synthetic-response")
                    .put("object", "chat.completion").put("created", 0).put("model", "returned-test-model");
                envelope.putArray("choices").addObject().put("index", 0).put("finish_reason", finishReason)
                    .putObject("message").put("role", "assistant").put("content", content);
                envelope.putObject("usage").put("prompt_tokens", 21).put("completion_tokens", 12).put("total_tokens", 33);
                body = json.writeValueAsBytes(envelope);
            } else {
                body = "upstream test-only-key private-upstream-diagnostic".getBytes(StandardCharsets.UTF_8);
            }
            if (responseCode == 302) exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(responseCode, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private InterviewModelClient gateway(String provider, String key, String fastModel, String fastEffort) {
        return new InterviewModelClient(json, provider,
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", key,
            "deep-test-model", "high", fastModel, fastEffort);
    }

    private ObjectNode followup() {
        ObjectNode input = json.createObjectNode().put("currentQuestion", "如何处理并发更新？")
            .put("answer", "通过事务回滚").put("followUpCount", 0);
        input.putArray("sources");
        return input;
    }

    @Test void 后续请求动态读取共享配置且DeepSeek追问关闭思考() throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment()
            .withProperty("app.llm.provider", "mock");
        var directory = java.nio.file.Files.createTempDirectory("v3-model-settings-");
        var settings = new cn.interview.service.model.ModelConfigurationService(env,
            new cn.interview.mapper.ModelConfigStore(directory.toString(), json), json);
        var gateway = new InterviewModelClient(json, settings);
        var body = json.createObjectNode().put("expectedRevision", 0).put("preset", "CUSTOM")
            .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
            .put("apiKey", "synthetic-connection-key").put("model", "new-deep-model")
            .put("fastModel", "new-fast-model").put("fastReasoningEffort", "low");
        settings.save(body);
        gateway.call("FOLLOWUP", followup(), 2);
        assertThat(requests.get(0).path("model").asText()).isEqualTo("new-fast-model");
        body.put("expectedRevision", 1).put("preset", "DEEPSEEK").put("fastModel", "next-fast-model");
        settings.save(body);
        gateway.call("FOLLOWUP", followup(), 2);
        assertThat(requests.get(1).path("model").asText()).isEqualTo("next-fast-model");
        assertThat(requests.get(1).has("reasoning_effort")).isFalse();
        assertThat(requests.get(1).path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test void unconfiguredRealModeFailsWithoutUsingMockOrNetwork() {
        assertThatThrownBy(() -> gateway("openai", "", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_NOT_CONFIGURED"));
        assertThat(requests).isEmpty();
    }

    @Test void unknownStageCannotSelectArbitraryPromptOrTriggerNetwork() {
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("../planner", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("INVALID_INPUT"));
        assertThat(requests).isEmpty();
    }

    @Test void fastAndDeepCallsUseTheirOwnModelAndEffortWithJsonMode() {
        var gateway = gateway("openai", "test-only-key", "fast-test-model", "low");
        var result = gateway.call("FOLLOWUP", followup(), 2);
        assertThat(result.path("question").asText()).isEqualTo("如何核对失败边界？");
        assertThat(result.path("_modelUsage").path("promptTokens").asInt()).isEqualTo(21);
        assertThat(result.path("_modelUsage").path("completionTokens").asInt()).isEqualTo(12);
        assertThat(result.path("_modelUsage").path("model").asText()).isEqualTo("returned-test-model");
        content = "{\"summary\":{\"technicalStack\":[],\"responsibilities\":[],\"clarifications\":[],\"focus\":[]},\"topics\":[]}";
        gateway.call("ANALYZE", json.createObjectNode(), 2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).path("model").asText()).isEqualTo("fast-test-model");
        assertThat(requests.get(0).path("reasoning_effort").asText()).isEqualTo("low");
        assertThat(requests.get(1).path("model").asText()).isEqualTo("deep-test-model");
        assertThat(requests.get(1).path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(requests.get(0).path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(requests.get(0).path("messages").get(1).path("content").asText()).contains("通过事务回滚");
    }

    @Test void blankFastModelReusesConfiguredModel() {
        gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("deep-test-model");
        assertThat(requests.getFirst().path("reasoning_effort").asText()).isEqualTo("low");
    }

    @Test void 专业策略在原追问请求返回合法诊断且输出上限保持八百() {
        var response=json.createObjectNode().put("action","FOLLOW_UP").put("question","怎样验证回滚后的状态？");
        response.putArray("sourceIds");
        response.putObject("diagnosis").put("category","INCOMPLETE")
            .put("answerQuote","通过事务回滚").put("gap","尚未说明状态核验")
            .put("nextGoal","核对失败后的状态").put("reason","原答只有回滚方案")
            .putArray("coveredPoints").add("事务回滚");
        content=response.toString();

        var result=gateway("openai","test-only-key","fast-test-model","low")
            .call("FOLLOWUP",followup().put("strategyVersion",1),2);

        assertThat(result.path("diagnosis")).isEqualTo(response.path("diagnosis"));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("max_tokens").asInt()).isEqualTo(800);
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("fast-test-model");
        assertThat(requests.getFirst().path("messages").get(1).path("content").asText())
            .contains("\"strategyVersion\":1","通过事务回滚");
    }

    @Test void 专业策略缺少诊断时拒绝输出且不发起额外请求() {
        assertThatThrownBy(()->gateway("openai","test-only-key","fast-test-model","low")
            .call("FOLLOWUP",followup().put("strategyVersion",1),2))
            .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code).isEqualTo("V3_MODEL_INVALID"));
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("max_tokens").asInt()).isEqualTo(800);
    }

    @Test void 显式Mock也独立上报测试用量而不访问网络() {
        var reported = new java.util.ArrayList<ObjectNode>();
        var result = gateway("mock", "", "", "low").call("FOLLOWUP", followup(), 2, reported::add);
        assertThat(result.path("mock").asBoolean()).isTrue();
        assertThat(reported).hasSize(1);
        assertThat(reported.getFirst().path("promptTokens").asInt()).isZero();
        assertThat(reported.getFirst().path("completionTokens").asInt()).isZero();
        assertThat(requests).isEmpty();
    }

    @Test void serverFailureIsOneAttemptAndDoesNotExposeUpstreamBody() {
        responseCode = 500;
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code).isEqualTo("MODEL_HTTP_ERROR");
                assertThat(error.getMessage()).doesNotContain("test-only-key", "private-upstream-diagnostic");
            });
        assertThat(requests).hasSize(1);
    }

    @Test void redirectsCannotForwardPromptOrCredentialToAnotherEndpoint() {
        responseCode = 302;
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOf(ApiException.class);
        assertThat(requests).hasSize(1);
        assertThat(redirected).hasValue(0);
    }

    @Test void singleRequestTimeoutIsBoundedAndNotRetried() {
        delayed = true;
        long started = System.nanoTime();
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 1))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_TIMEOUT"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1700));
        assertThat(requests).hasSize(1);
    }

    @Test void incompleteOutputCannotBeTreatedAsSuccessfulJson() {
        finishReason = "length";
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_INCOMPLETE_OUTPUT"));
    }

    @Test void trailingContentCannotPassJsonValidation() {
        content += " 非法尾部";
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_INVALID_OUTPUT"));
    }

    @Test void inventedSourceReferenceIsRejected() {
        content = "{\"action\":\"FOLLOW_UP\",\"question\":\"如何验证？\",\"sourceIds\":[\"invented\"]}";
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_INVALID_OUTPUT"));
    }

    @Test void explicitMockUsesResumeEvidenceAndDistinguishesRoles() {
        var mock = gateway("mock", "", "", "low");
        ObjectNode javaInput = json.createObjectNode().put("role", "Java 后端工程师")
            .put("resume", "负责订单支付回调与幂等校验").put("level", "JUNIOR").put("totalQuestions", 3);
        javaInput.putArray("sources");
        var javaResult = mock.call("PLAN", javaInput, 2);
        ObjectNode agentInput = javaInput.deepCopy().put("role", "大模型应用 / Agent 开发工程师")
            .put("resume", "负责企业知识库召回与权限过滤");
        var agentResult = mock.call("PLAN", agentInput, 2);
        assertThat(javaResult.path("mock").asBoolean()).isTrue();
        assertThat(javaResult.path("questions")).hasSize(3);
        assertThat(javaResult.path("questions").get(0).path("question").asText())
            .contains("Mock", "订单支付回调与幂等校验")
            .isNotEqualTo(agentResult.path("questions").get(0).path("question").asText());
        assertThat(agentResult.path("questions").get(0).path("question").asText()).contains("知识库召回与权限过滤");
        assertThat(requests).isEmpty();
    }

    @Test void eightQuestionMockDoesNotRepeatPreparedQuestions() {
        ObjectNode input = json.createObjectNode().put("role", "Java 后端工程师")
            .put("resume", "负责订单幂等校验").put("totalQuestions", 8);
        input.putArray("sources");
        JsonNode questions = gateway("mock", "", "", "low").call("PLAN", input, 2).path("questions");
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (JsonNode question : questions) distinct.add(question.path("question").asText());
        assertThat(distinct).hasSize(8);
    }

    @Test void completeEndpointIsNotAppendedTwice() {
        var gateway = new InterviewModelClient(json, "openai",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions", "test-only-key",
            "deep-test-model", "", "", "low");
        gateway.call("FOLLOWUP", followup(), 2);
        assertThat(requests).hasSize(1);
    }

    @Test void oversizedResponseCannotBecomeValidModelOutput() {
        content = "{\"action\":\"FOLLOW_UP\",\"question\":\"" + "过长".repeat(700000) + "\",\"sourceIds\":[]}";
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("FOLLOWUP", followup(), 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_INVALID_OUTPUT"));
        assertThat(requests).hasSize(1);
    }

    @Test void reviewCannotEvaluateQuestionWithoutAUserAnswer() {
        content = "{\"summary\":\"复盘摘要\",\"findings\":[{\"questionId\":\"q-unanswered\",\"assessment\":\"没有回答\",\"status\":\"INSUFFICIENT\",\"sourceIds\":[],\"sourceQuote\":\"\",\"suggestion\":\"请先作答\"}]}";
        ObjectNode input = json.createObjectNode();
        input.putArray("sources");
        input.putArray("messages").addObject().put("role", "ASSISTANT").put("questionId", "q-unanswered");
        assertThatThrownBy(() -> gateway("openai", "test-only-key", "", "low").call("REVIEW", input, 2))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_INVALID_OUTPUT"));
    }

    @Test void 合成复盘分别覆盖主问题与追问的已答标识且不评价未答题() {
        ObjectNode input = json.createObjectNode().put("role", "Java 后端工程师");
        input.putArray("sources");
        var messages = input.putArray("messages");
        messages.addObject().put("role", "ASSISTANT").put("questionId", "q-main").put("content", "你实现了哪些接口逻辑？");
        messages.addObject().put("role", "USER").put("questionId", "q-main").put("content", "我负责参数校验和缓存接入。");
        messages.addObject().put("role", "ASSISTANT").put("questionId", "q-followup").put("content", "缓存不可用时如何验证回源边界？");
        messages.addObject().put("role", "USER").put("questionId", "q-followup").put("content", "我设置了短超时，尚未做并发回源验证。");
        messages.addObject().put("role", "ASSISTANT").put("questionId", "q-unanswered").put("content", "你如何排查数据库请求上升？");

        ObjectNode raw = gateway("mock", "", "", "low").call("REVIEW", input, 2);
        ObjectNode report = new cn.interview.validation.InterviewOutputValidator(json)
            .report(raw, input.path("messages"), input.path("sources"));
        assertThat(raw.path("findings")).hasSize(2);
        assertThat(report.path("findings")).hasSize(2);
        assertThat(report.path("findings").findValuesAsText("questionId"))
            .containsExactlyInAnyOrder("q-main", "q-followup");
        assertThat(requests).isEmpty();
    }
}
