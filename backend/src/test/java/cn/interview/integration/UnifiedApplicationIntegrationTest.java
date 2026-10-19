package cn.interview.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.llm.provider=mock", "app.knowledge.network-enabled=false"})
@AutoConfigureMockMvc
class UnifiedApplicationIntegrationTest {
    static final Path DATA;
    static {
        try { DATA = Files.createTempDirectory("interview-unified-"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", DATA::toString);
    }
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test void 业务名称入口与原综合接口读取同一份场次() throws Exception {
        mvc.perform(get("/api/interviews/status")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/interviews")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v3/sessions")).andExpect(status().isOk()).andExpect(content().json("[]"));
        var response = mvc.perform(post("/api/interviews").contentType("application/json")
                .content(json.writeValueAsBytes(java.util.Map.of("requestId", java.util.UUID.randomUUID().toString(), "role", "合成入口兼容岗位", "level", "JUNIOR"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("id").asText();
        try {
            mvc.perform(get("/api/v3/sessions/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
            mvc.perform(get("/api/interviews/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("合成入口兼容岗位"));
        } finally {
            mvc.perform(delete("/api/v3/sessions/" + id)).andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/interviews/" + id)).andExpect(status().isNotFound());
    }

    @Test void 默认启动同时提供综合面试与原有共用接口且不依赖版本Profile() throws Exception {
        mvc.perform(get("/api/v3/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.provider").value("mock"));
        mvc.perform(get("/api/v3/sessions")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/sessions")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/materials")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v2/status")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }
}
