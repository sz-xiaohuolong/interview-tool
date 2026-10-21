package cn.interview.client.source;

import cn.interview.client.transport.KnowledgeTransport;
import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 仅从限定公开仓库的固定版本读取源码；远端返回的 URL 从不直接发起请求。 */
@Component
public class GitHubCodeClient {
    public static final int FILE_BYTES=96*1024, TREE_FILES=1500;
    private final ObjectMapper json;
    private final KnowledgeTransport transport;
    private final Path cache;

    @Autowired
    public GitHubCodeClient(ObjectMapper json,@Value("${app.data-dir}") String dataDir) {
        this(json,cn.interview.client.transport.FixedHttpTransport.github(),Path.of(dataDir).resolve("source/cache"));
    }
    public GitHubCodeClient(ObjectMapper json,KnowledgeTransport transport,Path cache) {
        this.json=json;this.transport=transport;this.cache=cache;
    }
    public static String repository(String url) {
        if(url==null||url.length()>300)throw ApiException.bad("请填写规范的 GitHub 公开仓库地址");
        String normalized=url.strip();
        if(normalized.startsWith("github.com/"))normalized="https://"+normalized;
        if(!normalized.matches("https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}/?"))
            throw ApiException.bad("仅支持 https://github.com/所有者/仓库，不支持其他地址、分支链接或查询参数");
        String repo=normalized.substring("https://github.com/".length()).replaceAll("/$","");
        if(repo.endsWith(".git"))repo=repo.substring(0,repo.length()-4);
        if(repo.endsWith("/.")||repo.endsWith("/..")||repo.endsWith("/"))throw ApiException.bad("GitHub 仓库名称无效");
        return repo;
    }
    public ObjectNode resolve(String repo) {
        repo=repository("https://github.com/"+repo);
        JsonNode info=get("/repos/"+repo,64*1024);
        if(!info.path("private").isBoolean()||info.path("private").asBoolean()
                ||!repo.equalsIgnoreCase(info.path("full_name").asText()))
            throw failure("SOURCE_NOT_PUBLIC","仓库不是可核验的 GitHub 公开仓库");
        String branch=info.path("default_branch").asText();
        if(branch.isBlank()||branch.length()>200)throw failure("SOURCE_EMPTY","仓库没有可读取的默认分支");
        // 分支摘要足以锁定版本，避免提交详情携带大批文件差异。
        String commit=get("/repos/"+repo+"/branches/"+URLEncoder.encode(branch,StandardCharsets.UTF_8),256*1024).path("commit").path("sha").asText();
        if(!commit.matches("[a-f0-9]{40}"))throw failure("SOURCE_INVALID","GitHub 未返回有效的代码版本");
        return json.createObjectNode().put("repository",repo).put("url","https://github.com/"+repo)
                .put("commitSha",commit).put("branch",branch).put("status","READY").put("checkedAt",java.time.Instant.now().toString());
    }
    public ObjectNode tree(JsonNode snapshot) {
        check(snapshot);
        JsonNode remote=get("/repos/"+snapshot.path("repository").asText()+"/git/trees/"+snapshot.path("commitSha").asText()+"?recursive=1",2_000_000);
        if(!remote.path("tree").isArray())throw failure("SOURCE_INVALID","GitHub 目录返回格式无效");
        ObjectNode result=json.createObjectNode();var files=result.putArray("files");
        boolean partial=remote.path("truncated").asBoolean();
        for(JsonNode entry:remote.path("tree")) {
            String path=entry.path("path").asText();
            if(!entry.path("type").asText().equals("blob")||!entry.path("mode").asText().matches("100(644|755)")
                    ||entry.path("size").asLong(-1)<0||entry.path("size").asLong()>FILE_BYTES||!allowed(path))continue;
            if(files.size()>=TREE_FILES){partial=true;break;}
            String sha=entry.path("sha").asText();
            if(!sha.matches("[a-f0-9]{40}"))continue;
            files.addObject().put("path",path).put("blobSha",sha).put("size",entry.path("size").asLong());
        }
        return result.put("partial",partial);
    }
    public String file(JsonNode snapshot,JsonNode tree,String path) {
        check(snapshot);
        if(!allowed(path))throw ApiException.bad("该文件不在允许读取的源码范围");
        JsonNode found=null;
        for(JsonNode entry:tree.path("files"))if(path.equals(entry.path("path").asText())){found=entry;break;}
        if(found==null)throw ApiException.bad("文件不属于本场固定版本的允许目录");
        String blob=found.path("blobSha").asText();
        if(!blob.matches("[a-f0-9]{40}"))throw ApiException.bad("源码对象标识无效");
        Path target=cache.resolve(cn.interview.common.ContentHash.sha(snapshot.path("repository").asText().toLowerCase(Locale.ROOT)))
                .resolve(snapshot.path("commitSha").asText()).resolve(blob+".txt");
        try {
            if(Files.isRegularFile(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&Files.size(target)<=FILE_BYTES){
                try{return verified(Files.readAllBytes(target),blob,found.path("size").asLong());}
                catch(ApiException error){Files.deleteIfExists(target);throw error;}
            }
            JsonNode response=get("/repos/"+snapshot.path("repository").asText()+"/git/blobs/"+blob,160*1024);
            if(!"base64".equals(response.path("encoding").asText())||!blob.equals(response.path("sha").asText()))
                throw failure("SOURCE_INVALID","源码内容身份或编码不匹配");
            byte[] bytes=Base64.getDecoder().decode(response.path("content").asText().replace("\n","").replace("\r",""));
            String text=verified(bytes,blob,found.path("size").asLong());
            Files.createDirectories(target.getParent());
            Path temporary=Files.createTempFile(target.getParent(),"blob-",".tmp");
            try{Files.write(temporary,bytes);Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
            finally{Files.deleteIfExists(temporary);}
            return text;
        } catch(ApiException e){throw e;}
        catch(Exception e){throw failure("SOURCE_READ_FAILED","无法读取或缓存源码文件");}
    }
    private String verified(byte[] bytes,String blob,long expected) throws Exception {
        if(bytes.length>FILE_BYTES||bytes.length!=expected)throw failure("SOURCE_INVALID","源码大小或摘要与固定目录不一致");
        var digest=java.security.MessageDigest.getInstance("SHA-1");digest.update(("blob "+bytes.length+"\0").getBytes(StandardCharsets.UTF_8));
        if(!HexFormat.of().formatHex(digest.digest(bytes)).equals(blob))throw failure("SOURCE_INVALID","源码内容与 Git 对象摘要不符");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(text.indexOf('\0')>=0)throw failure("SOURCE_BINARY","不读取二进制文件");return text;
    }
    private void check(JsonNode snapshot) {
        repository("https://github.com/"+snapshot.path("repository").asText());
        if(!snapshot.path("commitSha").asText().matches("[a-f0-9]{40}"))throw ApiException.bad("本场尚未锁定有效代码版本");
    }
    public static boolean allowed(String path) {
        if(path==null||path.isBlank()||path.length()>350||path.startsWith("/")||path.contains("\\")
                ||path.contains("%")||path.contains("?")||path.contains("#")||path.chars().anyMatch(c->c<32))return false;
        var parts=path.toLowerCase(Locale.ROOT).split("/");
        for(String part:parts)if(part.isBlank()||part.equals("..")||part.startsWith(".")
                ||Set.of("node_modules","vendor","target","build","dist","coverage","__pycache__").contains(part)
                ||part.contains("secret")||part.contains("credential")||part.contains("private")||part.contains("keystore")||part.contains("password"))return false;
        String name=parts[parts.length-1];
        if(name.endsWith(".pem")||name.endsWith(".key")||name.startsWith("application-"))return false;
        return name.matches(".*\\.(java|kt|scala|py|ts|tsx|js|jsx|vue|go|rs|cs|sql|md)$")
                ||name.equals("pom.xml")||name.equals("build.gradle")||name.equals("package.json");
    }
    private JsonNode get(String path,int maximum) {
        try {
            var response=transport.fetch(URI.create("https://api.github.com"+path),Duration.ofSeconds(10),maximum);
            if(response.status()==404)throw failure("SOURCE_NOT_PUBLIC","仓库不存在、不是公开仓库，或固定版本已不可访问");
            if(response.status()==403||response.status()==429)throw failure("SOURCE_RATE_LIMITED","GitHub 访问受限或达到匿名请求限额，请稍后重试");
            if(response.status()!=200)throw failure("SOURCE_UNAVAILABLE","GitHub 暂不可用，未自动重试");
            if(response.body().length>maximum)throw failure("SOURCE_TOO_LARGE","GitHub 响应超过读取上限");
            return json.readTree(response.body());
        }catch(ApiException e){throw e;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw failure("SOURCE_INVALID","GitHub 响应格式无效，未自动重试");}
        catch(Exception e){
            for(Throwable cause=e;cause!=null;cause=cause.getCause())
                if("公开资料超过大小限制".equals(cause.getMessage()))
                    throw failure("SOURCE_TOO_LARGE","GitHub 响应超过读取上限，未自动重试");
            throw failure("SOURCE_UNAVAILABLE","GitHub 读取超时或连接失败，未自动重试");
        }
    }
    public static ApiException failure(String code,String message){return new ApiException(503,code,message);}
}
