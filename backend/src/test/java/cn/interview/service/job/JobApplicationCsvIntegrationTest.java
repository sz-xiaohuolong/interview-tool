package cn.interview.service.job;

import cn.interview.entity.JobApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.llm.provider=mock")
@AutoConfigureMockMvc
class JobApplicationCsvIntegrationTest {
    static final Path DATA;
    static { try { DATA=Files.createTempDirectory("job-csv-test-"); } catch(Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir",()->DATA.toString()); }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @BeforeEach void clearSyntheticJobs() throws Exception {
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+DATA.resolve("interview.db"));var statement=connection.createStatement()) { statement.executeUpdate("DELETE FROM job_applications"); }
    }
    JsonNode body(String path) throws Exception { return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    JsonNode create(Map<String,Object> input) throws Exception { return json.readTree(mvc.perform(post("/api/job-applications").contentType("application/json").content(json.writeValueAsBytes(input))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    MockMultipartFile csv(String text) { return new MockMultipartFile("file","jobs.csv","text/csv",text.getBytes(StandardCharsets.UTF_8)); }
    @Test void exportEscapesChineseQuotesCommasNewlinesAndFormulaPrefixes() throws Exception {
        var record=create(Map.of("company","=危险公司","position","后端,\"核心\"","jd","第一行\n第二行","notes","+公式\r\n下一行","url","https://example.com/job/1"));
        var result=mvc.perform(get("/api/job-applications/csv/export")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(result.getContentType()).contains("text/csv");
        String csv=new String(result.getContentAsByteArray(),StandardCharsets.UTF_8);
        assertThat(csv).contains("\uFEFF").contains("=危险公司").contains("文本:").contains("\"后端,\"" ).contains("第一行\n第二行");
        assertThat(csv).contains(record.path("id").asText());
    }
    @Test void previewDoesNotWriteAndImportRestoresFullRecordThenDuplicateIsSkipped() throws Exception {
        String csv="\uFEFF格式,记录ID,公司名称,岗位名称,工作地点,招聘平台,原始岗位链接,JD正文,岗位类型,投递状态,投递日期,下次行动日期,面试时间,面试说明,备注,已归档,创建时间,修改时间\n"
            +"投递记录-1,91f58004-0000-4000-8000-000000000001,恢复公司,Java后端,深圳,BOSS,https://example.com/j/1,\"第一行\n第二行\",FULL_TIME,APPLIED,2026-10-20,2026-10-25,2026-10-24T02:30:00Z,技术面,\"备注,多行\",false,2026-10-21T00:00:00Z,2026-10-22T00:00:00Z\n";
        var preview=mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(csv))).andExpect(status().isOk()).andReturn().getResponse();
        JsonNode p=json.readTree(preview.getContentAsString()); assertThat(p.path("total").asInt()).isEqualTo(1); assertThat(p.path("valid").asInt()).isEqualTo(1); assertThat(p.path("written").asInt()).isZero(); assertThat(body("/api/job-applications?archived=all")).isEmpty();
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(csv)).param("previewToken",p.path("previewToken").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
        assertThat(body("/api/job-applications/91f58004-0000-4000-8000-000000000001").path("jd").asText()).isEqualTo("第一行\n第二行");
        JsonNode repeated=json.readTree(mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(csv))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(repeated.path("duplicates").asInt()).isEqualTo(1);
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(csv)).param("previewToken",repeated.path("previewToken").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.duplicates").value(1));
    }
    @Test void invalidRowsConflictEmptyAndOversizedAreExplicitAndAtomic() throws Exception {
        String header="格式,记录ID,公司名称,岗位名称,工作地点,招聘平台,原始岗位链接,JD正文,岗位类型,投递状态,投递日期,下次行动日期,面试时间,面试说明,备注,已归档,创建时间,修改时间\n";
        String invalid=header+"投递记录-1,,公司,,地点,,javascript:bad,,BAD,NOPE,not-date,,,,,false,,\n";
        mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(invalid))).andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(0)).andExpect(jsonPath("$.errors[0].line").value(2));
        var before=body("/api/job-applications?archived=all").size();
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(invalid)).param("previewToken","missing")).andExpect(status().isBadRequest());
        assertThat(body("/api/job-applications?archived=all")).hasSize(before);
        mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(""))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/job-applications/csv/preview").file(new MockMultipartFile("file","huge.csv","text/csv",new byte[5*1024*1024+1]))).andExpect(status().isBadRequest());
        var existing=create(Map.of("company","原公司","position","原岗位"));
        String conflict=header+"投递记录-1,"+existing.path("id").asText()+",变化公司,Java,地点,,https://example.com,,OTHER,TO_APPLY,,,,,,false,2026-10-21T00:00:00Z,2026-10-22T00:00:00Z\n";
        mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(conflict))).andExpect(status().isOk()).andExpect(jsonPath("$.conflicts").value(1)).andExpect(jsonPath("$.valid").value(0));
    }
    @Test void templateHasStableHeaders() throws Exception {
        String value=new String(mvc.perform(get("/api/job-applications/csv/template")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
        assertThat(value).startsWith("\uFEFF格式,记录ID,公司名称").contains("修改时间");
    }
    String header() throws Exception { return new String(mvc.perform(get("/api/job-applications/csv/template")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),StandardCharsets.UTF_8); }
    JsonNode preview(String text) throws Exception { return json.readTree(mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(text))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    void confirm(String text,JsonNode preview) throws Exception { mvc.perform(multipart("/api/job-applications/csv/import").file(csv(text)).param("previewToken",preview.path("previewToken").asText())).andExpect(status().isOk()); }

    @Test void exactExportRoundTripPreservesEveryFieldArchiveAndTimestampsAcrossDatabaseReopen() throws Exception {
        var values=json.createObjectNode().put("company","文本:=公司").put("position","带,逗号与\"引号\"")
            .put("location"," 上海 ").put("platform","自定义平台").put("url","https://example.com/?a=1&b=2")
            .put("jd","\t=第一行\r\n第二行\n").put("jobType","SUMMER_INTERNSHIP").put("status","OFFER")
            .put("appliedDate","2026-10-01").put("nextActionDate","2026-12-01").put("interviewAt","2026-10-23T21:35:21.123+08:00")
            .put("interviewNote","  @面试安排").put("notes","文本:真实前缀\n+第二行");
        var created=json.readTree(mvc.perform(post("/api/job-applications").contentType("application/json").content(json.writeValueAsBytes(values))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id=created.path("id").asText();
        mvc.perform(patch("/api/job-applications/"+id+"/archive").contentType("application/json").content("{\"archived\":true}")).andExpect(status().isOk());
        create(Map.of("company","同公司保留","position","第二岗位"));
        var before=body("/api/job-applications?archived=all&sort=createdAt&direction=asc");
        String exported=new String(mvc.perform(get("/api/job-applications/csv/export")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
        try(var parsed=org.apache.commons.csv.CSVFormat.RFC4180.parse(new java.io.StringReader(exported.substring(1)))) {
            var rows=parsed.getRecords();
            assertThat(rows.get(1).get(2)).isEqualTo("文本:文本:=公司");
            assertThat(rows.get(1).get(7)).isEqualTo("文本:\t=第一行\r\n第二行\n");
        }
        for(var row:before)mvc.perform(delete("/api/job-applications/"+row.path("id").asText())).andExpect(status().isNoContent());
        var p=preview(exported);assertThat(p.path("created").asInt()).isEqualTo(2);confirm(exported,p);
        assertThat(body("/api/job-applications?archived=all&sort=createdAt&direction=asc")).isEqualTo(before);
        var reopened=new cn.interview.mapper.JobApplicationStore(new cn.interview.mapper.SqliteStore(DATA.toString(),json));
        assertThat(json.<JsonNode>valueToTree(reopened.list(null,null,"all","createdAt","asc"))).isEqualTo(before);
        assertThat(body("/api/job-applications")).hasSize(1);
    }
    @Test void blankIdsAreDeduplicatedWithinFileAgainstExistingAndAfterRepeatedImport() throws Exception {
        String row="投递记录-1,,匿名ID公司,岗位,,,,,OTHER,TO_APPLY,,,,,,false,,\r\n";
        String text=header()+row+row;
        var p=preview(text);assertThat(p.path("created").asInt()).isEqualTo(1);assertThat(p.path("duplicates").asInt()).isEqualTo(1);
        confirm(text,p);assertThat(body("/api/job-applications?archived=all")).hasSize(1);
        var again=preview(text);assertThat(again.path("created").asInt()).isZero();assertThat(again.path("duplicates").asInt()).isEqualTo(2);
        confirm(text,again);assertThat(body("/api/job-applications?archived=all")).hasSize(1);
        var saved=body("/api/job-applications?archived=all").get(0);
        mvc.perform(patch("/api/job-applications/"+saved.path("id").asText()+"/status").contentType("application/json").content("{\"status\":\"APPLIED\"}")).andExpect(status().isOk());
        assertThat(preview(header()+row).path("conflicts").asInt()).isEqualTo(1);
    }
    @Test void staleOrForgedPreviewCannotWriteAndInvalidBatchDoesNotPartiallyImport() throws Exception {
        String good="投递记录-1,,原子公司,岗位,,,,,OTHER,TO_APPLY,,,,,,false,,\r\n";
        String invalid="投递记录-1,,错误公司,岗位,,,,,OTHER,UNKNOWN,,,,,,false,,\r\n";
        String text=header()+good; var p=preview(text);
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(text)).param("previewToken","fake")).andExpect(status().isConflict());
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(text.replace("原子公司","变更公司"))).param("previewToken",p.path("previewToken").asText())).andExpect(status().isConflict());
        String mixed=header()+good+invalid;var bad=preview(mixed);
        assertThat(bad.path("created").asInt()).isEqualTo(1);assertThat(bad.path("errors").size()).isEqualTo(1);
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(mixed)).param("previewToken",bad.path("previewToken").asText())).andExpect(status().isBadRequest());
        assertThat(body("/api/job-applications?archived=all")).isEmpty();
        create(Map.of("company","原子公司","position","岗位"));
        mvc.perform(multipart("/api/job-applications/csv/import").file(csv(text)).param("previewToken",p.path("previewToken").asText())).andExpect(status().isConflict());
        assertThat(body("/api/job-applications?archived=all")).hasSize(1);
    }
    @Test void rejectsHeaderOnlyWrongHeadersMalformedUtf8UnclosedQuotesTooManyRowsAndLongFields() throws Exception {
        String header=header();
        for(String invalid:java.util.List.of(header,"company,position\n企业,岗位",header+"\"未结束",header+"投递记录-1,,公司,岗位,,,,,OTHER,TO_APPLY,,,,,,false,,\n".repeat(5001)))
            mvc.perform(multipart("/api/job-applications/csv/preview").file(csv(invalid))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/job-applications/csv/preview").file(new MockMultipartFile("file","bad.csv","text/csv",new byte[]{(byte)0xc3,0x28}))).andExpect(status().isBadRequest());
        String tooLong=header+"投递记录-1,,"+"长".repeat(201)+",岗位,,,,,OTHER,TO_APPLY,,,,,,false,,\n";
        assertThat(preview(tooLong).path("errors").get(0).path("message").asText()).contains("200");
    }

    @Test void spreadsheetUnicodeWhitespaceAndFullWidthFormulaMarkersAreProtected() throws Exception {
        create(Map.of("company","\u3000=公式公司","position","\uFF1D1+1","notes","\u200B@SUM(1,1)"));
        String exported=new String(mvc.perform(get("/api/job-applications/csv/export")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);
        try(var parsed=org.apache.commons.csv.CSVFormat.RFC4180.parse(new java.io.StringReader(exported.substring(1)))) {
            var row=parsed.getRecords().get(1);
            assertThat(row.get(2)).isEqualTo("文本:\u3000=公式公司");
            assertThat(row.get(3)).isEqualTo("文本:\uFF1D1+1");
            assertThat(row.get(14)).isEqualTo("文本:\u200B@SUM(1,1)");
        }
    }

}
