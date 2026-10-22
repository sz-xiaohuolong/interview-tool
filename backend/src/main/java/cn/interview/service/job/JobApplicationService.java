package cn.interview.service.job;

import cn.interview.entity.JobApplication;
import cn.interview.exception.ApiException;
import cn.interview.mapper.JobApplicationStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobApplicationService {
    static final Set<String> STATUSES=Set.of("TO_APPLY","APPLIED","ASSESSMENT","INTERVIEW","OFFER","REJECTED","WITHDRAWN");
    static final Set<String> TYPES=Set.of("CAMPUS","INTERNSHIP","SUMMER_INTERNSHIP","FULL_TIME","OTHER");
    private final JobApplicationStore store;
    public JobApplicationService(JobApplicationStore store) { this.store=store; }

    public List<JobApplication> list(String company,String status,String archived,String sort,String direction) {
        if(company!=null && company.length()>200)throw ApiException.bad("公司搜索最多 200 字符");
        if(status!=null && !status.isEmpty())statusValue(status);
        return store.list(company,status,archived,sort,direction);
    }
    public JobApplication get(String id) { return store.get(id); }
    public JobApplication create(JsonNode body) {
        String now=Instant.now().toString();
        var row=validated(body,UUID.randomUUID().toString(),false,now,now);
        store.insert(row);return row;
    }
    public JobApplication update(String id,JsonNode body) {
        return store.atomic(()->{
            var original=store.get(id);
            var row=validated(body,id,original.archived(),original.createdAt(),Instant.now().toString());
            store.update(row);return row;
        });
    }
    public JobApplication status(String id,JsonNode body) {
        String status=statusValue(text(body,"status","投递状态",30,true,""));
        return store.atomic(()->{store.status(id,status,Instant.now().toString());return store.get(id);});
    }
    public JobApplication archive(String id,JsonNode body) {
        if(body==null || !body.path("archived").isBoolean())throw ApiException.bad("归档状态必须为布尔值");
        return store.atomic(()->{store.archive(id,body.path("archived").booleanValue(),Instant.now().toString());return store.get(id);});
    }
    public void delete(String id) { store.delete(id); }

    static JobApplication validated(JsonNode body,String id,boolean archived,String createdAt,String updatedAt) {
        if(body==null || !body.isObject())throw ApiException.bad("岗位记录必须为对象");
        String company=text(body,"company","公司名称",200,true,"");
        String position=text(body,"position","岗位名称",200,true,"");
        String url=text(body,"url","原始岗位链接",2048,false,"");
        if(!url.isEmpty())try {
            URI uri=URI.create(url);
            if(!(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost()==null || uri.getRawUserInfo()!=null || url.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();
        } catch(Exception ignored) { throw ApiException.bad("原始岗位链接仅支持完整的 HTTP(S) 地址，不能包含账号凭据或控制字符"); }
        String type=text(body,"jobType","岗位类型",30,false,"OTHER");
        if(!TYPES.contains(type))throw ApiException.bad("岗位类型无效");
        return new JobApplication(id,company,position,text(body,"location","工作地点",200,false,""),
            text(body,"platform","招聘平台",100,false,""),url,text(body,"jd","JD 正文",24000,false,""),type,
            statusValue(text(body,"status","投递状态",30,false,"TO_APPLY")),
            date(text(body,"appliedDate","投递日期",10,false,""),"投递日期"),
            date(text(body,"nextActionDate","下次行动日期",10,false,""),"下次行动日期"),
            time(text(body,"interviewAt","面试时间",40,false,""),"面试时间"),
            text(body,"interviewNote","面试说明",1000,false,""),text(body,"notes","备注",12000,false,""),archived,createdAt,updatedAt);
    }
    static String text(JsonNode body,String field,String label,int limit,boolean required,String fallback) {
        JsonNode node=body==null?null:body.get(field);
        String value;
        if(node==null || node.isNull())value=fallback;
        else if(!node.isTextual())throw ApiException.bad(label+"必须为文本");
        else value=node.textValue();
        if(required && value.isBlank())throw ApiException.bad(label+"不能为空");
        if(value.length()>limit)throw ApiException.bad(label+"不能超过 "+limit+" 字符");
        if(value.indexOf('\0')>=0)throw ApiException.bad(label+"不能含空字符");
        return value;
    }
    static String statusValue(String value) {
        if(!STATUSES.contains(value))throw ApiException.bad("投递状态无效");return value;
    }
    static String date(String value,String label) {
        if(value.isEmpty())return null;
        try {
            if(!value.matches("\\d{4}-\\d{2}-\\d{2}") || value.startsWith("0000"))throw new DateTimeParseException("",value,0);
            return LocalDate.parse(value).toString();
        } catch(DateTimeParseException e) {throw ApiException.bad(label+"必须是有效的 YYYY-MM-DD 日期");}
    }
    static String time(String value,String label) {
        if(value.isEmpty())return null;
        try {
            var time=OffsetDateTime.parse(value);
            if(time.getYear()<1 || time.getYear()>9999)throw new DateTimeParseException("",value,0);
            return time.toInstant().toString();
        } catch(DateTimeParseException e) { throw ApiException.bad(label+"必须为带时区偏移的有效 ISO 日期时间"); }
    }
}
