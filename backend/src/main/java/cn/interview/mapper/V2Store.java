package cn.interview.mapper;

import cn.interview.common.ContentHash;
import cn.interview.config.V2BootstrapConfiguration;
import cn.interview.exception.V2Exception;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import static cn.interview.common.Models.*;

/** 仅服务 V2 有限业务对象，所有 SQL 表名来自固定白名单。 */
@Component
public class V2Store {
    final Store legacy;
    final Environment env;
    private final Map<String, List<String>> columns = new java.util.concurrent.ConcurrentHashMap<>();
    static final Set<String> TABLES = Set.of("v2_operations", "v2_review_items", "v2_skill_events", "v2_practice_cases",
            "v2_practice_attempts", "v2_practice_evaluation_groups", "v2_practice_comparisons", "v2_jd_diagnostics",
            "v2_sprints", "v2_stories", "v2_story_versions", "v2_design_sessions", "v2_training_links");

    public V2Store(Store legacy, Environment env) { this.legacy=legacy; this.env=env; }
    public void enabled() { if (!V2BootstrapConfiguration.enabled(env)) throw V2Exception.disabled(); }
    public synchronized <T> T atomic(Supplier<T> action) { enabled(); return legacy.atomic(action); }
    public String encode(JsonNode body) {
        String value=legacy.encode(body);
        if(value.getBytes(StandardCharsets.UTF_8).length>2*1024*1024)
            throw new V2Exception(413,"V2_RECORD_TOO_LARGE","本条记录达到 2 MiB 上限，请另建记录；历史未删除。","EDIT_INPUT");
        return value;
    }
    public ObjectNode object() { return legacy.json.createObjectNode(); }
    public ArrayNode array() { return legacy.json.createArrayNode(); }
    public String hash(JsonNode input) { return ContentHash.sha(canonical(input).toString()); }
    JsonNode canonical(JsonNode input) {
        if(input.isObject()) { var out=object(); var keys=new TreeSet<String>();input.fieldNames().forEachRemaining(keys::add);keys.forEach(k->out.set(k,canonical(input.get(k))));return out; }
        if(input.isArray()) { var out=array();input.forEach(v->out.add(canonical(v)));return out; }
        return input;
    }
    private List<String> columns(String table) {
        enabled(); if(!TABLES.contains(table)) throw new IllegalArgumentException("不支持的 V2 表");
        return columns.computeIfAbsent(table,t->legacy.jdbc.query("PRAGMA table_info("+t+")",(rs,n)->rs.getString("name")));
    }
    static String camel(String column) {
        StringBuilder out=new StringBuilder();boolean upper=false;
        for(char c:column.toCharArray()) { if(c=='_')upper=true;else {out.append(upper?Character.toUpperCase(c):c);upper=false;} }
        return out.toString();
    }
    public ObjectNode get(String table, String id) {
        columns(table);
        var found=legacy.jdbc.query("SELECT * FROM "+table+" WHERE id=?",(rs,n)->{
            ObjectNode row=legacy.decode(rs.getString("payload"));
            for(String column:columns(table)) if(!column.equals("payload")) {
                Object value=rs.getObject(column);String field=camel(column);
                if(value==null)row.putNull(field);else row.set(field,legacy.json.valueToTree(value));
            }
            return row;
        },id);
        if(found.isEmpty()) throw new V2Exception(404,"V2_NOT_FOUND","记录不存在或来源已删除，请返回列表。","LOAD_LATEST");
        return found.getFirst();
    }
    public ObjectNode insert(String table,ObjectNode row) {
        row.put("schemaVersion",1);if(!row.has("id"))row.put("id",id());if(!row.has("createdAt"))row.put("createdAt",now());
        if(!row.has("updatedAt"))row.put("updatedAt",row.path("createdAt").asText());if(!row.has("revision"))row.put("revision",0);
        var cols=columns(table);var args=new ArrayList<Object>();
        for(String col:cols)args.add(col.equals("payload")?encode(row):sqlValue(row.get(camel(col))));
        legacy.jdbc.update("INSERT INTO "+table+"("+String.join(",",cols)+") VALUES("+String.join(",",Collections.nCopies(cols.size(),"?"))+")",args.toArray());
        return row;
    }
    public ObjectNode save(String table,ObjectNode row,int expected) {
        var cols=columns(table);if(!cols.contains("revision"))throw new IllegalArgumentException("该记录只允许追加");
        row.put("revision",expected+1).put("updatedAt",now());var updates=new ArrayList<String>();var args=new ArrayList<Object>();
        for(String col:cols) if(!Set.of("id","created_at").contains(col)) {
            updates.add(col+"=?");args.add(col.equals("payload")?encode(row):sqlValue(row.get(camel(col))));
        }
        args.add(row.path("id").asText());args.add(expected);
        if(legacy.jdbc.update("UPDATE "+table+" SET "+String.join(",",updates)+" WHERE id=? AND revision=?",args.toArray())!=1)
            throw V2Exception.conflict("V2_VERSION_CONFLICT","内容已在另一页面修改，请保留本页文本并加载最新版本。");
        return row;
    }
    static Object sqlValue(JsonNode value) {
        if(value==null||value.isNull())return null;
        if(value.isIntegralNumber())return value.intValue();
        return value.asText();
    }
    public void expect(ObjectNode row,JsonNode request) {
        if(!request.path("expectedVersion").isIntegralNumber())throw V2Exception.invalid("请提供 expectedVersion");
        if(request.path("expectedVersion").asInt()!=row.path("revision").asInt())
            throw V2Exception.conflict("V2_VERSION_CONFLICT","内容已更新；本页文本仍保留，请先查看服务端版本。")
                    .detail("currentRevision",row.path("revision").asInt());
    }
    public ObjectNode page(String table,String cursor,int limit,String status) {
        return page(table,cursor,limit,status,null,null);
    }
    public ObjectNode page(String table,String cursor,int limit,String status,String filterColumn,String filterValue) {
        columns(table);limit=Math.max(1,Math.min(100,limit));var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(status!=null&&!status.isBlank()) {where+=" AND "+(table.equals("v2_operations")?"state":"status")+"=?";args.add(status);}
        if(filterColumn!=null) {if(!columns(table).contains(filterColumn))throw new IllegalArgumentException("无效筛选字段");where+=" AND "+filterColumn+"=?";args.add(filterValue);}
        if(cursor!=null&&!cursor.isBlank()) {
            try {String[] key=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(key.length!=2)throw new IllegalArgumentException();where+=" AND (created_at<? OR (created_at=? AND id<?))";args.add(key[0]);args.add(key[0]);args.add(key[1]);
            } catch(IllegalArgumentException e) {throw V2Exception.invalid("历史游标无效，请返回第一页");}
        }
        args.add(limit+1);var ids=legacy.jdbc.query("SELECT id FROM "+table+where+" ORDER BY created_at DESC,id DESC LIMIT ?",(rs,n)->rs.getString(1),args.toArray());
        var out=object();var items=out.putArray("items");for(String key:ids.subList(0,Math.min(limit,ids.size())))items.add(get(table,key));
        if(ids.size()>limit) {var last=items.get(items.size()-1);out.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString((last.path("createdAt").asText()+"|"+last.path("id").asText()).getBytes(StandardCharsets.UTF_8)));}
        else out.putNull("nextCursor");return out;
    }
    public ObjectNode reviewPage(String cursor,int limit,String status) {
        enabled();limit=Math.max(1,Math.min(100,limit));var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(status!=null&&!status.isBlank()){where+=" AND status=?";args.add(status);}
        String due="COALESCE(next_review_at,'9999')";
        if(cursor!=null&&!cursor.isBlank())try {
            String[] key=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(key.length!=3)throw new IllegalArgumentException();
            where+=" AND ("+due+",created_at,id)>(?,?,?)";Collections.addAll(args,key[0],key[1],key[2]);
        }catch(IllegalArgumentException e){throw V2Exception.invalid("复习游标无效，请返回第一页");}
        args.add(limit+1);var ids=legacy.jdbc.query("SELECT id FROM v2_review_items"+where+" ORDER BY "+due+",created_at,id LIMIT ?",(rs,n)->rs.getString(1),args.toArray());
        var out=object();var items=out.putArray("items");for(String id:ids.subList(0,Math.min(limit,ids.size())))items.add(get("v2_review_items",id));
        out.putNull("nextCursor");if(ids.size()>limit){var last=items.get(items.size()-1);String key=(last.hasNonNull("nextReviewAt")?last.path("nextReviewAt").asText():"9999")+"|"+last.path("createdAt").asText()+"|"+last.path("id").asText();out.put("nextCursor",Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8)));}return out;
    }
    public List<String> ids(String table,String column,String value) {
        if(!columns(table).contains(column))throw new IllegalArgumentException("无效索引字段");
        return legacy.jdbc.query("SELECT id FROM "+table+" WHERE "+column+"=? ORDER BY created_at,id",(rs,n)->rs.getString(1),value);
    }

    public Store legacy() {return legacy;}
    public com.fasterxml.jackson.databind.ObjectMapper json() {return legacy.json;}
    public boolean isEnabled() {return V2BootstrapConfiguration.enabled(env);}
    public Integer latestPracticeCaseVersion(String familyId) {return legacy.jdbc.queryForObject("SELECT max(case_version) FROM v2_practice_cases WHERE family_id=?",Integer.class,familyId);}
    public List<String> attemptIdsAfter(String caseId,int ordinal,int limit) {return legacy.jdbc.query("SELECT id FROM v2_practice_attempts WHERE case_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",(rs,n)->rs.getString(1),caseId,ordinal,limit);}
    public int nextAttemptOrdinal(String caseId) {return legacy.jdbc.queryForObject("SELECT COALESCE(max(ordinal),0)+1 FROM v2_practice_attempts WHERE case_id=?",Integer.class,caseId);}
    public List<String> recentAttemptIds(String caseId) {return legacy.jdbc.query("SELECT id FROM v2_practice_attempts WHERE case_id=? AND status IN ('IMPORTED','READY','FAILED','INTERRUPTED') ORDER BY ordinal DESC LIMIT 2",(rs,n)->rs.getString(1),caseId);}
    public int reviewItemCount(String sessionId,int questionIndex) {return legacy.jdbc.queryForObject("SELECT count(*) FROM v2_review_items WHERE source_session_id=? AND question_index=?",Integer.class,sessionId,questionIndex);}
    public List<String> reviewItemIds(String sessionId,int questionIndex) {return legacy.jdbc.query("SELECT id FROM v2_review_items WHERE source_session_id=? AND question_index=?",(rs,n)->rs.getString(1),sessionId,questionIndex);}
    public int skillEventCount(String skillId) {return legacy.jdbc.queryForObject("SELECT count(*) FROM v2_skill_events WHERE skill_id=?",Integer.class,skillId);}
    public List<ObjectNode> latestSelfRating(String skillId) {return legacy.jdbc.query("SELECT payload FROM v2_skill_events WHERE skill_id=? AND event_type='SELF_RATING' ORDER BY created_at DESC,id DESC LIMIT 1",(rs,n)->legacy.decode(rs.getString(1)),skillId);}
    public int nextDiagnosticVersion(String familyId) {return legacy.jdbc.queryForObject("SELECT max(diagnostic_version)+1 FROM v2_jd_diagnostics WHERE family_id=?",Integer.class,familyId);}
    public void updatePayload(String table,ObjectNode row) {columns(table);legacy.jdbc.update("UPDATE "+table+" SET payload=? WHERE id=?",encode(row),row.path("id").asText());}
    public int confirmStoryVersion(ObjectNode version) {return legacy.jdbc.update("UPDATE v2_story_versions SET status='CONFIRMED',payload=? WHERE id=? AND status='DRAFT'",encode(version),version.path("id").asText());}
    public List<String> manifestOperationIds(String kind,String scope,String resourceId) {return legacy.jdbc.query("SELECT id FROM v2_operations WHERE kind=? AND scope_key=? AND resource_id=? AND state='SUCCEEDED'",(rs,n)->rs.getString(1),kind,scope,resourceId);}
    public List<String> requestOperationIds(String kind,String scope,String requestId) {return legacy.jdbc.query("SELECT id FROM v2_operations WHERE kind=? AND scope_key=? AND request_id=?",(rs,n)->rs.getString(1),kind,scope,requestId);}
    public int updateOperation(ObjectNode op,String expectedState,int expectedGeneration) {return legacy.jdbc.update("UPDATE v2_operations SET state=?,generation=?,expected_revision=?,payload=?,updated_at=? WHERE id=? AND state=? AND generation=?",op.path("state").asText(),op.path("generation").asInt(),op.path("expectedRevision").asInt(),encode(op),op.path("updatedAt").asText(),op.path("id").asText(),expectedState,expectedGeneration);}
    public List<String> pendingOperationIds() {return legacy.jdbc.query("SELECT id FROM v2_operations WHERE state IN ('PENDING','RUNNING')",(rs,n)->rs.getString(1));}

}
