package cn.interview.service.jobsearch;

import cn.interview.entity.Material;
import cn.interview.client.jobsearch.JobSourceGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.mapper.JobSearchStore;
import cn.interview.mapper.SqliteStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 合成岗位＋真实 SQLite，覆盖快照和资料的独立生命周期。 */
class JobSearchLibraryTest {
    @TempDir Path data;
    ObjectMapper json=new ObjectMapper();
    final List<Library> libraries=new ArrayList<>();
    @org.junit.jupiter.api.AfterEach void close(){libraries.forEach(l->l.search.close());}
    Library library(SqliteStore sqlite){var library=new Library(sqlite);libraries.add(library);return library;}
    final class Library {
        volatile List<JobRecord> records=List.of();
        final JobSearchStore database;
        final JobSearchService search;
        final JobSearchLibraryService service;
        Library(SqliteStore sqlite){
            database=new JobSearchStore(sqlite);
            search=new JobSearchService((source,keyword,city)->{
                var jobs=records.stream().filter(j->j.source().equals(source)).toList();
                return new JobSourceGateway.Cursor(){public JobSourceGateway.Batch next(){return new JobSourceGateway.Batch(jobs,false);}public JobRecord detail(JobRecord j){return j;}public void close(){}};
            },new JobCityCatalog());
            service=new JobSearchLibraryService(search,database);
        }
        synchronized String task(List<JobRecord> jobs) throws Exception {
            records=List.copyOf(jobs);var input=json.createObjectNode().put("keyword","合成岗位").put("cityId","shenzhen");
            input.set("sources",json.valueToTree(jobs.stream().map(JobRecord::source).distinct().toList()));
            String id=search.create(input).id();
            for(int i=0;i<200;i++){if(!search.get(id).status().equals("RUNNING"))return id;Thread.sleep(10);}
            throw new AssertionError("任务未完成");
        }
        ObjectNode saveFavorite(List<JobRecord> jobs,String favoriteId) throws Exception {
            var input=json.createObjectNode().put("taskId",task(jobs));input.set("jobKeys",json.valueToTree(jobs.stream().map(JobRecord::key).toList()));
            if(favoriteId!=null)input.put("favoriteId",favoriteId);return service.favorite(input);
        }
        ObjectNode saveJd(JobRecord job,String name,String content) throws Exception {
            return service.saveJd(json.createObjectNode().put("taskId",task(List.of(job))).put("jobKey",job.key()).put("name",name).put("content",content));
        }
        List<ObjectNode> favorites(){return service.favorites();}
        ObjectNode favorite(String id){return database.favorite(id);}
        void deleteFavorite(String id){service.delete(id);}
        ObjectNode lookup(List<String> keys){return service.lookup(json.createObjectNode().set("keys",json.valueToTree(keys)));}
    }
    JobRecord job(String source,String body){String id="synthetic";return new JobRecord(source+":"+id,source,id,"Java 工程师","合成公司","深圳","深圳南山",source.equals("BOSS")?"https://www.zhipin.com/job_detail/synthetic.html":"https://www.liepin.com/job/synthetic.shtml","20-30K",20d,30d,13,"3年","本科",body,"2026-10-24T00:00:00Z",body==null?null:"2026-10-24T00:00:01Z",null);}
    JobRecord withCity(JobRecord job,String city){return new JobRecord(job.key(),job.source(),job.sourceId(),job.title(),job.company(),city,job.location(),job.url(),job.salaryText(),job.salaryMinK(),job.salaryMaxK(),job.salaryMonths(),job.experienceText(),job.educationText(),job.description(),job.fetchedAt(),job.detailFetchedAt(),job.publishedAt());}
    @Test void favoriteIsImmutableAcrossNewDetailAndRestartAndCancellationDoesNotDeleteJd() throws Exception {
        var sqlite=new SqliteStore(data.toString(),json);var store=library(sqlite);
        var original=job("BOSS",null);var favorite=store.saveFavorite(List.of(original),null);String id=favorite.path("id").asText();
        var repeated=store.saveFavorite(List.of(job("BOSS","新正文")),null);assertThat(repeated.path("id").asText()).isEqualTo(id);assertThat(repeated.path("sources").get(0).path("description").isNull()).isTrue();assertThat(store.favorites()).hasSize(1);
        var jd=store.saveJd(original,"合成 JD","人工补充正文");assertThat(jd.path("source").path("contentEdited").asBoolean()).isTrue();
        var restarted=library(new SqliteStore(data.toString(),json));assertThat(restarted.favorite(id)).isEqualTo(favorite);restarted.deleteFavorite(id);assertThat(restarted.favorites()).isEmpty();assertThat(sqlite.material(jd.path("materialId").asText()).content()).isEqualTo("人工补充正文");
    }
    @Test void repeatedGroupRetainsOriginalSnapshotEvenWhenEveryNewRecordChanged() throws Exception {
        var store=library(new SqliteStore(data.toString(),json));var original=store.saveFavorite(List.of(job("BOSS","完整原文"),job("LIEPIN","完整原文")),null);
        var repeated=store.saveFavorite(List.of(job("BOSS","后来的不同原文"),job("LIEPIN","后来的不同原文")),null);
        assertThat(repeated).isEqualTo(original);
        assertThatThrownBy(()->store.saveFavorite(List.of(job("BOSS",null),job("LIEPIN",null)),"nonexistent")).hasMessageContaining("收藏不存在");
    }
    @Test void insufficientCrossSourceEvidenceDoesNotLeaveHalfFavorite() throws Exception {
        var store=library(new SqliteStore(data.toString(),json));assertThatThrownBy(()->store.saveFavorite(List.of(job("BOSS",null),job("LIEPIN",null)),null)).hasMessageContaining("同岗证据");assertThat(store.favorites()).isEmpty();
    }
    @Test void distinctIdentitiesWithinSameSourceCannotBeMergedAsCrossSourceFavorite() throws Exception {
        var store=library(new SqliteStore(data.toString(),json));var first=job("BOSS","合成全文");var another=new JobRecord("BOSS:another","BOSS","another",first.title(),first.company(),first.city(),first.location(),"https://www.zhipin.com/job_detail/another.html",first.salaryText(),first.salaryMinK(),first.salaryMaxK(),first.salaryMonths(),first.experienceText(),first.educationText(),first.description(),first.fetchedAt(),first.detailFetchedAt(),null);
        assertThatThrownBy(()->store.saveFavorite(List.of(first,another),null)).hasMessageContaining("同岗证据");assertThat(store.favorites()).isEmpty();
        assertThatThrownBy(()->store.saveFavorite(List.of(job("LIEPIN","合成全文"),first,another),null)).hasMessageContaining("同岗证据");assertThat(store.favorites()).isEmpty();
    }
    @Test void differentCitiesCannotMergeEvenWithIdenticalFullEvidenceAndLeaveNoRows() throws Exception {
        var store=library(new SqliteStore(data.toString(),json));
        assertThatThrownBy(()->store.saveFavorite(List.of(job("BOSS","完整合成正文"),withCity(job("LIEPIN","完整合成正文"),"北京")),null)).hasMessageContaining("同岗证据");
        assertThat(store.favorites()).isEmpty();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("interview.db"));var statement=connection.createStatement();var result=statement.executeQuery("SELECT (SELECT COUNT(*) FROM job_search_favorites)+(SELECT COUNT(*) FROM job_search_favorite_sources)")){
            assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
        }
    }
    @Test void emptyCitiesStillAllowIdenticalCompleteLocationEvidence() throws Exception {
        var store=library(new SqliteStore(data.toString(),json));
        var favorite=store.saveFavorite(List.of(withCity(job("BOSS","完整合成正文"),null),withCity(job("LIEPIN","完整合成正文")," ")),null);
        assertThat(favorite.path("sources")).hasSize(2);assertThat(store.favorites()).hasSize(1);
    }
    @Test void sourceWriteFailureRollsBackFavoriteAndPreviouslyInsertedSource() throws Exception {
        var sqlite=new SqliteStore(data.toString(),json);var library=library(sqlite);
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("interview.db"));var statement=connection.createStatement()){
            statement.execute("CREATE TRIGGER synthetic_failure BEFORE INSERT ON job_search_favorite_sources WHEN NEW.source_key LIKE 'LIEPIN:%' BEGIN SELECT RAISE(ABORT, '合成写入失败'); END");
            assertThatThrownBy(()->library.saveFavorite(List.of(job("BOSS","完整正文"),job("LIEPIN","完整正文")),null)).hasMessageContaining("合成写入失败");
            try(var result=statement.executeQuery("SELECT (SELECT COUNT(*) FROM job_search_favorites)+(SELECT COUNT(*) FROM job_search_favorite_sources)")){
                assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
            }
        }
    }
    @Test void concurrentSourceSaveIsIdempotentAndDoesNotOverwriteEditedMaterialAndDeletionAllowsRecreate() throws Exception {
        var sqlite=new SqliteStore(data.toString(),json);var store=library(sqlite);var source=job("BOSS","原站全文");
        var pool=Executors.newFixedThreadPool(8);var calls=new ArrayList<Future<com.fasterxml.jackson.databind.node.ObjectNode>>();for(int i=0;i<16;i++)calls.add(pool.submit(()->store.saveJd(source,"合成 JD","原站全文")));
        var results=new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();for(var call:calls)results.add(call.get(5,TimeUnit.SECONDS));pool.shutdownNow();assertThat(results.stream().filter(r->r.path("created").asBoolean()).count()).isEqualTo(1);assertThat(sqlite.materials("JD")).hasSize(1);
        String id=results.getFirst().path("materialId").asText();assertThat(sqlite.material(id).jobSource().path("contentEdited").asBoolean()).isFalse();
        sqlite.updateMaterial(id,"用户修改名称","用户后改正文","2026-10-24T01:00:00Z");assertThat(sqlite.material(id).jobSource().path("contentEdited").asBoolean()).isTrue();
        var retried=store.saveJd(source,"请求重试","不要覆盖");assertThat(retried.path("created").asBoolean()).isFalse();assertThat(sqlite.material(id).content()).isEqualTo("用户后改正文");assertThat(retried.path("source").path("contentEdited").asBoolean()).isTrue();
        var other=store.saveJd(job("LIEPIN","另一来源正文"),"另一来源 JD","另一来源正文");assertThat(other.path("materialId").asText()).isNotEqualTo(id);
        sqlite.deleteMaterial(id);assertThat(store.lookup(List.of(source.key())).path(source.key()).isNull()).isTrue();var recreated=store.saveJd(source,"重新保存","原站全文");assertThat(recreated.path("created").asBoolean()).isTrue();assertThat(recreated.path("materialId").asText()).isNotEqualTo(id);
        assertThat(new Material("id","JD","旧构造","正文","t","t",List.of()).jobSource()).isNull();
    }
}
