package cn.interview.service.jobsearch;

import cn.interview.client.jobsearch.JobSourceGateway;
import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import static cn.interview.common.Models.*;

@Service
public class JobSearchService {
    public record SourceProgress(String source,String status,int count,boolean hasMore,String errorCode,String errorMessage) {}
    public record TaskSnapshot(String id,String keyword,String cityId,String cityName,String status,long revision,List<SourceProgress> sources,List<JobRecord> jobs) {}
    private static final Set<String> SOURCES=Set.of("BOSS","LIEPIN","ZHAOPIN");
    private final JobSourceGateway gateway;
    private final JobCityCatalog cities;
    private final java.time.Clock clock;
    private final Map<String,Task> tasks=new LinkedHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(6,6,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24),Thread.ofPlatform().daemon().name("岗位搜索-",0).factory());
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("岗位回收-",0).factory());
    @org.springframework.beans.factory.annotation.Autowired
    public JobSearchService(JobSourceGateway gateway,JobCityCatalog cities){this(gateway,cities,java.time.Clock.systemUTC());}
    JobSearchService(JobSourceGateway gateway,JobCityCatalog cities,java.time.Clock clock){this.gateway=gateway;this.cities=cities;this.clock=clock;reaper.scheduleWithFixedDelay(this::expire,60,60,TimeUnit.SECONDS);}
    private static class Run {
        final String source;final LinkedHashMap<String,JobRecord> jobs=new LinkedHashMap<>();
        final ArrayDeque<JobRecord> buffer=new ArrayDeque<>();boolean sourceMore=true;
        String status="SEARCHING",errorCode,errorMessage;boolean more=true;long generation;JobSourceGateway.Cursor cursor;Future<?> future;boolean detailLoading;
        Run(String source){this.source=source;}
    }
    private static class Task {
        final String id=cn.interview.common.Models.id(),keyword,cityId,cityName;
        final LinkedHashMap<String,Run> runs=new LinkedHashMap<>();long revision;Instant active=Instant.now();boolean stopped;
        Task(String keyword,String cityId,String cityName){this.keyword=keyword;this.cityId=cityId;this.cityName=cityName;}
    }
    public synchronized TaskSnapshot create(JsonNode body){
        String keyword=required(body,"keyword",200),cityId=required(body,"cityId",80),cityName=cities.name(cityId);
        if(!body.path("sources").isArray()||body.path("sources").isEmpty())throw ApiException.bad("请至少选择一个招聘来源");
        var chosen=new LinkedHashSet<String>();for(var source:body.path("sources")){if(!source.isTextual()||!SOURCES.contains(source.asText()))throw ApiException.bad("不支持的招聘来源");chosen.add(source.asText());}
        // 本机单用户工作区只运行当前搜索；旧任务内容仍可读取，但迟到响应不得追加。
        tasks.values().forEach(this::stopTask);
        while(tasks.size()>=32){var first=tasks.keySet().iterator().next();tasks.remove(first);}
        Task t=new Task(keyword,cityId,cityName);t.active=clock.instant();chosen.forEach(s->t.runs.put(s,new Run(s)));tasks.put(t.id,t);
        t.runs.values().forEach(r->schedule(t,r));return snapshot(t);
    }
    public synchronized TaskSnapshot get(String id){return snapshot(task(id));}
    public synchronized TaskSnapshot more(String id){Task t=task(id);if(t.stopped)throw ApiException.conflict("搜索已停止，请重新搜索");t.runs.values().stream().filter(r->r.more&&!r.status.equals("SEARCHING")&&!r.status.equals("FAILED")&&r.jobs.size()<100).forEach(r->schedule(t,r));return snapshot(t);}
    public synchronized TaskSnapshot retry(String id,JsonNode body){Task t=task(id);if(t.stopped)throw ApiException.conflict("搜索已停止，请重新搜索");Run r=t.runs.get(required(body,"source",20));if(r==null)throw ApiException.bad("该搜索未选择此来源");if(!r.status.equals("FAILED"))throw ApiException.conflict("只能重试失败来源");schedule(t,r);return snapshot(t);}
    public synchronized TaskSnapshot stop(String id){Task t=task(id);stopTask(t);return snapshot(t);}
    public synchronized JobRecord job(String taskId,String key){Task t=task(taskId);return find(t,key);}
    private JobRecord find(Task t,String key){return t.runs.values().stream().map(r->r.jobs.get(key)).filter(Objects::nonNull).findFirst().orElseThrow(()->ApiException.missing("该岗位不在当前搜索结果中"));}
    public JobRecord detail(String id,String key){
        Task t;Run r;JobRecord job;long generation;JobSourceGateway.Cursor cursor;
        synchronized(this){t=task(id);job=find(t,key);if(job.description()!=null&&!job.description().isBlank())return job;if(t.stopped)throw ApiException.conflict("搜索已停止，请重新搜索后读取详情");r=t.runs.get(job.source());if(r.detailLoading||r.status.equals("SEARCHING"))throw ApiException.conflict("该来源正在读取，请稍后再打开详情");r.detailLoading=true;generation=r.generation;cursor=r.cursor;if(cursor==null){r.detailLoading=false;throw new ApiException(503,"ACCESS_UNAVAILABLE","来源连接不可用，请重试该来源");}}
        JobRecord detailed;try{detailed=cursor.detail(job);}catch(Exception e){throw safeFailure(e);}finally{synchronized(this){r.detailLoading=false;}}
        synchronized(this){if(t.stopped||generation!=r.generation)throw ApiException.conflict("搜索状态已变化，请重新打开详情");r.jobs.put(key,detailed);t.revision++;return detailed;}
    }
    private void schedule(Task t,Run r){r.status="SEARCHING";r.errorCode=null;r.errorMessage=null;long generation=++r.generation;t.revision++;
        try{r.future=workers.submit(()->{
            try {
                JobSourceGateway.Cursor cursor;
                synchronized(this){if(t.stopped||r.generation!=generation)return;cursor=r.cursor;}
                if(cursor==null){cursor=gateway.open(r.source,t.keyword,t.cityId);synchronized(this){if(t.stopped||r.generation!=generation){cursor.close();return;}r.cursor=cursor;}}
                JobSourceGateway.Batch batch=null;
                synchronized(this){if(t.stopped||generation!=r.generation)return;}
                if(r.buffer.isEmpty())batch=cursor.next();
                synchronized(this){
                    if(t.stopped||generation!=r.generation)return;
                    if(batch!=null){r.sourceMore=batch.hasMore();for(JobRecord job:batch.jobs()){
                        if(r.buffer.size()>=100)break;
                        if(job!=null&&r.source.equals(job.source())&&job.sourceId()!=null&&!job.sourceId().isBlank()&&job.key().equals(job.source()+":"+job.sourceId())&&!r.jobs.containsKey(job.key())&&r.buffer.stream().noneMatch(j->j.key().equals(job.key())))r.buffer.add(job);
                    }}
                    int added=0;while(!r.buffer.isEmpty()&&added<20&&r.jobs.size()<100){JobRecord job=r.buffer.removeFirst();if(r.jobs.putIfAbsent(job.key(),job)==null)added++;}
                    r.more=(!r.buffer.isEmpty()||r.sourceMore)&&r.jobs.size()<100;
                    if(r.jobs.size()>=100)r.buffer.clear();
                    r.status=r.jobs.size()>=100?"LIMIT":r.jobs.isEmpty()?"EMPTY":r.more?"READY":"ENDED";t.revision++;
                }
            } catch(Exception e){synchronized(this){if(t.stopped||generation!=r.generation)return;ApiException safe=safeFailure(e);r.status="FAILED";r.errorCode=safe.code;r.errorMessage=safe.getMessage();t.revision++;}}
        });}catch(RejectedExecutionException e){r.status="FAILED";r.errorCode="SOURCE_NETWORK";r.errorMessage="搜索资源暂时繁忙，请稍后重试";t.revision++;}
    }
    public static ApiException safeFailure(Exception e){
        String code=e instanceof ApiException a?a.code:"SOURCE_NETWORK";
        return switch(code){
            case "ACCESS_UNAVAILABLE"->new ApiException(503,code,"本机浏览器访问配置不可用，请由开发者检查");
            case "SESSION_EXPIRED"->new ApiException(503,code,"该来源登录态已失效，请由开发者恢复会话");
            case "VERIFICATION_REQUIRED"->new ApiException(503,code,"该来源需要完成安全验证，请由开发者处理");
            case "RATE_LIMITED"->new ApiException(429,code,"该来源暂时限制访问，请稍后重试");
            case "SOURCE_TIMEOUT"->new ApiException(504,code,"招聘来源读取超时，请稍后重试");
            case "SOURCE_CHANGED"->new ApiException(502,code,"招聘来源页面结构发生变化，请由开发者检查");
            default->new ApiException(502,"SOURCE_NETWORK","来源访问失败，请稍后重试");
        };
    }
    private Task task(String id){Task t=tasks.get(id);if(t==null)throw ApiException.missing("搜索任务已过期或不存在，请重新搜索");t.active=clock.instant();return t;}
    private TaskSnapshot snapshot(Task t){
        var progress=t.runs.values().stream().map(r->new SourceProgress(r.source,r.status,r.jobs.size(),r.more,r.errorCode,r.errorMessage)).toList();
        var lists=t.runs.values().stream().map(r->new ArrayList<>(r.jobs.values())).toList();var jobs=new ArrayList<JobRecord>();for(int i=0;i<100;i++)for(var list:lists)if(i<list.size())jobs.add(list.get(i));
        return new TaskSnapshot(t.id,t.keyword,t.cityId,t.cityName,t.stopped?"STOPPED":progress.stream().anyMatch(p->p.status.equals("SEARCHING"))?"RUNNING":"IDLE",t.revision,progress,List.copyOf(jobs));
    }
    private void stopTask(Task t){if(t.stopped)return;t.stopped=true;for(Run r:t.runs.values()){r.generation++;if(r.status.equals("SEARCHING")||r.more)r.status="STOPPED";r.more=false;r.buffer.clear();release(r);}t.revision++;}
    private void release(Run r){if(r.future!=null)r.future.cancel(true);workers.purge();if(r.cursor!=null){r.cursor.close();r.cursor=null;}}
    synchronized void expire(){Instant before=clock.instant().minusSeconds(900);tasks.values().removeIf(t->{if(t.active.isBefore(before)){stopTask(t);return true;}return false;});}
    @PreDestroy public synchronized void close(){tasks.values().forEach(this::stopTask);tasks.clear();workers.shutdownNow();reaper.shutdownNow();}
}
