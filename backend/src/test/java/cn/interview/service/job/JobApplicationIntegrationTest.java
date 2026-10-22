package cn.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.llm.provider=mock")
@AutoConfigureMockMvc
class JobApplicationIntegrationTest {
    static final String API = "/api/job-applications";
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("job-application-test-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir",()->DATA.toString()); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    ObjectNode input(String company,String position) { return json.createObjectNode().put("company",company).put("position",position); }
    JsonNode send(MockHttpServletRequestBuilder request,Object body) throws Exception {
        return json.readTree(mvc.perform(request.contentType("application/json").content(json.writeValueAsBytes(body)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode read(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void sameCompanyHasIndependentJobsAndAllFieldsPersist() throws Exception {
        var first=send(post(API),input("同公司集成测试","Java 工程师"));
        var second=send(post(API),input("同公司集成测试","测试工程师"));
        assertThat(first.path("id").asText()).isNotEqualTo(second.path("id").asText());
        assertThat(first.path("status").asText()).isEqualTo("TO_APPLY");
        assertThat(first.path("archived").asBoolean()).isFalse();
        var body=input("同公司集成测试","后端开发").put("location","上海").put("platform","公司官网")
            .put("url","https://example.com/jobs/1").put("jd","Java, SQL\n负责\"服务\"\n  保留空格 ")
            .put("jobType","FULL_TIME").put("status","INTERVIEW").put("appliedDate","2026-10-20")
            .put("nextActionDate","2026-10-25").put("interviewAt","2026-10-24T10:30:00+08:00")
            .put("interviewNote","视频面试").put("notes","第一行\r\n第二行");
        String path=API+"/"+first.path("id").asText();
        var updated=send(put(path),body);
        assertThat(updated.path("createdAt")).isEqualTo(first.path("createdAt"));
        var loaded=read(path);
        body.fields().forEachRemaining(field->{
            if(!field.getKey().equals("interviewAt"))assertThat(loaded.path(field.getKey())).isEqualTo(field.getValue());
        });
        assertThat(java.time.OffsetDateTime.parse(loaded.path("interviewAt").asText()).toInstant().toString()).isEqualTo("2026-10-24T02:30:00Z");
        assertThat(read(API+"?company=同公司集成测试")).hasSize(2);
        assertThat(Files.size(DATA.resolve("interview.db"))).isPositive();
    }
    @Test void statusesAreReversibleAndArchiveIsIndependentAndDeleteIsIsolated() throws Exception {
        var first=send(post(API),input("生命周期测试","第一岗"));
        var other=send(post(API),input("生命周期测试","第二岗"));
        String path=API+"/"+first.path("id").asText();
        for(String value:new String[]{"APPLIED","ASSESSMENT","INTERVIEW","OFFER","REJECTED","WITHDRAWN","TO_APPLY"})
            assertThat(send(patch(path+"/status"),java.util.Map.of("status",value)).path("status").asText()).isEqualTo(value);
        var archived=send(patch(path+"/archive"),java.util.Map.of("archived",true));
        assertThat(archived.path("status").asText()).isEqualTo("TO_APPLY");
        assertThat(read(API+"?company=生命周期测试")).hasSize(1);
        assertThat(read(API+"?company=生命周期测试&archived=true")).hasSize(1);
        assertThat(read(API+"?company=生命周期测试&archived=all")).hasSize(2);
        send(patch(path+"/archive"),java.util.Map.of("archived",false));
        assertThat(read(API+"?company=生命周期测试")).hasSize(2);
        mvc.perform(delete(path)).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        assertThat(read(API+"/"+other.path("id").asText()).path("position").asText()).isEqualTo("第二岗");
    }
    @Test void filtersAndSortUseLiteralCompanyAndNullDatesLast() throws Exception {
        send(post(API),input("筛选%公司","晚").put("nextActionDate","2026-11-03").put("status","APPLIED"));
        send(post(API),input("筛选%公司","早").put("nextActionDate","2026-11-01").put("status","APPLIED"));
        send(post(API),input("筛选%公司","空"));
        send(post(API),input("筛选其他公司","无关"));
        var asc=json.readTree(mvc.perform(get(API).param("company","筛选%公司").param("sort","nextActionDate").param("direction","asc"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(asc.findValuesAsText("position")).containsExactly("早","晚","空");
        var desc=json.readTree(mvc.perform(get(API).param("company","筛选%公司").param("sort","nextActionDate").param("direction","desc"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(desc.findValuesAsText("position")).containsExactly("晚","早","空");
        var filtered=json.readTree(mvc.perform(get(API).param("company","筛选%公司").param("status","APPLIED"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered).hasSize(2);
        mvc.perform(get(API).param("sort","company; DROP TABLE materials")).andExpect(status().isBadRequest());
        mvc.perform(get(API).param("archived","yes")).andExpect(status().isBadRequest());
    }
    @Test void validatesWithoutSilentlyChangingOrTruncatingFields() throws Exception {
        for(var body:java.util.List.of(input(" ","岗"),input("企业",""),input("长".repeat(201),"岗"),
            input("企业","岗").put("url","javascript:alert(1)"),input("企业","岗").put("url","https://user:pass@example.com"),
            input("企业","岗").put("status","UNKNOWN"),input("企业","岗").put("jobType","UNKNOWN"),
            input("企业","岗").put("appliedDate","2026-02-30"),input("企业","岗").put("interviewAt","2026-10-23T10:00"),
            input("企业","岗").put("notes","字".repeat(12001)),input("企业","岗").put("company",123)))
            mvc.perform(post(API).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        var row=send(post(API),input("  原始公司  ","岗位").put("appliedDate","").putNull("nextActionDate"));
        assertThat(row.path("company").asText()).isEqualTo("  原始公司  ");
        assertThat(row.path("appliedDate").isNull()).isTrue();
        mvc.perform(patch(API+"/"+row.path("id").asText()+"/archive").contentType("application/json").content("{\"archived\":\"true\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(API).header("Origin","https://evil.example").contentType("application/json").content(json.writeValueAsBytes(input("企业","岗位")))).andExpect(status().isForbidden());
    }
    @Test void additiveSchemaCanRunRepeatedlyWithoutChangingLegacyData() throws Exception {
        Path legacy=Files.createTempDirectory("job-schema-legacy-");
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+legacy.resolve("interview.db"));var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE materials(id TEXT PRIMARY KEY,kind TEXT NOT NULL,name TEXT NOT NULL,content TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
            statement.execute("INSERT INTO materials VALUES('legacy','JD','旧岗位描述','原文与备注','2026-10-01T00:00:00Z','2026-10-01T00:00:00Z')");
        }
        var database=new cn.interview.mapper.SqliteStore(legacy.toString(),json);
        var service=new JobApplicationService(new cn.interview.mapper.JobApplicationStore(database));
        var row=service.create(input("持久公司","持久岗位").put("notes","中文\n备注"));
        for(int attempt=0;attempt<2;attempt++) {
            var reopened=new cn.interview.mapper.SqliteStore(legacy.toString(),json);
            assertThat(new cn.interview.mapper.JobApplicationStore(reopened).get(row.id())).isEqualTo(row);
            assertThat(reopened.material("legacy").content()).isEqualTo("原文与备注");
            assertThat(reopened.materials(null)).hasSize(1);
        }
    }

}
