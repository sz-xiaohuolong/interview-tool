package cn.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import static cn.interview.Models.*;

@Component
public class Store {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final Path root;
    final TransactionTemplate transaction;
    public Store(@Value("${app.data-dir}") String dataDir,ObjectMapper json) throws Exception {
        this.json=json;root=Path.of(dataDir).toAbsolutePath().normalize();Files.createDirectories(root.resolve("uploads"));
        SQLiteConfig config=new SQLiteConfig();config.enforceForeignKeys(true);config.setBusyTimeout(5000);config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource ds=new SQLiteDataSource(config);ds.setUrl("jdbc:sqlite:"+root.resolve("interview.db"));
        jdbc=new JdbcTemplate(ds);transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        String schema=new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
        for(String sql:schema.split(";"))if(!sql.isBlank())jdbc.execute(sql);
    }
    <T>T atomic(Supplier<T> action){return transaction.execute(status->action.get());}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("数据序列化失败",e);}}
    ObjectNode decode(String value){try{return (ObjectNode)json.readTree(value);}catch(Exception e){throw new IllegalStateException("本地数据无法读取",e);}}
    List<Material> materials(String kind) {
        if(kind!=null)choice(kind,"RESUME","PROJECT","JD");
        return jdbc.query("SELECT * FROM materials "+(kind==null?"":"WHERE kind=? ")+"ORDER BY updated_at DESC",(rs,row)->new Material(rs.getString("id"),rs.getString("kind"),rs.getString("name"),rs.getString("content"),rs.getString("created_at"),rs.getString("updated_at"),documents(rs.getString("id"))),kind==null?new Object[]{}:new Object[]{kind});
    }
    Material material(String id) {
        List<Material> found=jdbc.query("SELECT * FROM materials WHERE id=?",(rs,row)->new Material(rs.getString("id"),rs.getString("kind"),rs.getString("name"),rs.getString("content"),rs.getString("created_at"),rs.getString("updated_at"),documents(id)),id);
        if(found.isEmpty())throw ApiException.missing("资料不存在或已删除");return found.getFirst();
    }
    List<Document> documents(String id){return jdbc.query("SELECT * FROM documents WHERE material_id=? ORDER BY created_at",(rs,row)->new Document(rs.getString("id"),rs.getString("name"),rs.getString("content"),rs.getInt("truncated")==1,rs.getString("created_at")),id);}
    void saveSession(ObjectNode s){s.put("updatedAt",now());jdbc.update("INSERT INTO sessions(id,payload,updated_at) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at",s.path("id").asText(),encode(s),s.path("updatedAt").asText());}
    ObjectNode session(String id){return one("SELECT payload FROM sessions WHERE id=?",id,"面试不存在");}
    List<ObjectNode> sessions(){return jdbc.query("SELECT payload FROM sessions ORDER BY updated_at DESC",(rs,row)->decode(rs.getString(1)));}
    ObjectNode prediction(String id){return one("SELECT payload FROM predictions WHERE id=?",id,"押题记录不存在");}
    List<ObjectNode> predictions(String resumeId){return jdbc.query("SELECT payload FROM predictions "+(resumeId==null?"":"WHERE resume_id=? ")+"ORDER BY created_at DESC",(rs,row)->decode(rs.getString(1)),resumeId==null?new Object[]{}:new Object[]{resumeId});}
    void savePrediction(ObjectNode p){jdbc.update("INSERT INTO predictions(id,resume_id,payload,created_at) VALUES(?,?,?,?)",p.path("id").asText(),p.path("resumeId").asText(),encode(p),p.path("createdAt").asText());}
    void saveReport(String id,ObjectNode r){jdbc.update("INSERT INTO reports(session_id,payload) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET payload=excluded.payload",id,encode(r));}
    ObjectNode report(String id){return one("SELECT payload FROM reports WHERE session_id=?",id,"报告尚未生成");}
    ObjectNode one(String sql,String id,String message){List<ObjectNode> found=jdbc.query(sql,(rs,row)->decode(rs.getString(1)),id);if(found.isEmpty())throw ApiException.missing(message);return found.getFirst();}
}
