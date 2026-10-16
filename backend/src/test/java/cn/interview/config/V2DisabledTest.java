package cn.interview.config;

import cn.interview.Application;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class V2DisabledTest {
    @TempDir Path root;

    @Test void disabledStatusAndBridgeDoNotTouchMissingV2Tables() throws Exception {
        try (var app = new SpringApplicationBuilder(Application.class).run("--app.data-dir=" + root,
                "--app.llm.provider=mock", "--app.v2.enabled=false", "--server.port=0", "--logging.level.root=ERROR")) {
            var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) app).build();
            mvc.perform(get("/api/v2/status")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
            mvc.perform(post("/api/sessions").contentType("application/json")
                    .content("{\"trainingLinkId\":\"existing-link\"}"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("V2_DISABLED"));
            mvc.perform(post("/api/sessions").contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }
    }
}
