package cn.interview.mapper;

import cn.interview.entity.Document;
import cn.interview.entity.Material;
import cn.interview.exception.ApiException;
import cn.interview.exception.RecoveryException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import static cn.interview.common.Models.*;

@Component
public class SqliteStore {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final Path root;
    final TransactionTemplate transaction;
    public SqliteStore(@Value("${app.data-dir}") String dataDir,ObjectMapper json) throws Exception {
        this.json=json;root=Path.of(dataDir).toAbsolutePath().normalize();Files.createDirectories(root.resolve("uploads"));
        SQLiteConfig config=new SQLiteConfig();config.enforceForeignKeys(true);config.setBusyTimeout(5000);config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource ds=new SQLiteDataSource(config);ds.setUrl("jdbc:sqlite:"+root.resolve("interview.db"));
        jdbc=new JdbcTemplate(ds);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema=new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String sql:schema.split(";"))if(!sql.isBlank())jdbc.execute(sql);
    }
    public <T>T atomic(Supplier<T> action){return transaction.execute(status->action.get());}
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("数据序列化失败",e);}}
    public ObjectNode decode(String value){try{return (ObjectNode)json.readTree(value);}catch(Exception e){throw new IllegalStateException("本地数据无法读取",e);}}
    public List<Material> materials(String kind) {
        if(kind!=null)choice(kind,"RESUME","PROJECT","JD");
        return jdbc.query("SELECT * FROM materials "+(kind==null?"":"WHERE kind=? ")+"ORDER BY updated_at DESC",(rs,row)->new Material(rs.getString("id"),rs.getString("kind"),rs.getString("name"),rs.getString("content"),rs.getString("created_at"),rs.getString("updated_at"),documents(rs.getString("id")),repository(rs.getString("id"))),kind==null?new Object[]{}:new Object[]{kind});
    }
    public Material material(String id) {
        List<Material> found=jdbc.query("SELECT * FROM materials WHERE id=?",(rs,row)->new Material(rs.getString("id"),rs.getString("kind"),rs.getString("name"),rs.getString("content"),rs.getString("created_at"),rs.getString("updated_at"),documents(id),repository(id)),id);
        if(found.isEmpty())throw ApiException.missing("资料不存在或已删除");return found.getFirst();
    }
    public List<Document> documents(String id){return jdbc.query("SELECT * FROM documents WHERE material_id=? ORDER BY created_at",(rs,row)->new Document(rs.getString("id"),rs.getString("name"),rs.getString("content"),rs.getInt("truncated")==1,rs.getString("created_at")),id);}
    public void saveSession(ObjectNode s){s.put("updatedAt",now());jdbc.update("INSERT INTO sessions(id,payload,updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at",s.path("id").asText(),encode(s),s.path("updatedAt").asText());}
    public ObjectNode session(String id){return one("SELECT payload FROM sessions WHERE id=?",id,"面试不存在");}
    public List<ObjectNode> sessions(){return jdbc.query("SELECT payload FROM sessions ORDER BY updated_at DESC",(rs,row)->decode(rs.getString(1)));}
    public ObjectNode prediction(String id){return one("SELECT payload FROM predictions WHERE id=?",id,"押题记录不存在");}
    public List<ObjectNode> predictions(String resumeId){return jdbc.query("SELECT payload FROM predictions "+(resumeId==null?"":"WHERE resume_id=? ")+"ORDER BY created_at DESC",(rs,row)->decode(rs.getString(1)),resumeId==null?new Object[]{}:new Object[]{resumeId});}
    public void savePrediction(ObjectNode p){jdbc.update("INSERT INTO predictions(id,resume_id,payload,created_at) VALUES(?,?,?,?)",p.path("id").asText(),p.path("resumeId").asText(),encode(p),p.path("createdAt").asText());}
    public void saveReport(String id,ObjectNode r){jdbc.update("INSERT INTO reports(session_id,payload) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET payload=excluded.payload",id,encode(r));}
    public ObjectNode report(String id){return one("SELECT payload FROM reports WHERE session_id=?",id,"报告尚未生成");}
    ObjectNode one(String sql,String id,String message){List<ObjectNode> found=jdbc.query(sql,(rs,row)->decode(rs.getString(1)),id);if(found.isEmpty())throw ApiException.missing(message);return found.getFirst();}

    public ObjectNode availableMaterials(String cursor,int limit) {
        int count=Math.max(1,Math.min(100,limit));var args=new java.util.ArrayList<Object>();String where="";
        if(cursor!=null&&!cursor.isBlank())try{String[] key=new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);if(key.length!=2)throw new IllegalArgumentException();where=" WHERE (created_at,id)<(?,?)";args.add(key[0]);args.add(key[1]);}catch(IllegalArgumentException e){throw RecoveryException.invalid("资料游标无效");}
        args.add(count+1);var rows=jdbc.query("SELECT id,name,kind,created_at FROM materials"+where+" ORDER BY created_at DESC,id DESC LIMIT ?",(rs,n)->json.createObjectNode().put("id",rs.getString(1)).put("name",rs.getString(2)).put("kind",rs.getString(3)).put("createdAt",rs.getString(4)),args.toArray());
        var out=json.createObjectNode();var items=out.putArray("items");rows.subList(0,Math.min(count,rows.size())).forEach(items::add);out.putNull("nextCursor");if(rows.size()>count){var last=items.get(items.size()-1);out.put("nextCursor",java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((last.path("createdAt").asText()+"|"+last.path("id").asText()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}return out;
    }

    public Path uploadsDirectory() {return root.resolve("uploads");}
    public com.fasterxml.jackson.databind.JsonNode repository(String id) {
        var rows=jdbc.queryForList("SELECT payload FROM project_repositories WHERE material_id=?",String.class,id);
        return rows.isEmpty()?null:decode(rows.getFirst());
    }
    public void saveRepository(String id,com.fasterxml.jackson.databind.JsonNode repository) {
        if(repository==null)jdbc.update("DELETE FROM project_repositories WHERE material_id=?",id);
        else jdbc.update("INSERT INTO project_repositories(material_id,payload) VALUES(?,?) ON CONFLICT(material_id) DO UPDATE SET payload=excluded.payload",id,encode(repository));
    }
    public void insertMaterial(String id,String kind,String name,String content,String now) {jdbc.update("INSERT INTO materials VALUES(?,?,?,?,?,?)",id,kind,name,content,now,now);}
    public void updateMaterial(String id,String name,String content,String now) {jdbc.update("UPDATE materials SET name=?,content=?,updated_at=? WHERE id=?",name,content,now,id);}
    public void touchMaterial(String id,String now) {jdbc.update("UPDATE materials SET updated_at=? WHERE id=?",now,id);}
    public void deleteMaterial(String id) {jdbc.update("DELETE FROM materials WHERE id=?",id);}
    public List<String> documentStorageNames(String materialId) {return jdbc.queryForList("SELECT storage_name FROM documents WHERE material_id=?",String.class,materialId);}
    public List<String> documentStorageNames(String materialId,String documentId) {return jdbc.queryForList("SELECT storage_name FROM documents WHERE id=? AND material_id=?",String.class,documentId,materialId);}
    public void insertDocument(String id,String materialId,String name,String storage,String text,boolean truncated,String now) {jdbc.update("INSERT INTO documents VALUES(?,?,?,?,?,?,?)",id,materialId,name,storage,text,truncated?1:0,now);}
    public void deleteDocument(String materialId,String documentId) {jdbc.update("DELETE FROM documents WHERE id=? AND material_id=?",documentId,materialId);}

}
