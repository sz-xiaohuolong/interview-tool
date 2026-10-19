package cn.interview.service.interview;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.llm.provider=openai","app.llm.api-key="}) @AutoConfigureMockMvc
class UnconfiguredIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{String dir=Files.createTempDirectory("unconfigured-").toString();r.add("app.data-dir",()->dir);}
    @Autowired MockMvc mvc;
    @Test void missingKeyIsVisibleAndNeverFallsBackToMock()throws Exception{
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.baseUrl").doesNotExist());
        mvc.perform(post("/api/sessions").contentType("application/json").content("{\"mode\":\"QUICK\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.mock").value(false)).andExpect(jsonPath("$.messages.length()").value(0));
    }
}
