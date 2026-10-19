package cn.interview.service;

import cn.interview.Application;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes=Application.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"app.llm.provider=mock","app.knowledge.network-enabled=false"})
class InterviewStatusTest {
    static final Path DATA;
    static {try {DATA=Files.createTempDirectory("v3-status-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",DATA::toString);}
    @LocalServerPort int port;
    @Test void 新增状态接口明确标记测试模式且不暴露凭证() throws Exception {
        var r=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v3/status")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"enabled\":true","\"provider\":\"mock\"").doesNotContain("apiKey","baseUrl");
    }
}
