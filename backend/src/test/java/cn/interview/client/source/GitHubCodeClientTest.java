package cn.interview.client.source;

import cn.interview.client.transport.KnowledgeTransport;
import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GitHubCodeClientTest {
    final ObjectMapper json=new ObjectMapper();
    @TempDir Path cache;
    @Test void rejectsForeignAddressesAndSensitivePaths() {
        for(String url:List.of("http://github.com/a/b","https://github.com.evil/a/b","https://github.com/a/b/tree/main","https://github.com/a/b?x=1","https://github.com/a/.."))
            assertThatThrownBy(()->GitHubCodeClient.repository(url)).isInstanceOf(ApiException.class);
        for(String path:List.of(".env","src/../config.java","node_modules/x.ts","src/secrets.java","src/Password.txt","src/%2e%2e/a.java",
                "secrets/config.ts","credentials/data.sql","src/keystore/Keys.java","private/config.ts"))assertThat(GitHubCodeClient.allowed(path)).as(path).isFalse();
        assertThat(GitHubCodeClient.repository("github.com/spring-projects/spring-petclinic.git")).isEqualTo("spring-projects/spring-petclinic");
    }
    @Test void verifiesBlobAndSeparatesRepositoryAndCommitCache() throws Exception {
        byte[] source="class Orders {\n  void save() {}\n}\n".getBytes(StandardCharsets.UTF_8);
        var digest=java.security.MessageDigest.getInstance("SHA-1");digest.update(("blob "+source.length+"\0").getBytes(StandardCharsets.UTF_8));
        String blob=HexFormat.of().formatHex(digest.digest(source)),sha="a".repeat(40),sha2="b".repeat(40);
        List<String> requests=new ArrayList<>();
        KnowledgeTransport transport=(uri,timeout,max)->{
            requests.add(uri.toString());String path=uri.getPath();
            if(path.equals("/repos/acme/orders"))return payload("{\"private\":false,\"full_name\":\"acme/orders\",\"default_branch\":\"main\"}");
            if(path.equals("/repos/acme/orders/branches/main"))return payload("{\"commit\":{\"sha\":\""+sha+"\"}}");
            if(path.equals("/repos/acme/orders/git/trees/"+sha))return payload("{\"tree\":[{\"path\":\"src/Orders.java\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\""+blob+"\",\"size\":"+source.length+"}]}");
            if(path.matches("/repos/acme/(orders|billing)/git/blobs/"+blob))return payload("{\"sha\":\""+blob+"\",\"encoding\":\"base64\",\"content\":\""+Base64.getEncoder().encodeToString(source)+"\"}");
            throw new AssertionError("未知请求："+uri);
        };
        var client=new GitHubCodeClient(json,transport,cache);var snapshot=client.resolve("acme/orders");var tree=client.tree(snapshot);
        assertThat(client.file(snapshot,tree,"src/Orders.java")).contains("void save()");
        client.file(snapshot,tree,"src/Orders.java");
        var cached=java.nio.file.Files.walk(cache).filter(java.nio.file.Files::isRegularFile).findFirst().orElseThrow();
        java.nio.file.Files.writeString(cached,"伪造的缓存内容");
        assertThatThrownBy(()->client.file(snapshot,tree,"src/Orders.java")).isInstanceOf(ApiException.class).hasMessageContaining("摘要");
        assertThat(java.nio.file.Files.exists(cached)).isFalse();
        assertThat(requests.stream().filter(p->p.contains("/blobs/")).count()).isEqualTo(1);
        client.file(snapshot.deepCopy().put("commitSha",sha2),tree,"src/Orders.java");
        client.file(snapshot.deepCopy().put("repository","acme/billing"),tree,"src/Orders.java");
        assertThat(requests.stream().filter(p->p.contains("/blobs/")).count()).isEqualTo(3);
        assertThatThrownBy(()->client.file(snapshot,tree,"src/Other.java")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsForgedContentAndReportsRateLimits() throws Exception {
        var snapshot=json.createObjectNode().put("repository","acme/orders").put("commitSha","a".repeat(40));
        var tree=json.readTree("{\"files\":[{\"path\":\"A.java\",\"blobSha\":\""+"b".repeat(40)+"\",\"size\":1}]}");
        var client=new GitHubCodeClient(json,(u,t,m)->payload("{\"sha\":\""+"b".repeat(40)+"\",\"encoding\":\"base64\",\"content\":\"eA==\"}"),cache);
        assertThatThrownBy(()->client.file(snapshot,tree,"A.java")).isInstanceOf(ApiException.class).hasMessageContaining("摘要");
        var limited=new GitHubCodeClient(json,(u,t,m)->new KnowledgeTransport.Payload(429,"application/json",new byte[0]),cache);
        assertThatThrownBy(()->limited.resolve("acme/orders")).isInstanceOf(ApiException.class).hasMessageContaining("限额");
    }
    @Test void unavailablePrivateAndTimedOutRepositoriesNeverBecomeReadableBindings() {
        var missing=new GitHubCodeClient(json,(u,t,m)->new KnowledgeTransport.Payload(404,"application/json",new byte[0]),cache);
        assertThatThrownBy(()->missing.resolve("acme/orders")).isInstanceOf(ApiException.class).hasMessageContaining("不是公开仓库");
        var hidden=new GitHubCodeClient(json,(u,t,m)->payload("{\"private\":true,\"full_name\":\"acme/orders\",\"default_branch\":\"main\"}"),cache);
        assertThatThrownBy(()->hidden.resolve("acme/orders")).isInstanceOf(ApiException.class).hasMessageContaining("不是可核验");
        var timeout=new GitHubCodeClient(json,(u,t,m)->{throw new java.net.http.HttpTimeoutException("测试超时");},cache);
        assertThatThrownBy(()->timeout.resolve("acme/orders")).isInstanceOf(ApiException.class).hasMessageContaining("超时");
    }
    @Test void 默认分支版本查询不读取完整提交变更且保留分支转义() {
        String sha="c".repeat(40);
        var requests=new ArrayList<String>();
        var client=new GitHubCodeClient(json,(uri,timeout,max)->{
            requests.add(uri.getRawPath());
            if(uri.getRawPath().equals("/repos/acme/orders"))return payload("{\"private\":false,\"full_name\":\"acme/orders\",\"default_branch\":\"release/java21\"}");
            if(uri.getRawPath().equals("/repos/acme/orders/branches/release%2Fjava21")) {
                assertThat(max).isEqualTo(256*1024);
                return payload("{\"name\":\"release/java21\",\"commit\":{\"sha\":\""+sha+"\"}}");
            }
            throw new AssertionError("不得请求完整提交或未知接口："+uri);
        },cache);
        var snapshot=client.resolve("acme/orders");
        assertThat(snapshot.path("commitSha").asText()).isEqualTo(sha);
        assertThat(snapshot.path("branch").asText()).isEqualTo("release/java21");
        assertThat(requests).containsExactly("/repos/acme/orders","/repos/acme/orders/branches/release%2Fjava21");
    }

    @Test void 响应超过上限与损坏JSON不能误报为连接超时() {
        var tooLarge=new GitHubCodeClient(json,(uri,timeout,max)->{
            throw new java.util.concurrent.ExecutionException(new IllegalStateException("公开资料超过大小限制"));
        },cache);
        assertThatThrownBy(()->tooLarge.resolve("acme/orders")).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.code).isEqualTo("SOURCE_TOO_LARGE");
            assertThat(error.getMessage()).contains("读取上限").doesNotContain("超时");
        });
        var invalid=new GitHubCodeClient(json,(uri,timeout,max)->payload("不是JSON"),cache);
        assertThatThrownBy(()->invalid.resolve("acme/orders")).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.code).isEqualTo("SOURCE_INVALID");
            assertThat(error.getMessage()).contains("格式").doesNotContain("超时");
        });
    }

    KnowledgeTransport.Payload payload(String text){return new KnowledgeTransport.Payload(200,"application/json",text.getBytes(StandardCharsets.UTF_8));}
}
