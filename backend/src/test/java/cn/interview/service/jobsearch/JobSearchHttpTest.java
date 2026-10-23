package cn.interview.service.jobsearch;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 边界使用真实 Spring／SQLite，不访问招聘站点或模型。 */
@SpringBootTest(properties="app.llm.provider=mock")
@AutoConfigureMockMvc
class JobSearchHttpTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("job-search-http-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir",()->DATA.toString()); }
    @Autowired MockMvc mvc;
    @Test void citiesAndMissingTaskAreExplicit() throws Exception {
        mvc.perform(get("/api/job-search/cities")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("shenzhen"));
        mvc.perform(get("/api/job-search/tasks/absent")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void invalidSearchAndUntrustedIdentityAreRejected() throws Exception {
        for(String body:new String[]{"{}","{\"keyword\":\"Java\",\"cityId\":\"shenzhen\",\"sources\":[\"OTHER\"]}","{\"keyword\":\"Java\",\"cityId\":\"unknown\",\"sources\":[\"BOSS\"]}"})
            mvc.perform(post("/api/job-search/tasks").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/job-search/jd").contentType("application/json").content("{\"jobKey\":\"BOSS:fake\",\"name\":\"JD\",\"content\":\"正文\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/job-search/tasks").header("Origin","https://evil.example").contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
}
