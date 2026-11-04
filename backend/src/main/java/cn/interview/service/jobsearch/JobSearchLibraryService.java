package cn.interview.service.jobsearch;

import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.exception.ApiException;
import cn.interview.mapper.JobSearchStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;
import static cn.interview.common.Models.*;

@Service
public class JobSearchLibraryService {
    private final JobSearchService search;private final JobSearchStore store;
    public JobSearchLibraryService(JobSearchService search,JobSearchStore store){this.search=search;this.store=store;}
    public List<ObjectNode> favorites(){return store.favorites();}
    public ObjectNode favorite(JsonNode body){
        String taskId=required(body,"taskId",100);var keys=keys(body,"jobKeys",100);String favoriteId=optional(body,"favoriteId",null,100);
        if(keys.isEmpty())throw ApiException.bad("请至少选择一个岗位来源");
        var evidence=new LinkedHashMap<String,JobRecord>();search.get(taskId).jobs().forEach(job->evidence.put(job.key(),job));
        var jobs=keys.stream().map(key->{JobRecord job=evidence.get(key);if(job==null)throw ApiException.missing("该岗位不在当前搜索结果中");return job;}).toList();
        return store.atomic(()->{
            Set<String> existing=store.favoriteIds(jobs.stream().map(JobRecord::key).toList());
            if(favoriteId!=null){store.favorite(favoriteId);existing.add(favoriteId);}
            if(existing.size()>1)throw ApiException.conflict("所选来源已属于不同收藏，请分别处理");
            String target=existing.isEmpty()?id():existing.iterator().next();
            var combined=new ArrayList<JobRecord>();var present=new HashSet<String>();
            if(!existing.isEmpty())for(JobRecord saved:store.favoriteJobs(target)){
                combined.add(favoriteEvidence(saved,evidence.get(saved.key())));present.add(saved.key());
            }
            for(JobRecord job:jobs)if(present.add(job.key()))combined.add(job);
            if(combined.stream().map(JobRecord::source).distinct().count()!=combined.size())throw ApiException.bad("这些来源缺少完整的同岗证据，请分别收藏");
            for(JobRecord job:combined)if(!sameJob(combined.getFirst(),job))throw ApiException.bad("这些来源缺少完整的同岗证据，请分别收藏");
            String time=now();if(existing.isEmpty())store.insertFavorite(target,time);
            store.insertFavoriteSources(target,jobs,time);return store.favorite(target);
        });
    }
    public void delete(String id){store.deleteFavorite(id);}
    public ObjectNode lookup(JsonNode body){return store.lookup(keys(body,"keys",1000));}
    public ObjectNode saveJd(JsonNode body){
        String key=required(body,"jobKey",500),name=required(body,"name",120);
        if(!body.path("content").isTextual()||body.path("content").asText().isBlank())throw ApiException.bad("JD 正文不能为空，请补充内容");String content=body.path("content").asText();if(content.length()>24000)throw ApiException.bad("JD 正文不能超过 24000 个字符");
        String taskId=optional(body,"taskId",null,100),favoriteId=optional(body,"favoriteId",null,100);
        if((taskId==null)==(favoriteId==null))throw ApiException.bad("请指定搜索任务或收藏中的一个来源");
        JobRecord job=taskId!=null?search.job(taskId,key):store.favoriteJob(favoriteId,key);
        return store.atomic(()->{
            JsonNode previous=store.jdLink(job.key());boolean created=previous==null;
            JsonNode source=created?store.insertJd(job,name,content,job.description()==null||!job.description().equals(content)):previous;
            return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("materialId",source.path("materialId").asText()).put("created",created).set("source",source);
        });
    }
    private static JobRecord favoriteEvidence(JobRecord saved,JobRecord current){
        if(!normalized(saved.description()).isEmpty()||current==null||normalized(current.description()).isEmpty())return saved;
        if(!saved.key().equals(current.key())||!saved.source().equals(current.source())||!saved.sourceId().equals(current.sourceId()))return saved;
        if(!normalized(saved.title()).equals(normalized(current.title()))||!normalized(saved.company()).equals(normalized(current.company()))||!normalized(saved.city()).equals(normalized(current.city()))||!normalized(saved.location()).equals(normalized(current.location())))return saved;
        // 仅借用当前任务正文验证同岗；插入与返回仍保留旧收藏原始快照。
        return saved.withDescription(current.description(),current.detailFetchedAt());
    }
    static String normalized(String value){return value==null?"":value.strip().replaceAll("\\s+"," ");}
    static boolean sameJob(JobRecord a,JobRecord b){if(a.key().equals(b.key()))return true;if(a.source().equals(b.source()))return false;return equalKnown(a.title(),b.title())&&equalKnown(a.company(),b.company())&&normalized(a.city()).equals(normalized(b.city()))&&equalKnown(a.location(),b.location())&&equalKnown(a.description(),b.description());}
    private static boolean equalKnown(String a,String b){return !normalized(a).isEmpty()&&normalized(a).equals(normalized(b));}
    private List<String> keys(JsonNode body,String field,int max){JsonNode values=body.path(field);if(!values.isArray()||values.size()>max)throw ApiException.bad("岗位来源列表格式或数量无效");var keys=new LinkedHashSet<String>();for(JsonNode n:values){if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>500)throw ApiException.bad("岗位来源身份无效");keys.add(n.asText());}return List.copyOf(keys);}
}
