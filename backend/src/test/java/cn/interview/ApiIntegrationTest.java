package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.llm.provider=mock")
@AutoConfigureMockMvc
class ApiIntegrationTest {
    static final Path DATA;
    static { try { DATA = Files.createTempDirectory("interview-test-"); } catch (Exception e) { throw new RuntimeException(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) { r.add("app.data-dir", () -> DATA.toString()); }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    JsonNode postJson(String path, Object body) throws Exception {
        return json.readTree(mvc.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    JsonNode session() throws Exception { return postJson("/api/sessions", Map.of("mode","QUICK","role","Java 后端","durationMinutes",5)); }
    JsonNode answer(JsonNode s, String key) throws Exception {
        return postJson("/api/sessions/"+s.path("id").asText()+"/answers", Map.of("content","我使用事务保证数据一致性，同时在失败时回滚。", "requestId",key,"expectedMessageId",s.path("messages").get(s.path("messages").size()-1).path("id").asText()));
    }
    @Test void materialsPersistAndValidateInputs() throws Exception {
        JsonNode m = postJson("/api/materials", Map.of("kind","PROJECT","name","订单服务","content","Java、SQLite；个人负责订单接口。"));
        mvc.perform(get("/api/materials")).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("订单服务"));
        mvc.perform(put("/api/materials/"+m.path("id").asText()).contentType("application/json").content("{\"name\":\"修改后\",\"content\":\"职责更新\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("修改后"));
        mvc.perform(post("/api/materials").contentType("application/json").content("{\"kind\":\"PROJECT\",\"name\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/materials/"+m.path("id").asText())).andExpect(status().isNoContent());
    }
    @Test void uploadRejectsBadTypeAndEmptyContentAndMarksTruncation() throws Exception {
        mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file","a.exe","application/octet-stream",new byte[]{1})).param("kind","RESUME")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file","empty.md","text/plain","  ".getBytes())).param("kind","RESUME")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file","resume.md","text/plain","项目经验".repeat(7000).getBytes())).param("kind","RESUME"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documents[0].truncated").value(true));
    }
    @Test void interviewCapsFollowUpsAndRejectsDuplicateOrStaleAnswers() throws Exception {
        JsonNode s = session(); String id=s.path("id").asText();
        assertThat(s.path("totalQuestions").asInt()).isEqualTo(3);
        assertThat(s.has("plan")).isFalse();
        String first=s.path("messages").get(0).path("id").asText();
        s=answer(s,"one"); assertThat(s.path("followUpCount").asInt()).isEqualTo(1);
        JsonNode duplicate=postJson("/api/sessions/"+id+"/answers",Map.of("content","我使用事务保证数据一致性，同时在失败时回滚。","requestId","one","expectedMessageId",first));
        assertThat(duplicate.path("messages").size()).isEqualTo(3);
        mvc.perform(post("/api/sessions/"+id+"/answers").contentType("application/json").content(json.writeValueAsString(Map.of("content","过期回答","requestId","stale","expectedMessageId",first)))).andExpect(status().isConflict());
        s=answer(s,"two"); assertThat(s.path("followUpCount").asInt()).isEqualTo(2);
        s=answer(s,"three"); assertThat(s.path("questionIndex").asInt()).isEqualTo(1); assertThat(s.path("followUpCount").asInt()).isZero();
        s=postJson("/api/sessions/"+id+"/finish",Map.of());
        assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
        mvc.perform(post("/api/sessions/"+id+"/answers").contentType("application/json").content(json.writeValueAsString(Map.of("content","结束后","requestId","late","expectedMessageId",first)))).andExpect(status().isConflict());
        mvc.perform(get("/api/sessions/"+id+"/report")).andExpect(status().isOk()).andExpect(jsonPath("$.dimensions.length()").value(4));
        mvc.perform(get("/api/sessions/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(7));
        assertThat(Files.exists(DATA.resolve("interview.db"))).isTrue();
    }
    @Test void predictionsHaveTenQuestionsAndPracticeOneOnly() throws Exception {
        JsonNode resume=postJson("/api/materials",Map.of("kind","RESUME","name","我的简历","content","使用 Spring Boot 和 SQLite 开发订单接口，负责事务。"));
        JsonNode p=postJson("/api/predictions",Map.of("resumeId",resume.path("id").asText(),"role","Java 后端","jd","后端开发"));
        assertThat(p.path("questions").size()).isEqualTo(10);
        JsonNode s=postJson("/api/sessions",Map.of("mode","RESUME","predictionId",p.path("id").asText(),"questionId",p.path("questions").get(0).path("id").asText()));
        assertThat(s.path("totalQuestions").asInt()).isEqualTo(1);
        mvc.perform(delete("/api/materials/"+resume.path("id").asText())).andExpect(status().isNoContent());
        s=answer(s,"a"); s=answer(s,"b"); s=answer(s,"c");
        assertThat(s.path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void noAnswersCannotGenerateInventedScores() throws Exception {
        JsonNode s=session(); String id=s.path("id").asText();
        postJson("/api/sessions/"+id+"/finish",Map.of());
        JsonNode report=json.readTree(mvc.perform(get("/api/sessions/"+id+"/report")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.path("summary").asText()).contains("没有");
        assertThat(report.path("dimensions").get(0).path("reason").asText()).contains("回答");
    }
    @Test void crossOriginWritesAreRejected() throws Exception {
        mvc.perform(post("/api/materials").header("Origin","https://evil.example").contentType("application/json").content("{\"kind\":\"JD\",\"name\":\"岗位\",\"content\":\"Java\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("mock")).andExpect(jsonPath("$.apiKey").doesNotExist());
    }
    @Test void pdfDocxAndProjectDocumentsAreRealFilesAndCanBeRemoved() throws Exception {
        byte[] pdf;
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument();var bytes=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)) {
                stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,700);stream.showText("Java Spring Boot resume, API transactions");stream.endText();
            }
            document.save(bytes);pdf=bytes.toByteArray();
        }
        JsonNode resume=json.readTree(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file","resume.pdf","application/pdf",pdf)).param("kind","RESUME")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(resume.path("documents").get(0).path("text").asText()).contains("Java Spring Boot");
        JsonNode project=postJson("/api/materials",Map.of("kind","PROJECT","name","测试文档项目","content",""));
        String id=project.path("id").asText();byte[] docx;
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var bytes=new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("我负责订单接口，使用事务保存订单与库存。");document.write(bytes);docx=bytes.toByteArray();
        }
        project=json.readTree(mvc.perform(multipart("/api/materials/"+id+"/documents").file(new MockMultipartFile("file","design.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",docx))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String documentId=project.path("documents").get(0).path("id").asText();
        assertThat(project.path("documents").get(0).path("text").asText()).contains("事务保存订单");
        mvc.perform(multipart("/api/materials/"+id+"/documents").file(new MockMultipartFile("file","README.txt","text/plain","项目背景：个人订单管理。".getBytes()))).andExpect(status().isOk()).andExpect(jsonPath("$.documents.length()").value(2));
        mvc.perform(delete("/api/materials/"+id+"/documents/"+documentId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/materials?kind=PROJECT")).andExpect(status().isOk());
    }
    @Test void contextTruncationAndDeletedProjectDoNotChangeSessionSnapshot() throws Exception {
        JsonNode project=postJson("/api/materials",Map.of("kind","PROJECT","name","长项目","content","设计资料".repeat(6000)));
        String id=project.path("id").asText();
        mvc.perform(multipart("/api/materials/"+id+"/documents").file(new MockMultipartFile("file","notes.md","text/plain","职责说明".repeat(6000).getBytes()))).andExpect(status().isOk());
        JsonNode s=postJson("/api/sessions",Map.of("mode","PROJECT","materialId",id,"durationMinutes",5));
        assertThat(s.path("contextTruncated").asBoolean()).isTrue();assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");
        mvc.perform(delete("/api/materials/"+id)).andExpect(status().isNoContent());
        s=answer(s,"project-answer");assertThat(s.path("status").asText()).isEqualTo("IN_PROGRESS");
        Store reopened=new Store(DATA.toString(),json);
        assertThat(reopened.session(s.path("id").asText()).path("context").asText()).contains("设计资料").hasSize(30000);
    }
    @Test void reopeningDatabaseRecoversPendingAnswerWithoutAddingAnotherAnswer() throws Exception {
        JsonNode s=session();s=answer(s,"restart-answer");String id=s.path("id").asText();
        Store reopened=new Store(DATA.toString(),json);var saved=reopened.session(id);
        ((com.fasterxml.jackson.databind.node.ArrayNode)saved.path("messages")).remove(2);saved.put("pendingAction","INTERVIEW");reopened.saveSession(saved);
        LlmClient mock=new LlmClient(json,"mock","","","",1,"");InterviewService recovered=new InterviewService(reopened,new MaterialService(reopened),mock,json);recovered.recover();
        assertThat(recovered.get(id).path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(recovered.get(id).path("messages").size()).isEqualTo(2);
        assertThat(recovered.retry(id).path("messages").size()).isEqualTo(3);
    }
    @Test void rejectsInvalidFileSizeAndUntrustedLocalPorts() throws Exception {
        mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file","large.md","text/plain",new byte[5*1024*1024+1])).param("kind","RESUME")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/materials").header("Origin","http://127.0.0.1:9999").contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(post("/api/materials").header("Origin","http://127.0.0.1:5173").contentType("application/json").content("{\"kind\":\"JD\",\"name\":\"本机开发页面\",\"content\":\"Java\"}")).andExpect(status().isOk());
    }

    @Test void encodedApiPathCannotBypassOriginChecks() throws Exception {
        mvc.perform(post(java.net.URI.create("/%61pi/materials")).header("Origin","https://evil.example").header("Sec-Fetch-Site","cross-site").contentType("application/json").content("{\"kind\":\"JD\",\"name\":\"不可信请求\",\"content\":\"恶意跨站写入\"}")).andExpect(status().isForbidden());
    }

    @Test void savedPredictionWithAnswerLeakCannotBecomeInterviewQuestion() throws Exception {
        JsonNode resume=postJson("/api/materials",Map.of("kind","RESUME","name","旧押题记录","content","我负责 Java 后端事务处理。"));
        JsonNode p=postJson("/api/predictions",Map.of("resumeId",resume.path("id").asText()));
        Store store=new Store(DATA.toString(),json);var saved=store.prediction(p.path("id").asText());((com.fasterxml.jackson.databind.node.ObjectNode)saved.path("questions").get(0)).put("question","请解释事务？参考答案：事务满足 ACID。");store.jdbc.update("UPDATE predictions SET payload=? WHERE id=?",store.encode(saved),p.path("id").asText());
        mvc.perform(post("/api/sessions").contentType("application/json").content(json.writeValueAsString(Map.of("mode","RESUME","predictionId",p.path("id").asText(),"questionId",p.path("questions").get(0).path("id").asText())))).andExpect(status().isBadGateway());
    }

}
