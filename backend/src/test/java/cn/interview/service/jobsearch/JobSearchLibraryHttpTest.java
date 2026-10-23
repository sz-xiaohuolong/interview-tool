package cn.interview.service.jobsearch;

import cn.interview.client.jobsearch.JobSourceGateway;
import cn.interview.entity.jobsearch.JobRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 合成来源仅在测试配置注册；HTTP、业务服务和 SQLite 均使用正式实现。 */
@SpringBootTest(properties="app.llm.provider=mock")
@AutoConfigureMockMvc
@Import(JobSearchLibraryHttpTest.SyntheticSource.class)
class JobSearchLibraryHttpTest {
    static final Path DATA;
    static{try{DATA=Files.createTempDirectory("job-search-library-http-");}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("app.data-dir",()->DATA.toString());}
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;
    @TestConfiguration static class SyntheticSource {
        @Bean @Primary JobSourceGateway synthetic(){return (source,keyword,city)->{
            if(!city.equals("shenzhen"))throw new AssertionError("未知城市替身调用");
            if(Set.of("合成补充岗位","合成标题变化","合成公司变化","合成城市变化","合成地点变化","合成旧正文").contains(keyword)){
                if(!Set.of("BOSS","LIEPIN").contains(source))throw new AssertionError("未知补充来源替身调用");
                boolean boss=source.equals("BOSS");String id="synthetic-append";
                var job=new JobRecord(source+":"+id,source,id,boss&&keyword.equals("合成标题变化")?"变更标题":"合成补充岗位",boss&&keyword.equals("合成公司变化")?"变更公司":"合成公司",boss&&keyword.equals("合成城市变化")?"北京":"深圳",boss&&keyword.equals("合成地点变化")?"深圳福田":"深圳南山",boss?"https://www.zhipin.com/job_detail/"+id+".html":"https://www.liepin.com/job/"+id+".shtml","20-30K",20d,30d,13,"3年","本科",null,"2026-10-24T00:00:00Z",null,null);
                return new JobSourceGateway.Cursor(){boolean fetched;public JobSourceGateway.Batch next(){if(fetched)throw new AssertionError("未知补充下一批调用");fetched=true;return new JobSourceGateway.Batch(List.of(job),false);}public JobRecord detail(JobRecord current){return current.withDescription(keyword.equals("合成旧正文")?"合成旧完整正文":"合成来源完整正文","2026-10-24T00:01:00Z");}public void close(){}};
            }
            if(!source.equals("BOSS")||!keyword.equals("合成 Java 岗位"))throw new AssertionError("未知来源替身调用");
            return new JobSourceGateway.Cursor(){boolean fetched;public JobSourceGateway.Batch next(){if(fetched)throw new AssertionError("未知下一批调用");fetched=true;return new JobSourceGateway.Batch(List.of(new JobRecord("BOSS:synthetic","BOSS","synthetic","合成 Java 岗位","合成公司","深圳","深圳南山","https://www.zhipin.com/job_detail/synthetic.html","20-30K",20d,30d,13,"3年","本科",null,"2026-10-24T00:00:00Z",null,null)),false);}public JobRecord detail(JobRecord job){return job.withDescription("合成来源完整正文","2026-10-24T00:01:00Z");}public void close(){}};
        };}
    }
    JsonNode send(MockHttpServletRequestBuilder request,Object body)throws Exception{return json.readTree(mvc.perform(request.contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    JsonNode read(String path)throws Exception{return json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    JsonNode task()throws Exception{return task("合成 Java 岗位",List.of("BOSS"));}
    JsonNode task(String keyword,List<String> sources)throws Exception{var initial=send(post("/api/job-search/tasks"),Map.of("keyword",keyword,"cityId","shenzhen","sources",sources));for(int i=0;i<100;i++){var t=read("/api/job-search/tasks/"+initial.path("id").asText());if(!t.path("status").asText().equals("RUNNING"))return t;Thread.sleep(10);}throw new AssertionError("任务未完成");}
    List<String> originalFavoriteRow(String id)throws Exception{
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+DATA.resolve("interview.db"));var statement=connection.prepareStatement("SELECT f.created_at,s.snapshot_json,s.saved_at FROM job_search_favorites f JOIN job_search_favorite_sources s ON s.favorite_id=f.id WHERE f.id=? AND s.source_key='BOSS:synthetic-append'")){
            statement.setString(1,id);try(var result=statement.executeQuery()){assertThat(result.next()).isTrue();return List.of(result.getString(1),result.getString(2),result.getString(3));}
        }
    }
    void appendRejected(String taskId,String favoriteId)throws Exception{mvc.perform(post("/api/job-search/favorites").contentType("application/json").content(json.writeValueAsBytes(Map.of("taskId",taskId,"jobKeys",List.of("LIEPIN:synthetic-append"),"favoriteId",favoriteId)))).andExpect(status().isBadRequest());}
    @Test void currentTaskEvidenceAllowsAddingSourceWithoutChangingOriginalOverviewSnapshot()throws Exception{
        String taskId=task("合成补充岗位",List.of("BOSS","LIEPIN")).path("id").asText();
        var favorite=send(post("/api/job-search/favorites"),Map.of("taskId",taskId,"jobKeys",List.of("BOSS:synthetic-append")));String favoriteId=favorite.path("id").asText();var before=originalFavoriteRow(favoriteId);
        send(post("/api/job-search/tasks/"+taskId+"/jobs/LIEPIN:synthetic-append/detail"),Map.of());appendRejected(taskId,favoriteId);assertThat(originalFavoriteRow(favoriteId)).isEqualTo(before);
        send(post("/api/job-search/tasks/"+taskId+"/jobs/BOSS:synthetic-append/detail"),Map.of());
        var added=send(post("/api/job-search/favorites"),Map.of("taskId",taskId,"jobKeys",List.of("LIEPIN:synthetic-append"),"favoriteId",favoriteId));
        assertThat(added.path("id")).isEqualTo(favorite.path("id"));assertThat(added.path("createdAt")).isEqualTo(favorite.path("createdAt"));assertThat(added.path("sources")).hasSize(2);
        for(JsonNode source:added.path("sources"))if(source.path("source").asText().equals("BOSS"))assertThat(source).isEqualTo(favorite.path("sources").get(0));
        assertThat(originalFavoriteRow(favoriteId)).isEqualTo(before);
        mvc.perform(delete("/api/job-search/favorites/"+favoriteId)).andExpect(status().isNoContent());
    }
    @Test void changedOverviewOrConflictingSavedFullBodyCannotBeReplacedByCurrentEvidence()throws Exception{
        for(String changed:List.of("合成标题变化","合成公司变化","合成城市变化","合成地点变化","合成旧正文")){
            String oldTask=task(changed.equals("合成旧正文")?changed:"合成补充岗位",List.of("BOSS")).path("id").asText();
            if(changed.equals("合成旧正文"))send(post("/api/job-search/tasks/"+oldTask+"/jobs/BOSS:synthetic-append/detail"),Map.of());
            var favorite=send(post("/api/job-search/favorites"),Map.of("taskId",oldTask,"jobKeys",List.of("BOSS:synthetic-append")));String id=favorite.path("id").asText();var before=originalFavoriteRow(id);
            String current=task(changed.equals("合成旧正文")?"合成补充岗位":changed,List.of("BOSS","LIEPIN")).path("id").asText();
            for(String source:List.of("BOSS","LIEPIN"))send(post("/api/job-search/tasks/"+current+"/jobs/"+source+":synthetic-append/detail"),Map.of());
            appendRejected(current,id);assertThat(originalFavoriteRow(id)).isEqualTo(before);
            mvc.perform(delete("/api/job-search/favorites/"+id)).andExpect(status().isNoContent());
        }
    }
    @Test void exactHttpPathsDriveSnapshotJdLookupEditAndDeletedJdRecreation()throws Exception{
        var task=task();String taskId=task.path("id").asText();assertThat(task.path("jobs")).hasSize(1);assertThat(task.path("jobs").get(0).path("key").asText()).isEqualTo("BOSS:synthetic");
        var favorite=send(post("/api/job-search/favorites"),Map.of("taskId",taskId,"jobKeys",List.of("BOSS:synthetic")));String favoriteId=favorite.path("id").asText();assertThat(favorite.path("sources").get(0).path("description").isNull()).isTrue();
        var detail=send(post("/api/job-search/tasks/"+taskId+"/jobs/BOSS:synthetic/detail"),Map.of());assertThat(detail.path("description").asText()).isEqualTo("合成来源完整正文");assertThat(read("/api/job-search/favorites").get(0)).isEqualTo(favorite);
        var input=json.createObjectNode().put("taskId",taskId).put("jobKey","BOSS:synthetic").put("name","已选择的 JD").put("content","合成来源完整正文");
        for(var invalid:List.of(input.deepCopy().put("name"," "),input.deepCopy().put("name","中".repeat(121)),input.deepCopy().put("content","\n "),input.deepCopy().put("content","中".repeat(24001))))mvc.perform(post("/api/job-search/jd").contentType("application/json").content(json.writeValueAsBytes(invalid))).andExpect(status().isBadRequest());
        var saved=send(post("/api/job-search/jd"),input);String materialId=saved.path("materialId").asText();assertThat(saved.path("created").asBoolean()).isTrue();assertThat(saved.path("source").path("contentEdited").asBoolean()).isFalse();
        var materials=read("/api/materials?kind=JD");assertThat(materials).hasSize(1);assertThat(materials.get(0).path("id").asText()).isEqualTo(materialId);assertThat(materials.get(0).path("jobSource")).isEqualTo(saved.path("source"));
        send(put("/api/materials/"+materialId),Map.of("name","用户编辑后的 JD","content","用户编辑后的正文"));var retried=send(post("/api/job-search/jd"),input);assertThat(retried.path("created").asBoolean()).isFalse();assertThat(retried.path("source").path("contentEdited").asBoolean()).isTrue();
        var lookup=send(post("/api/job-search/jd-links/lookup"),Map.of("keys",List.of("BOSS:synthetic","LIEPIN:missing")));assertThat(lookup.path("BOSS:synthetic").path("materialId").asText()).isEqualTo(materialId);assertThat(lookup.path("LIEPIN:missing").isNull()).isTrue();
        mvc.perform(delete("/api/materials/"+materialId)).andExpect(status().isNoContent());input.remove("taskId");input.put("favoriteId",favoriteId);var recreated=send(post("/api/job-search/jd"),input);assertThat(recreated.path("created").asBoolean()).isTrue();assertThat(recreated.path("source").path("contentEdited").asBoolean()).isTrue();
        mvc.perform(delete("/api/job-search/favorites/"+favoriteId)).andExpect(status().isNoContent());assertThat(read("/api/job-search/favorites")).isEmpty();assertThat(read("/api/materials?kind=JD")).hasSize(1);
        mvc.perform(post("/api/job-search/tasks/"+taskId+"/jobs/BOSS:untrusted/detail").contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
}
