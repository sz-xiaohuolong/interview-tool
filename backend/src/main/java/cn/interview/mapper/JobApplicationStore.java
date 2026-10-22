package cn.interview.mapper;

import cn.interview.entity.JobApplication;
import cn.interview.exception.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class JobApplicationStore {
    private final SqliteStore database;
    private static final Map<String,String> SORTS = Map.of(
        "updatedAt","julianday(updated_at)", "createdAt","julianday(created_at)",
        "appliedDate","applied_date", "nextActionDate","next_action_date", "interviewAt","julianday(interview_at)");

    public JobApplicationStore(SqliteStore database) { this.database=database; }
    public <T> T atomic(Supplier<T> action) { return database.atomic(action); }

    public List<JobApplication> list(String company,String status,String archived,String sort,String direction) {
        String column=SORTS.get(sort);
        if(column==null || !(direction.equals("asc") || direction.equals("desc"))) throw ApiException.bad("排序字段或方向无效");
        if(!List.of("false","true","all").contains(archived)) throw ApiException.bad("归档筛选无效");
        var clauses=new ArrayList<String>(); var args=new ArrayList<Object>();
        if(!archived.equals("all")) { clauses.add("archived=?"); args.add(archived.equals("true")?1:0); }
        if(company!=null && !company.isEmpty()) { clauses.add("instr(lower(company),lower(?))>0"); args.add(company); }
        if(status!=null && !status.isEmpty()) { clauses.add("status=?"); args.add(status); }
        String where=clauses.isEmpty()?"":" WHERE "+String.join(" AND ",clauses);
        return database.jdbc.query("SELECT * FROM job_applications"+where+" ORDER BY ("+column+" IS NULL) ASC,"+column+" "+direction+",id ASC",
            (rs,n)->map(rs),args.toArray());
    }
    public JobApplication find(String id) {
        return database.jdbc.query("SELECT * FROM job_applications WHERE id=?",(rs,n)->map(rs),id).stream().findFirst().orElse(null);
    }
    public JobApplication get(String id) {
        JobApplication row=find(id);
        if(row==null)throw ApiException.missing("岗位记录不存在或已删除");
        return row;
    }
    public void insert(JobApplication row) {
        database.jdbc.update("""
            INSERT INTO job_applications(id,company,position,location,platform,url,jd,job_type,status,
                applied_date,next_action_date,interview_at,interview_note,notes,archived,created_at,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,row.id(),row.company(),row.position(),row.location(),row.platform(),row.url(),row.jd(),row.jobType(),row.status(),
            row.appliedDate(),row.nextActionDate(),row.interviewAt(),row.interviewNote(),row.notes(),row.archived()?1:0,row.createdAt(),row.updatedAt());
    }
    public void update(JobApplication row) {
        int changed=database.jdbc.update("""
            UPDATE job_applications SET company=?,position=?,location=?,platform=?,url=?,jd=?,job_type=?,status=?,
                applied_date=?,next_action_date=?,interview_at=?,interview_note=?,notes=?,updated_at=? WHERE id=?
            """,row.company(),row.position(),row.location(),row.platform(),row.url(),row.jd(),row.jobType(),row.status(),
            row.appliedDate(),row.nextActionDate(),row.interviewAt(),row.interviewNote(),row.notes(),row.updatedAt(),row.id());
        if(changed==0)throw ApiException.missing("岗位记录不存在或已删除");
    }
    public void status(String id,String status,String now) {
        if(database.jdbc.update("UPDATE job_applications SET status=?,updated_at=? WHERE id=?",status,now,id)==0)
            throw ApiException.missing("岗位记录不存在或已删除");
    }
    public void archive(String id,boolean archived,String now) {
        if(database.jdbc.update("UPDATE job_applications SET archived=?,updated_at=? WHERE id=?",archived?1:0,now,id)==0)
            throw ApiException.missing("岗位记录不存在或已删除");
    }
    public void delete(String id) {
        if(database.jdbc.update("DELETE FROM job_applications WHERE id=?",id)==0)throw ApiException.missing("岗位记录不存在或已删除");
    }
    private static JobApplication map(ResultSet rs) throws SQLException {
        return new JobApplication(rs.getString("id"),rs.getString("company"),rs.getString("position"),rs.getString("location"),
            rs.getString("platform"),rs.getString("url"),rs.getString("jd"),rs.getString("job_type"),rs.getString("status"),
            rs.getString("applied_date"),rs.getString("next_action_date"),rs.getString("interview_at"),rs.getString("interview_note"),
            rs.getString("notes"),rs.getInt("archived")==1,rs.getString("created_at"),rs.getString("updated_at"));
    }
}
