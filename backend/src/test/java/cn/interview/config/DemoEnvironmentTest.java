package cn.interview.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.demo=true", "app.web.frontend-port=5174", "app.llm.provider=openai", "app.llm.api-key="})
@AutoConfigureMockMvc
class DemoEnvironmentTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("demo-environment-test-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", () -> DATA.toString()); }
    @Autowired MockMvc mvc;

    @Test void demoHealthUsesRealModelAndDoesNotPretendToBeConfigured() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true)).andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.apiKey").doesNotExist());
    }

    @Test void acceptsDemoPageWritesAndRejectsDailyPageAndExternalOrigins() throws Exception {
        String body="{\"kind\":\"JD\",\"name\":\"虚构演示岗位\",\"content\":\"Java\"}";
        mvc.perform(post("/api/materials").header("Origin","http://127.0.0.1:5174").contentType("application/json").content(body)).andExpect(status().isOk());
        for(String origin : new String[]{"http://127.0.0.1:5173", "https://example.com", "http://evil.example:5174"})
            mvc.perform(post("/api/materials").header("Origin",origin).contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
