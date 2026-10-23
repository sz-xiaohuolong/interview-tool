package cn.interview.mapper;

import cn.interview.common.ContentHash;
import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;
import static cn.interview.common.Models.*;

@Component
public class JobSearchStore {
    private final SqliteStore store;
    public JobSearchStore(SqliteStore store){this.store=store;}
    public List<ObjectNode> favorites(){return store.jdbc.query("SELECT id FROM job_search_favorites ORDER BY created_at DESC,id",(rs,n)->favorite(rs.getString(1)));}
    public ObjectNode favorite(String id){
        var rows=store.jdbc.query("SELECT id,created_at FROM job_search_favorites WHERE id=?",(rs,n)->store.json.createObjectNode().put("id",rs.getString(1)).put("createdAt",rs.getString(2)),id);
        if(rows.isEmpty())throw ApiException.missing("收藏不存在或已取消");ObjectNode result=rows.getFirst();var sources=result.putArray("sources");
        store.jdbc.query("SELECT snapshot_json,saved_at FROM job_search_favorite_sources WHERE favorite_id=? ORDER BY saved_at,source_key",(rs,n)->store.decode(rs.getString(1)).put("savedAt",rs.getString(2)),id).forEach(sources::add);return result;
    }
    public JobRecord favoriteJob(String id,String key){for(JsonNode item:favorite(id).path("sources"))if(key.equals(item.path("key").asText()))return parseJob(item);throw ApiException.missing("该来源岗位不在指定收藏中");}
    private JobRecord parseJob(JsonNode node){ObjectNode copy=((ObjectNode)node).deepCopy();copy.remove("savedAt");try{return store.json.treeToValue(copy,JobRecord.class);}catch(Exception e){throw new IllegalStateException("收藏快照无法读取",e);}}
    public ObjectNode saveFavorite(List<JobRecord> jobs,String favoriteId){return saveFavorite(jobs,favoriteId,Map.of());}
    public synchronized ObjectNode saveFavorite(List<JobRecord> jobs,String favoriteId,Map<String,JobRecord> currentEvidence){
        if(jobs.isEmpty())throw ApiException.bad("请至少选择一个岗位来源");
        return store.atomic(()->{
            var existing=new LinkedHashSet<>(store.jdbc.queryForList("SELECT favorite_id FROM job_search_favorite_sources WHERE source_key IN ("+String.join(",",Collections.nCopies(jobs.size(),"?"))+")",String.class,jobs.stream().map(JobRecord::key).toArray()));
            if(favoriteId!=null){favorite(favoriteId);existing.add(favoriteId);}
            if(existing.size()>1)throw ApiException.conflict("所选来源已属于不同收藏，请分别处理");
            String target=existing.isEmpty()?id():existing.iterator().next();var combined=new ArrayList<JobRecord>();var present=new HashSet<String>();
            if(!existing.isEmpty())for(JsonNode j:favorite(target).path("sources")){JobRecord saved=parseJob(j);combined.add(favoriteEvidence(saved,currentEvidence.get(saved.key())));present.add(saved.key());}
            for(JobRecord job:jobs)if(present.add(job.key()))combined.add(job);
            if(combined.stream().map(JobRecord::source).distinct().count()!=combined.size())throw ApiException.bad("这些来源缺少完整的同岗证据，请分别收藏");
            for(JobRecord j:combined)if(!sameJob(combined.getFirst(),j))throw ApiException.bad("这些来源缺少完整的同岗证据，请分别收藏");
            String time=now();if(existing.isEmpty())store.jdbc.update("INSERT INTO job_search_favorites(id,created_at) VALUES(?,?)",target,time);
            for(JobRecord j:jobs)store.jdbc.update("INSERT INTO job_search_favorite_sources(source_key,favorite_id,snapshot_json,saved_at) VALUES(?,?,?,?) ON CONFLICT(source_key) DO NOTHING",j.key(),target,store.encode(j),time);
            return favorite(target);
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
    public synchronized void deleteFavorite(String id){favorite(id);store.jdbc.update("DELETE FROM job_search_favorites WHERE id=?",id);}
    public ObjectNode lookup(List<String> keys){var result=store.json.createObjectNode();for(String key:keys){JsonNode link=store.jobSourceByKey(key);if(link==null)result.putNull(key);else result.set(key,link);}return result;}
    public synchronized ObjectNode saveJd(JobRecord job,String name,String content){
        return store.atomic(()->{
            JsonNode previous=store.jobSourceByKey(job.key());if(previous!=null)return store.json.createObjectNode().put("materialId",previous.path("materialId").asText()).put("created",false).set("source",previous);
            String materialId=id(),time=now();store.insertMaterial(materialId,"JD",name,content,time);
            var source=store.json.createObjectNode().put("materialId",materialId).put("source",job.source()).put("sourceId",job.sourceId()).put("url",job.url()).put("fetchedAt",job.detailFetchedAt()==null?job.fetchedAt():job.detailFetchedAt()).put("contentEdited",job.description()==null||!job.description().equals(content));
            store.jdbc.update("INSERT INTO job_search_jd_links(source_key,material_id,source_json,source_content_hash) VALUES(?,?,?,?)",job.key(),materialId,store.encode(source),job.description()==null?"":ContentHash.sha(job.description()));
            return store.json.createObjectNode().put("materialId",materialId).put("created",true).set("source",source);
        });
    }
}
