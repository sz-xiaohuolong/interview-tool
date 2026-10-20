package cn.interview.client.source;

import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.dto.ModelSettings;
import cn.interview.service.source.CodeExplorationService;
import cn.interview.service.model.ModelConfigurationService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceToolModelClientTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void threeFileHeadersDoNotPreventReadingActualImplementations() throws Exception {
        var requests=new ArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));int round=requests.size();
            var envelope=json.createObjectNode().put("id","source-depth").put("object","chat.completion").put("model","test");
            var choice=envelope.putArray("choices").addObject().put("index",0).put("finish_reason",round<3?"tool_calls":"stop");
            var message=choice.putObject("message").put("role","assistant");
            if(round<3){
                message.putNull("content");var calls=message.putArray("tool_calls");
                for(String path:round==1?List.of("Orders.java","Billing.java","Stock.java"):List.of("Orders.java")){
                    var arguments=json.createObjectNode().put("path",path).put("startLine",round==1?1:3).put("endLine",round==1?2:5).put("goal","事务边界");
                    calls.addObject().put("id","call-"+round+path).put("type","function").putObject("function").put("name","readSource").put("arguments",arguments.toString());
                }
            }else message.put("content","已读取实际实现");
            byte[] bytes=json.writeValueAsBytes(envelope);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var settings=new ModelSettings("openai","CUSTOM","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-only","model","","","");
            var configured=mock(ModelConfigurationService.class);when(configured.connection()).thenReturn(settings);
            var github=mock(GitHubCodeClient.class);var tree=json.createObjectNode();var files=tree.putArray("files");
            for(String path:List.of("Orders.java","Billing.java","Stock.java")){
                files.addObject().put("path",path);
                when(github.file(any(),any(),eq(path))).thenReturn("// 文件头\nclass Orders {\n public void save(Order order) {\n  repository.save(order);\n }\n}");
            }
            when(github.tree(any())).thenReturn(tree);
            var source=json.createObjectNode().put("repository","acme/orders").put("commitSha","a".repeat(40));
            var service=new CodeExplorationService(json,github,mock(GitMcpSearchClient.class),new SourceToolModelClient(new OpenAiJsonClient(json)),configured,true);
            service.prepare(source,json.createObjectNode(),v->{});
            assertThat(requests).hasSize(3);
            assertThat(requests.get(1).path("messages").toString()).contains("declarations","save(Order order)","line\\\":3");
            assertThat(source.path("evidence").findValuesAsText("snippet")).anyMatch(s->s.contains("repository.save(order)"));
        }finally{server.stop(0);}
    }
    @Test void sendsNativeToolsAndRegistersOnlyActualReadResults()throws Exception {
        var requests=new ArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(json.readTree(exchange.getRequestBody()));
            var envelope=json.createObjectNode().put("id","source-test").put("object","chat.completion").put("model","test");
            var choice=envelope.putArray("choices").addObject().put("index",0).put("finish_reason",requests.size()==1?"tool_calls":"stop");
            var message=choice.putObject("message").put("role","assistant");
            if(requests.size()==1){
                message.putNull("content");message.putArray("tool_calls").addObject().put("id","call-1").put("type","function").putObject("function").put("name","readSource")
                    .put("arguments","{\"path\":\"Orders.java\",\"startLine\":1,\"endLine\":3,\"goal\":\"异常处理\"}");
            }else message.put("content","已完成源码读取");
            envelope.putObject("usage").put("prompt_tokens",12).put("completion_tokens",7).put("total_tokens",19);
            byte[] bytes=json.writeValueAsBytes(envelope);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var settings=new ModelSettings("openai","CUSTOM","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-only","model","","","");
            var configured=mock(ModelConfigurationService.class);when(configured.connection()).thenReturn(settings);
            var github=mock(GitHubCodeClient.class);var tree=json.createObjectNode();tree.putArray("files").addObject().put("path","Orders.java");when(github.tree(any())).thenReturn(tree);
            when(github.file(any(),any(),eq("Orders.java"))).thenReturn("class Orders {\n void save() {}\n}");
            var source=json.createObjectNode().put("repository","acme/orders").put("commitSha","a".repeat(40));
            var service=new CodeExplorationService(json,github,mock(GitMcpSearchClient.class),new SourceToolModelClient(new OpenAiJsonClient(json)),configured,true);
            service.prepare(source,json.createObjectNode().put("role","Java 后端"),v->{});
            assertThat(source.path("status").asText()).isEqualTo("READY");
            assertThat(source.path("evidence").get(0).path("path").asText()).isEqualTo("Orders.java");
            assertThat(requests).hasSize(2);
            assertThat(requests.get(0).has("response_format")).isFalse();
            assertThat(requests.get(0).path("tools").size()).isEqualTo(3);
            assertThat(requests.get(1).path("messages").toString()).contains("tool_call_id","call-1","void save()");
            assertThat(source.path("modelUsage").findValues("promptTokens")).hasSize(2);
        }finally{server.stop(0);}
    }
}
