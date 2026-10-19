package cn.interview.service.model;

import cn.interview.Application;
import cn.interview.client.interview.FocusedInterviewModelClient;
import cn.interview.client.training.ModelTask;
import cn.interview.client.training.TrainingModelClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 仅访问本机协议服务，验证配置保存与实际调用，不使用真实密钥。 */
@SpringBootTest(properties={"app.llm.provider=openai", "app.llm.api-key=startup-synthetic-key", "app.llm.model=startup-model", "app.llm.timeout-seconds=2"})
@AutoConfigureMockMvc
class ModelConfigurationIntegrationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path DATA;
    static final HttpServer SERVER;
    static final AtomicReference<JsonNode> REQUEST=new AtomicReference<>();
    static final AtomicReference<String> AUTH=new AtomicReference<>();
    static final AtomicInteger CALLS=new AtomicInteger();
    static volatile int upstreamStatus=200;
    static volatile String responseOverride;
    static {
        try {
            DATA=Files.createTempDirectory("model-settings-");
            SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            SERVER.createContext("/v1/",exchange->{
                CALLS.incrementAndGet(); AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String body;
                if(exchange.getRequestURI().getPath().endsWith("/models")) body="{\"data\":[{\"id\":\"wire-model\"},{\"id\":\"wire-model\"},{\"id\":\"other-model\"}]}";
                else {
                    REQUEST.set(JSON.readTree(exchange.getRequestBody()));
                    body="{\"model\":\"wire-model\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3,\"total_tokens\":14}}";
                }
                if(upstreamStatus!=200) body="上游诊断含 synthetic-new-key，不得返回用户";
                if(responseOverride!=null) body=responseOverride;
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if(upstreamStatus==302) exchange.getResponseHeaders().set("Location", "/v1/redirected");
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(upstreamStatus,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            }); SERVER.start();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);r.add("app.llm.base-url",()->url());}
    static String url(){return "http://127.0.0.1:"+SERVER.getAddress().getPort()+"/v1";}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired FocusedInterviewModelClient llm; @Autowired TrainingModelClient training;
    JsonNode view()throws Exception{return json.readTree(mvc.perform(get("/api/model-config")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    ObjectNode draft()throws Exception{return json.createObjectNode().put("expectedRevision",view().path("revision").asLong()).put("preset","CUSTOM").put("baseUrl",url()).put("model","wire-model").put("apiKey","synthetic-new-key").put("fastModel","").put("reasoningEffort","").put("fastReasoningEffort","");}
    JsonNode write(String path,ObjectNode body)throws Exception{return json.readTree(mvc.perform(post(path).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    JsonNode save(ObjectNode body)throws Exception{return json.readTree(mvc.perform(put("/api/model-config").contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    @AfterEach void restore()throws Exception{upstreamStatus=200;responseOverride=null;var response=mvc.perform(get("/api/model-config")).andReturn().getResponse();if(response.getStatus()==200){var s=json.readTree(response.getContentAsString());mvc.perform(post("/api/model-config/reset").contentType("application/json").content("{\"expectedRevision\":"+s.path("revision").asLong()+"}"));}}
    @AfterAll static void stop(){SERVER.stop(0);}

    @Test void 保存后真实旧客户端与顶栏无需重启使用新模型且不泄露密钥()throws Exception{
        int calls=CALLS.get();var result=save(draft());
        assertThat(CALLS.get()).isEqualTo(calls);
        assertThat(result.toString()).doesNotContain("synthetic-new-key","startup-synthetic-key");
        assertThat(result.path("apiKeyConfigured").asBoolean()).isTrue();
        mvc.perform(get("/api/health")).andExpect(jsonPath("$.model").value("wire-model"));
        llm.call("planner",json.createObjectNode());
        assertThat(REQUEST.get().path("model").asText()).isEqualTo("wire-model");
        assertThat(AUTH.get()).isEqualTo("Bearer synthetic-new-key");
        Path file=DATA.resolve("model-config.json");assertThat(file).exists();
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
    @Test void 切换模型后专项练习拒绝旧评估分组并使用新协议()throws Exception{
        var task=ModelTask.PRACTICE_EVALUATOR;var old=training.protocol(task);
        save(draft());
        org.assertj.core.api.Assertions.assertThatThrownBy(()->training.call(task,json.createObjectNode(),old)).isInstanceOf(cn.interview.exception.RecoveryException.class).hasMessageContaining("评估分组");
        var protocol=training.protocol(task);training.call(task,json.createObjectNode(),protocol);
        assertThat(REQUEST.get().path("model").asText()).isEqualTo("wire-model");
        assertThat(AUTH.get()).isEqualTo("Bearer synthetic-new-key");
    }
    @Test void 草稿测试和模型发现不改变已保存配置()throws Exception{
        var before=view();var draft=draft();
        var models=write("/api/model-config/models",draft);
        assertThat(models.path("models").findValuesAsText("id")).containsExactly("wire-model","other-model");
        var tested=write("/api/model-config/test",draft);
        assertThat(tested.path("jsonSupported").asBoolean()).isTrue();
        assertThat(tested.path("usage").path("totalTokens").asInt()).isEqualTo(14);
        assertThat(tested.toString()).doesNotContain("synthetic-new-key");
        assertThat(REQUEST.get().path("messages").toString()).doesNotContain("简历","面试回答");
        assertThat(REQUEST.get().path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(view()).isEqualTo(before);
    }
    @Test void 留空密钥只允许同一连接沿用且旧版本不能覆盖()throws Exception{
        var s=save(draft());var body=draft().put("expectedRevision",s.path("revision").asLong()).put("apiKey","").put("model","changed-model");
        s=save(body);llm.call("planner",json.createObjectNode());assertThat(AUTH.get()).isEqualTo("Bearer synthetic-new-key");
        mvc.perform(put("/api/model-config").contentType("application/json").content(body.toString())).andExpect(status().isConflict());
        body.put("expectedRevision",s.path("revision").asLong()).put("baseUrl",url()+"/other");
        mvc.perform(put("/api/model-config").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        assertThat(view().path("model").asText()).isEqualTo("changed-model");
    }
    @Test void 未选择模型也能主动获取列表且不允许通过字段启用Mock()throws Exception{
        var body=draft().put("model", "").put("provider", "mock");
        assertThat(write("/api/model-config/models", body).path("models")).hasSize(2);
        mvc.perform(put("/api/model-config").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        body.put("model", "wire-model");
        assertThat(save(body).path("provider").asText()).isEqualTo("openai");
    }
    @Test void DeepSeek旧版追问关闭思考而深度任务保留已选强度()throws Exception{
        save(draft().put("preset", "DEEPSEEK").put("reasoningEffort", "high"));
        llm.call("interviewer", json.createObjectNode());
        assertThat(REQUEST.get().path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(REQUEST.get().has("reasoning_effort")).isFalse();
        llm.call("planner", json.createObjectNode());
        assertThat(REQUEST.get().path("reasoning_effort").asText()).isEqualTo("high");
    }
    @Test void 本机配置可重新加载且恢复启动配置不写入启动密钥()throws Exception{
        save(draft());
        var app=new SpringApplication(Application.class);app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try(var context=app.run("--app.data-dir="+DATA,"--app.llm.provider=openai","--app.llm.api-key=startup-synthetic-key","--app.llm.base-url="+url(),"--app.llm.model=startup-model","--app.v3.enabled=false","--logging.level.root=ERROR","--spring.main.banner-mode=off")){
            assertThat(context.getBean(FocusedInterviewModelClient.class).health().get("model")).isEqualTo("wire-model");
        }
        var s=view();var result=write("/api/model-config/reset",json.createObjectNode().put("expectedRevision",s.path("revision").asLong()));
        assertThat(result.path("source").asText()).isEqualTo("STARTUP");
        assertThat(result.path("model").asText()).isEqualTo("startup-model");
        assertThat(Files.readString(DATA.resolve("model-config.json"))).doesNotContain("synthetic-new-key","startup-synthetic-key");
    }
    @ParameterizedTest @ValueSource(strings={"https://u:p@example.com/v1","https://example.com/v1?key=x","https://example.com/#x","http://192.168.1.2/v1","file:///tmp/x"})
    void 不安全地址被拒绝且保留原配置(String address)throws Exception{
        var before=view();var body=draft().put("baseUrl",address);
        mvc.perform(put("/api/model-config").contentType("application/json").content(body.toString())).andExpect(status().isBadRequest());
        assertThat(view()).isEqualTo(before);
    }
    @Test void 上游错误不泄露正文且跨站写入被拒绝()throws Exception{
        var body=draft();upstreamStatus=401;
        var error=mvc.perform(post("/api/model-config/test").contentType("application/json").content(body.toString())).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(error).contains("MODEL_AUTH_ERROR").doesNotContain("synthetic-new-key","上游诊断");
        mvc.perform(put("/api/model-config").header("Origin","https://other.example").contentType("application/json").content(body.toString())).andExpect(status().isForbidden());
    }
    @Test void 超大响应与重定向均拒绝且不改写配置()throws Exception{
        var before=view();var body=draft();responseOverride="x".repeat(2_000_001);
        mvc.perform(post("/api/model-config/test").contentType("application/json").content(body.toString())).andExpect(status().isBadGateway());
        responseOverride=null;upstreamStatus=302;int count=CALLS.get();
        mvc.perform(post("/api/model-config/models").contentType("application/json").content(body.toString())).andExpect(status().isServiceUnavailable());
        assertThat(CALLS.get()).isEqualTo(count+1);
        assertThat(view()).isEqualTo(before);
    }
}
