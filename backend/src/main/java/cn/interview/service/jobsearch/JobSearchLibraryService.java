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
        return store.saveFavorite(jobs,favoriteId,evidence);
    }
    public void delete(String id){store.deleteFavorite(id);}
    public ObjectNode lookup(JsonNode body){return store.lookup(keys(body,"keys",1000));}
    public ObjectNode saveJd(JsonNode body){
        String key=required(body,"jobKey",500),name=required(body,"name",120);
        if(!body.path("content").isTextual()||body.path("content").asText().isBlank())throw ApiException.bad("JD 正文不能为空，请补充内容");String content=body.path("content").asText();if(content.length()>24000)throw ApiException.bad("JD 正文不能超过 24000 个字符");
        String taskId=optional(body,"taskId",null,100),favoriteId=optional(body,"favoriteId",null,100);
        if((taskId==null)==(favoriteId==null))throw ApiException.bad("请指定搜索任务或收藏中的一个来源");
        JobRecord job=taskId!=null?search.job(taskId,key):store.favoriteJob(favoriteId,key);return store.saveJd(job,name,content);
    }
    private List<String> keys(JsonNode body,String field,int max){JsonNode values=body.path(field);if(!values.isArray()||values.size()>max)throw ApiException.bad("岗位来源列表格式或数量无效");var keys=new LinkedHashSet<String>();for(JsonNode n:values){if(!n.isTextual()||n.asText().isBlank()||n.asText().length()>500)throw ApiException.bad("岗位来源身份无效");keys.add(n.asText());}return List.copyOf(keys);}
}
