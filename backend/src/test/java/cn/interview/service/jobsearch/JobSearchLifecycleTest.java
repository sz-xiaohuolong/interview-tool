package cn.interview.service.jobsearch;

import cn.interview.client.jobsearch.JobSourceGateway;
import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** 来源替身只用于竞态和批次边界，不作为三站质量证据。 */
class JobSearchLifecycleTest {
    final ObjectMapper json=new ObjectMapper();
    JobRecord job(String source,int id){return new JobRecord(source+":"+id,source,""+id,"合成岗位","合成公司","深圳","深圳南山",switch(source){case "BOSS"->"https://www.zhipin.com/job_detail/"+id+".html";case "LIEPIN"->"https://www.liepin.com/job/"+id+".shtml";default->"https://www.zhaopin.com/jobdetail/"+id+".htm";},"20-30K",20d,30d,null,"3年","本科",null,"2026-10-24T00:00:00Z",null,null);}
    com.fasterxml.jackson.databind.JsonNode input(String... sources){var n=json.createObjectNode().put("keyword","Java 后端").put("cityId","shenzhen");n.set("sources",json.valueToTree(sources));return n;}
    JobSearchService.TaskSnapshot idle(JobSearchService s,String id) throws Exception {for(int i=0;i<200;i++){var t=s.get(id);if(!t.status().equals("RUNNING"))return t;Thread.sleep(10);}throw new AssertionError("任务未完成");}
    @Test void oversizedPlatformPageIsBufferedRatherThanLostAndSourceStopsAt100() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        JobSourceGateway g=(source,k,c)->new JobSourceGateway.Cursor(){int page;public JobSourceGateway.Batch next(){calls.incrementAndGet();var jobs=new ArrayList<JobRecord>();for(int i=0;i<35;i++)jobs.add(job(source,page*35+i));page++;return new JobSourceGateway.Batch(jobs,true);}public JobRecord detail(JobRecord j){return j;}public void close(){}};
        try(var s=new ClosingService(g)){
            var t=idle(s.service,s.service.create(input("BOSS")).id());assertThat(t.jobs()).hasSize(20);assertThat(calls).hasValue(1);
            t=idle(s.service,s.service.more(t.id()).id());assertThat(t.jobs()).hasSize(35);assertThat(calls).hasValue(1);
            while(t.jobs().size()<100)t=idle(s.service,s.service.more(t.id()).id());
            assertThat(t.jobs()).hasSize(100);assertThat(t.sources().getFirst().status()).isEqualTo("LIMIT");assertThat(calls).hasValue(3);
            s.service.more(t.id());assertThat(calls).hasValue(3);
        }
    }
    @Test void lateReplyAfterStopAndNewSearchCannotWriteAndDoesNotBlockStop() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger closed=new AtomicInteger();
        JobSourceGateway g=(source,k,c)->new JobSourceGateway.Cursor(){public JobSourceGateway.Batch next(){entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}return new JobSourceGateway.Batch(List.of(job(source,1)),true);}public JobRecord detail(JobRecord j){return j;}public void close(){closed.incrementAndGet();}};
        try(var s=new ClosingService(g)){
            var first=s.service.create(input("BOSS"));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            long start=System.nanoTime();s.service.stop(first.id());assertThat(System.nanoTime()-start).isLessThan(TimeUnit.SECONDS.toNanos(1));
            release.countDown();Thread.sleep(50);assertThat(s.service.get(first.id()).jobs()).isEmpty();assertThat(closed).hasValue(1);
            assertThatThrownBy(()->s.service.more(first.id())).isInstanceOf(ApiException.class);
            var second=idle(s.service,s.service.create(input("BOSS")).id());assertThat(second.jobs()).hasSize(1);assertThat(s.service.get(first.id()).status()).isEqualTo("STOPPED");
        }finally{release.countDown();}
    }
    @Test void independentFailureRetryDoesNotDuplicateSuccessfulSourceOrLeakProtocol() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        JobSourceGateway g=(source,k,c)->new JobSourceGateway.Cursor(){public JobSourceGateway.Batch next(){if(source.equals("LIEPIN")&&attempts.getAndIncrement()==0)throw new ApiException(503,"SESSION_EXPIRED","secret-cookie ws://127.0.0.1/token");return new JobSourceGateway.Batch(List.of(job(source,7)),false);}public JobRecord detail(JobRecord j){return j;}public void close(){}};
        try(var s=new ClosingService(g)){
            var t=idle(s.service,s.service.create(input("BOSS","LIEPIN")).id());assertThat(t.jobs()).hasSize(1);assertThat(t.sources().get(1).status()).isEqualTo("FAILED");assertThat(t.sources().get(1).errorMessage()).doesNotContain("secret","ws://");
            t=idle(s.service,s.service.retry(t.id(),json.createObjectNode().put("source","LIEPIN")).id());assertThat(t.jobs()).extracting(JobRecord::key).containsExactly("BOSS:7","LIEPIN:7");
        }
    }
    @Test void inactivityExpiryReleasesOwnedResourcesAndRejectsOldTask() throws Exception {
        AtomicInteger closed=new AtomicInteger();var clock=new java.time.Clock(){java.time.Instant value=java.time.Instant.parse("2026-10-24T00:00:00Z");public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return value;}};
        JobSourceGateway g=(source,k,c)->new JobSourceGateway.Cursor(){public JobSourceGateway.Batch next(){return new JobSourceGateway.Batch(List.of(job(source,1)),true);}public JobRecord detail(JobRecord j){return j;}public void close(){closed.incrementAndGet();}};
        try(var s=new ClosingService(new JobSearchService(g,new JobCityCatalog(),clock))){String id=idle(s.service,s.service.create(input("BOSS")).id()).id();clock.value=clock.value.plusSeconds(901);s.service.expire();assertThat(closed).hasValue(1);assertThatThrownBy(()->s.service.get(id)).hasMessageContaining("已过期或不存在");}
    }
    @Test void delayedDetailCannotUpdateAfterStop() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        JobSourceGateway g=(source,k,c)->new JobSourceGateway.Cursor(){public JobSourceGateway.Batch next(){return new JobSourceGateway.Batch(List.of(job(source,1)),false);}public JobRecord detail(JobRecord j){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return j.withDescription("迟到正文","later");}public void close(){}};
        try(var s=new ClosingService(g)){String id=idle(s.service,s.service.create(input("BOSS")).id()).id();var pool=Executors.newSingleThreadExecutor();var future=pool.submit(()->s.service.detail(id,"BOSS:1"));assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();s.service.stop(id);release.countDown();assertThatThrownBy(()->future.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);assertThat(s.service.get(id).jobs().getFirst().description()).isNull();pool.shutdownNow();}finally{release.countDown();}
    }
    record ClosingService(JobSearchService service) implements AutoCloseable {ClosingService(JobSourceGateway g){this(new JobSearchService(g,new JobCityCatalog()));}public void close(){service.close();}}
}
