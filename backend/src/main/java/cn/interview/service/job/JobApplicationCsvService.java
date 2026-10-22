package cn.interview.service.job;

import cn.interview.entity.JobApplication;
import cn.interview.exception.ApiException;
import cn.interview.mapper.JobApplicationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class JobApplicationCsvService {
    private static final int MAX_BYTES=5*1024*1024, MAX_ROWS=5000;
    private static final String FORMAT="投递记录-1", ESCAPE="文本:";
    private static final List<String> HEADERS=List.of("格式","记录ID","公司名称","岗位名称","工作地点","招聘平台","原始岗位链接","JD正文",
        "岗位类型","投递状态","投递日期","下次行动日期","面试时间","面试说明","备注","已归档","创建时间","修改时间");
    private static final List<String> FIELDS=List.of("id","company","position","location","platform","url","jd","jobType","status",
        "appliedDate","nextActionDate","interviewAt","interviewNote","notes","archived","createdAt","updatedAt");
    private final JobApplicationStore store;
    private final ObjectMapper json;
    private final byte[] secret=new byte[32];
    public JobApplicationCsvService(JobApplicationStore store,ObjectMapper json) { this.store=store;this.json=json;new SecureRandom().nextBytes(secret); }
    public record RowError(int line,String message) {}
    public record PreviewRow(int line,String company,String position,String disposition,String message) {}
    public record Preview(String previewToken,int total,int valid,int created,int duplicates,int conflicts,List<RowError> errors,List<PreviewRow> rows) {}
    public record ImportResult(int created,int duplicates) {}
    private record Plan(List<JobApplication> inserts,List<PreviewRow> rows,List<RowError> errors,int duplicates,int conflicts,String fingerprint) {}

    public byte[] template() { return exportRows(List.of()); }
    public byte[] export() { return exportRows(store.list(null,null,"all","createdAt","asc")); }
    private byte[] exportRows(List<JobApplication> records) {
        try {
            var text=new StringWriter(); text.write('\uFEFF');
            try(var printer=new CSVPrinter(text,CSVFormat.RFC4180)) {
                printer.printRecord(HEADERS);
                for(var record:records) {
                    var node=json.valueToTree(record); var values=new ArrayList<String>(); values.add(FORMAT);
                    for(String field:FIELDS) values.add(protect(node.path(field).isNull()?"":node.path(field).asText()));
                    printer.printRecord(values);
                }
            }
            return text.toString().getBytes(StandardCharsets.UTF_8);
        } catch(java.io.IOException e) { throw new IllegalStateException("CSV 导出失败",e); }
    }
    public Preview preview(MultipartFile file) {
        byte[] bytes=read(file); Plan plan=plan(bytes);
        String time=Long.toString(Instant.now().getEpochSecond());
        return new Preview(time+":"+sign(time,plan.fingerprint()),plan.rows().size(),plan.inserts().size(),plan.inserts().size(),
            plan.duplicates(),plan.conflicts(),plan.errors(),plan.rows());
    }
    public ImportResult importFile(MultipartFile file,String previewToken) {
        byte[] bytes=read(file);
        return store.atomic(()->{
            Plan plan=plan(bytes);
            if(!plan.errors().isEmpty())throw ApiException.bad("CSV 存在无效行或冲突，请修正后重新预览；本次未写入任何记录");
            verifyToken(previewToken,plan.fingerprint());
            String now=Instant.now().toString();
            for(var row:plan.inserts()) {
                var node=json.valueToTree(row);
                String created=row.createdAt()==null?now:row.createdAt();
                String updated=row.updatedAt()==null?created:row.updatedAt();
                store.insert(JobApplicationService.validated(node,row.id(),row.archived(),created,updated));
            }
            return new ImportResult(plan.inserts().size(),plan.duplicates());
        });
    }
    private Plan plan(byte[] bytes) {
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(Exception e) { throw ApiException.bad("CSV 必须使用 UTF-8 编码，请另存为 CSV UTF-8 后重试"); }
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        var byId=new HashMap<String,JobApplication>();var byContent=new HashMap<String,JobApplication>();
        for(var row:store.list(null,null,"all","createdAt","asc")) { byId.put(row.id(),row);byContent.putIfAbsent(content(row),row); }
        var inserts=new ArrayList<JobApplication>();var rows=new ArrayList<PreviewRow>();var errors=new ArrayList<RowError>();
        var signature=new StringBuilder(hash(bytes));int duplicates=0,conflicts=0;
        try(var parser=CSVFormat.RFC4180.parse(new StringReader(text))) {
            var iterator=parser.iterator();
            if(!iterator.hasNext())throw ApiException.bad("CSV 文件为空，请使用模板填写岗位记录");
            var header=iterator.next();
            if(!header.toList().equals(HEADERS))throw ApiException.bad("CSV 表头与模板不一致，请保留模板中的全部列及顺序");
            while(iterator.hasNext()) {
                var csv=iterator.next();int line=rows.size()+2;
                if(rows.size()>=MAX_ROWS)throw ApiException.bad("CSV 最多包含 5000 条记录，请拆分文件");
                String company="",position="";
                try {
                    if(csv.size()!=HEADERS.size())throw ApiException.bad("列数与表头不一致，请检查逗号和引号");
                    if(!FORMAT.equals(csv.get(0)))throw ApiException.bad("格式列必须为「投递记录-1」");
                    var body=json.createObjectNode();
                    for(int i=0;i<FIELDS.size();i++)body.put(FIELDS.get(i),unprotect(csv.get(i+1)));
                    company=body.path("company").asText();position=body.path("position").asText();
                    String archived=body.path("archived").asText();
                    if(!List.of("","true","false").contains(archived))throw ApiException.bad("已归档必须是 true、false 或空值");
                    if(body.path("status").asText().isEmpty())body.put("status","TO_APPLY");
                    if(body.path("jobType").asText().isEmpty())body.put("jobType","OTHER");
                    String created=JobApplicationService.time(body.path("createdAt").asText(),"创建时间");
                    String updated=JobApplicationService.time(body.path("updatedAt").asText(),"修改时间");
                    if(created==null && updated!=null)throw ApiException.bad("填写修改时间时也必须填写创建时间");
                    if(created!=null && updated!=null && Instant.parse(updated).isBefore(Instant.parse(created)))throw ApiException.bad("修改时间不能早于创建时间");
                    String id=body.path("id").asText();boolean suppliedId=!id.isEmpty();
                    if(suppliedId && (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))throw ApiException.bad("记录 ID 必须为小写标准 UUID，新增记录可留空");
                    var candidate=JobApplicationService.validated(body,id,archived.equals("true"),created,updated);
                    String content=content(candidate);
                    // 无 ID 行由完整业务内容产生稳定身份，重复导入后即使原记录被编辑，也只会提示冲突。
                    if(!suppliedId) { id=UUID.nameUUIDFromBytes(("job-csv:"+content).getBytes(StandardCharsets.UTF_8)).toString();candidate=JobApplicationService.validated(body,id,candidate.archived(),created,updated); }
                    JobApplication existing=byId.get(id);
                    if(existing==null && !suppliedId)existing=byContent.get(content);
                    if(existing!=null) {
                        signature.append('|').append(encode(existing));
                        boolean same=content(existing).equals(content)
                            && (created==null || created.equals(existing.createdAt())) && (updated==null || updated.equals(existing.updatedAt()));
                        if(!same) {
                            conflicts++;throw ApiException.bad("记录 ID 已存在但内容不同；不会覆盖，请在页面核对或修正文件");
                        }
                        duplicates++;rows.add(new PreviewRow(line,company,position,"DUPLICATE","相同记录已存在，将跳过"));
                    } else {
                        inserts.add(candidate);byId.put(id,candidate);byContent.putIfAbsent(content,candidate);
                        rows.add(new PreviewRow(line,company,position,"NEW","确认后新增"));
                    }
                } catch(ApiException e) { errors.add(new RowError(line,e.getMessage()));rows.add(new PreviewRow(line,limitPreview(company),limitPreview(position),"ERROR",e.getMessage())); }
            }
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw ApiException.bad("CSV 格式无效，请检查第 "+(rows.size()+2)+" 条记录附近的双引号及换行；未写入任何记录"); }
        if(rows.isEmpty())throw ApiException.bad("CSV 只有表头，请至少填写一条岗位记录");
        signature.append('|').append(encode(rows));
        return new Plan(inserts,rows,errors,duplicates,conflicts,hash(signature.toString().getBytes(StandardCharsets.UTF_8)));
    }
    private byte[] read(MultipartFile file) {
        if(file.isEmpty())throw ApiException.bad("CSV 文件为空，请选择填写后的文件");
        if(file.getSize()>MAX_BYTES)throw ApiException.bad("CSV 不能超过 5 MiB");
        try(var input=file.getInputStream()) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES)throw ApiException.bad("CSV 不能超过 5 MiB");return bytes;
        } catch(java.io.IOException e) { throw ApiException.bad("无法读取 CSV 文件，请重新选择"); }
    }
    private String content(JobApplication row) {
        var node=json.valueToTree(row);var content=((com.fasterxml.jackson.databind.node.ObjectNode)node).deepCopy();
        content.remove(List.of("id","createdAt","updatedAt"));return encode(content);
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("CSV 校验失败"); } }
    private static String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch(Exception e) { throw new IllegalStateException(e); } }
    private String sign(String time,String fingerprint) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((time+":"+fingerprint).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    private void verifyToken(String token,String fingerprint) {
        try {
            String[] parts=token.split(":",-1);long time=Long.parseLong(parts[0]),age=Instant.now().getEpochSecond()-time;
            if(parts.length!=2 || age<0 || age>900 || !MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.UTF_8),sign(parts[0],fingerprint).getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
        } catch(Exception e) { throw ApiException.conflict("预览已过期、文件或相关记录已变化，请重新预览后确认；本次未写入记录"); }
    }
    private static String protect(String value) {
        if(value.isEmpty())return value;
        char first=value.charAt(0);
        boolean unsafe=first<=' ' || Character.isWhitespace(first) || Character.isSpaceChar(first)
            || Character.getType(first)==Character.FORMAT || "=+-@＝＋－＠".indexOf(first)>=0;
        return value.startsWith(ESCAPE) || unsafe ? ESCAPE+value : value;
    }
    private static String unprotect(String value) { return value.startsWith(ESCAPE)?value.substring(ESCAPE.length()):value; }
    private static String limitPreview(String value) { return value.length()>200?value.substring(0,200)+"…":value; }
}
