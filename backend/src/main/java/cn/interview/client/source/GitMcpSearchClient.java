package cn.interview.client.source;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 只取限定仓库的文件路径线索，忽略服务端指令与通用抓取工具。 */
@Component
public class GitMcpSearchClient {
    public record Search(List<String> paths,String warning) {}
    public Search search(String repository,String query) {
        GitHubCodeClient.repository("https://github.com/"+repository);
        if(query==null||!query.matches("[\\p{L}\\p{N}_.$ /-]{1,120}"))return new Search(List.of(),"搜索词超出允许范围，使用固定版本目录检索");
        var transport=HttpClientStreamableHttpTransport.builder("https://gitmcp.io").endpoint("/"+repository)
                .connectTimeout(Duration.ofSeconds(3)).build();
        try(var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(6)).initializationTimeout(Duration.ofSeconds(6)).build()) {
            client.initialize();
            var tools=client.listTools().tools();
            if(tools.size()>20)throw new IllegalStateException();
            var selected=tools.stream().filter(t->t.name().matches("search_[A-Za-z0-9_]+_code")).toList();
            if(selected.size()!=1)throw new IllegalStateException();
            var result=client.callTool(new McpSchema.CallToolRequest(selected.getFirst().name(),Map.of("query",query)));
            if(Boolean.TRUE.equals(result.isError()))throw new IllegalStateException();
            var paths=new LinkedHashSet<String>();int bytes=0;
            for(var content:result.content())if(content instanceof McpSchema.TextContent text){
                bytes+=text.text().length();if(bytes>24000)throw new IllegalStateException();
                var matches=Pattern.compile("(?m)^- \\*\\*Path\\*\\*: (.+)$").matcher(text.text());
                while(matches.find()&&paths.size()<30){String path=matches.group(1).strip();if(GitHubCodeClient.allowed(path))paths.add(path);}
            }
            return new Search(List.copyOf(paths),paths.isEmpty()?"GitMCP 未找到路径，使用本场固定版本目录检索":null);
        }catch(Exception e){return new Search(List.of(),"GitMCP 搜索不可用或超时，已改用本场固定版本目录检索");}
    }
}
