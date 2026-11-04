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
    /** 锁和事务覆盖调用方的读取、校验与写入。 */
    public synchronized <T> T atomic(java.util.function.Supplier<T> action){return store.atomic(action);}
    public Set<String> favoriteIds(List<String> keys){
        return new LinkedHashSet<>(store.jdbc.queryForList("SELECT favorite_id FROM job_search_favorite_sources WHERE source_key IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")",String.class,keys.toArray()));
    }
    public List<JobRecord> favoriteJobs(String id){var jobs=new ArrayList<JobRecord>();favorite(id).path("sources").forEach(node->jobs.add(parseJob(node)));return jobs;}
    public void insertFavorite(String id,String time){store.jdbc.update("INSERT INTO job_search_favorites(id,created_at) VALUES(?,?)",id,time);}
    public void insertFavoriteSources(String id,List<JobRecord> jobs,String time){
        for(JobRecord job:jobs)store.jdbc.update("INSERT INTO job_search_favorite_sources(source_key,favorite_id,snapshot_json,saved_at) VALUES(?,?,?,?) ON CONFLICT(source_key) DO NOTHING",job.key(),id,store.encode(job),time);
    }
    public synchronized void deleteFavorite(String id){favorite(id);store.jdbc.update("DELETE FROM job_search_favorites WHERE id=?",id);}
    public ObjectNode lookup(List<String> keys){var result=store.json.createObjectNode();for(String key:keys){JsonNode link=store.jobSourceByKey(key);if(link==null)result.putNull(key);else result.set(key,link);}return result;}
    public JsonNode jdLink(String key){return store.jobSourceByKey(key);}
    public ObjectNode insertJd(JobRecord job,String name,String content,boolean contentEdited){
        String materialId=id(),time=now();store.insertMaterial(materialId,"JD",name,content,time);
        var source=store.json.createObjectNode().put("materialId",materialId).put("source",job.source()).put("sourceId",job.sourceId()).put("url",job.url()).put("fetchedAt",job.detailFetchedAt()==null?job.fetchedAt():job.detailFetchedAt()).put("contentEdited",contentEdited);
        store.jdbc.update("INSERT INTO job_search_jd_links(source_key,material_id,source_json,source_content_hash) VALUES(?,?,?,?)",job.key(),materialId,store.encode(source),job.description()==null?"":ContentHash.sha(job.description()));
        return source;
    }
}
