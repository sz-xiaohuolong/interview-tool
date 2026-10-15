package cn.interview;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import static cn.interview.Models.*;

@Service
public class MaterialService {
    static final int TEXT_LIMIT=24000;
    static final int CONTEXT_LIMIT=30000;
    final Store store;
    public MaterialService(Store store){this.store=store;}
    public List<Material> list(String kind){return store.materials(kind);}
    public synchronized Material create(JsonNode body){
        String kind=choice(required(body,"kind",20),"RESUME","PROJECT","JD");
        String name=required(body,"name",120),content=optional(body,"content","",TEXT_LIMIT),id=id(),now=now();
        store.jdbc.update("INSERT INTO materials VALUES(?,?,?,?,?,?)",id,kind,name,content,now,now);return store.material(id);
    }
    public synchronized Material update(String id,JsonNode body){store.material(id);String name=required(body,"name",120),content=optional(body,"content","",TEXT_LIMIT);store.jdbc.update("UPDATE materials SET name=?,content=?,updated_at=? WHERE id=?",name,content,now(),id);return store.material(id);}
    public synchronized void delete(String id){
        store.material(id);List<String> files=store.jdbc.queryForList("SELECT storage_name FROM documents WHERE material_id=?",String.class,id);
        store.jdbc.update("DELETE FROM materials WHERE id=?",id);files.forEach(this::removeFile);
    }
    private void removeFile(String name){try{Files.deleteIfExists(store.root.resolve("uploads").resolve(name));}catch(Exception e){throw new ApiException(500,"FILE_DELETE_FAILED","资料记录已删除，但原文件清理失败，请检查 data/uploads 目录权限");}}
    public synchronized Material upload(String kind,String name,MultipartFile file){
        choice(kind,"RESUME","PROJECT","JD");Extracted e=extract(file,kind);String materialId=id(),now=now();
        String title=(name==null||name.isBlank())?e.name:name.strip();if(title.length()>120)throw ApiException.bad("资料名称不能超过 120 个字符");
        String storage=saveFile(file,e.extension);
        try{return store.atomic(()->{store.jdbc.update("INSERT INTO materials VALUES(?,?,?,?,?,?)",materialId,kind,title,"",now,now);insertDocument(materialId,e,storage);return store.material(materialId);});}
        catch(RuntimeException ex){removeFile(storage);throw ex;}
    }
    public synchronized Material addDocument(String materialId,MultipartFile file){
        Material m=store.material(materialId);if(m.documents().size()>=20)throw ApiException.bad("每份资料最多保存 20 个文档");
        Extracted e=extract(file,m.kind());String storage=saveFile(file,e.extension);
        try{return store.atomic(()->{insertDocument(materialId,e,storage);store.jdbc.update("UPDATE materials SET updated_at=? WHERE id=?",now(),materialId);return store.material(materialId);});}catch(RuntimeException ex){removeFile(storage);throw ex;}
    }
    public synchronized void deleteDocument(String materialId,String documentId){
        store.material(materialId);List<String> files=store.jdbc.queryForList("SELECT storage_name FROM documents WHERE id=? AND material_id=?",String.class,documentId,materialId);
        if(files.isEmpty())throw ApiException.missing("文档不存在");
        store.atomic(()->{store.jdbc.update("DELETE FROM documents WHERE id=? AND material_id=?",documentId,materialId);store.jdbc.update("UPDATE materials SET updated_at=? WHERE id=?",now(),materialId);return null;});removeFile(files.getFirst());
    }
    void insertDocument(String materialId,Extracted e,String storage){store.jdbc.update("INSERT INTO documents VALUES(?,?,?,?,?,?,?)",id(),materialId,e.name,storage,e.text,e.truncated?1:0,now());}
    String saveFile(MultipartFile file,String extension){String storage=id()+"."+extension;try{file.transferTo(store.root.resolve("uploads").resolve(storage));return storage;}catch(Exception e){throw new ApiException(500,"FILE_WRITE_FAILED","无法保存上传文件，请检查本地目录权限");}}
    record Extracted(String name,String extension,String text,boolean truncated){}
    Extracted extract(MultipartFile file,String kind){
        if(file==null||file.isEmpty())throw ApiException.bad("文件不能为空");
        if(file.getSize()>5L*1024*1024)throw ApiException.bad("单文件不能超过 5 MiB");
        String name=file.getOriginalFilename()==null?"文档":file.getOriginalFilename().replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if(name.isBlank()||name.length()>200||name.chars().anyMatch(c->c<32))throw ApiException.bad("文件名无效或过长");
        String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!List.of("pdf","docx","md","markdown","txt").contains(ext)||("RESUME".equals(kind)&&"txt".equals(ext)))throw ApiException.bad("不支持的文件格式；简历支持 PDF、DOCX、Markdown，项目还支持 TXT");
        String text;
        try{
            byte[] bytes=file.getBytes();
            if(ext.equals("pdf")){try(var pdf=Loader.loadPDF(bytes)){if(pdf.getNumberOfPages()>200)throw ApiException.bad("PDF 不得超过 200 页");text=new PDFTextStripper().getText(pdf);}}
            else if(ext.equals("docx")){try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes));var extractor=new XWPFWordExtractor(doc)){text=extractor.getText();}}
            else{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\0')>=0)throw ApiException.bad("文本包含无效字符，请上传 UTF-8 文本");}
        }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.bad("无法解析文件，请确认格式正确、文件未加密且文本采用 UTF-8 编码");}
        text=text.replace("\uFEFF","").strip();if(text.isBlank())throw ApiException.bad("未提取到文本；扫描 PDF 需要先转换为可选择的文本资料");
        boolean truncated=text.length()>TEXT_LIMIT;return new Extracted(name,ext,truncated?text.substring(0,TEXT_LIMIT):text,truncated);
    }
    public Context context(Material m,String jd){
        StringBuilder text=new StringBuilder("资料名称：").append(m.name()).append("\n手动资料：\n").append(m.content());boolean truncated=false;
        for(Document d:m.documents()){text.append("\n文档：").append(d.name()).append("\n").append(d.text());truncated|=d.truncated();}
        if(jd!=null&&!jd.isBlank())text.append("\n目标 JD：\n").append(jd);
        truncated|=text.length()>CONTEXT_LIMIT;return new Context(text.substring(0,Math.min(text.length(),CONTEXT_LIMIT)),truncated);
    }
}
