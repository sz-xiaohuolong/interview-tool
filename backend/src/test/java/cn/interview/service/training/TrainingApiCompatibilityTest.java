package cn.interview.service.training;

import cn.interview.testsupport.SyntheticTrainingData;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.llm.provider=mock", "app.training.enabled=true", "app.knowledge.network-enabled=false"})
@AutoConfigureMockMvc
class TrainingApiCompatibilityTest {
    static final Path DATA = SyntheticTrainingData.migrated();
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) { registry.add("app.data-dir", DATA::toString); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void 新训练入口创建后旧地址读取相同记录且重复请求不另建故事() throws Exception {
        var body = json.writeValueAsBytes(Map.of("requestId", UUID.randomUUID().toString(), "templateKind", "STAR"));
        var created = json.readTree(mvc.perform(post("/api/training/stories").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = created.path("id").asText();
        var old = json.readTree(mvc.perform(get("/api/v2/stories/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var current = json.readTree(mvc.perform(get("/api/training/stories/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(old).isEqualTo(current);
        assertThat(old.path("id")).isEqualTo(created.path("id"));
        assertThat(old.path("currentVersionId")).isEqualTo(created.path("currentVersionId"));
        mvc.perform(post("/api/v2/stories").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }
}
