package cn.interview.service.source;

import cn.interview.client.source.GitHubCodeClient;
import cn.interview.client.source.GitMcpSearchClient;
import cn.interview.client.source.SourceToolModelClient;
import cn.interview.exception.ApiException;
import cn.interview.service.model.ModelConfigurationService;
import cn.interview.dto.ModelSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeExplorationServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final GitHubCodeClient github=mock(GitHubCodeClient.class);
    final GitMcpSearchClient mcp=mock(GitMcpSearchClient.class);
    final SourceToolModelClient model=mock(SourceToolModelClient.class);
    final ModelConfigurationService settings=mock(ModelConfigurationService.class);
    @Test void locksCommitBeforeExplorationAndKeepsVerifiedFragmentsOnRetry() throws Exception {
        when(settings.connection()).thenReturn(new ModelSettings("openai","CUSTOM","http://localhost","x","test","","",""));
        var source=json.createObjectNode().put("repository","acme/orders").put("status","PENDING");
        when(github.resolve("acme/orders")).thenReturn(json.createObjectNode().put("repository","acme/orders").put("commitSha","a".repeat(40)));
        var tree=json.readTree("{\"files\":[{\"path\":\"src/Orders.java\"}],\"partial\":false}");
        when(github.tree(any())).thenReturn((ObjectNode)tree);
        when(github.file(any(),any(),eq("src/Orders.java"))).thenReturn("class Orders {\n void save() {}\n}\n");
        doAnswer(inv->{CodeExplorationService.SourceTools tools=inv.getArgument(1);tools.readSource("src/Orders.java",1,3,"异常处理");return null;})
                .when(model).explore(any(),any(),any(),any());
        var service=new CodeExplorationService(json,github,mcp,model,settings,true);
        var saved=new java.util.ArrayList<ObjectNode>();
        service.prepare(source,json.createObjectNode(),value->saved.add(value.deepCopy()));
        assertThat(saved.getFirst().path("commitSha").asText()).isEqualTo("a".repeat(40));
        assertThat(source.path("status").asText()).isEqualTo("READY");
        assertThat(source.path("evidence").get(0).path("snippet").asText()).contains("void save()");
        assertThat(source.path("evidence").get(0).path("url").asText()).endsWith("/src/Orders.java#L1-L3");
        service.prepare(source,json.createObjectNode(),v->{});
        verify(github,times(1)).resolve("acme/orders");
        verify(model,times(1)).explore(any(),any(),any(),any());
        assertThat(CodeExplorationService.summary(source).toString()).doesNotContain("save()","\"evidence\":","异常处理");
    }
    @Test void unsupportedToolsRequireExplicitFallbackAndKeepLockedVersion() {
        when(settings.connection()).thenReturn(new ModelSettings("openai","CUSTOM","http://localhost","x","test","","",""));
        var source=json.createObjectNode().put("repository","acme/orders").put("commitSha","a".repeat(40));
        var tree=json.createObjectNode();tree.putArray("files").addObject().put("path","Orders.java");when(github.tree(any())).thenReturn(tree);
        var service=new CodeExplorationService(json,github,mcp,model,settings,true);
        assertThatThrownBy(()->service.prepare(source,json.createObjectNode(),v->{})).isInstanceOf(ApiException.class);
        assertThat(source.path("status").asText()).isEqualTo("FAILED");
        assertThat(source.path("commitSha").asText()).isEqualTo("a".repeat(40));
        CodeExplorationService.skip(source);
        service.prepare(source,json.createObjectNode(),v->{});
        assertThat(source.path("status").asText()).isEqualTo("SKIPPED");
    }
    @Test void mcpFailureUsesFixedDirectoryAndLaterModelFailureKeepsVerifiedEvidence() {
        when(settings.connection()).thenReturn(new ModelSettings("openai","CUSTOM","http://localhost","x","test","","",""));
        var source=json.createObjectNode().put("repository","acme/orders").put("commitSha","a".repeat(40));
        var tree=json.createObjectNode();tree.putArray("files").addObject().put("path","src/Orders.java");when(github.tree(any())).thenReturn(tree);
        when(github.file(any(),any(),eq("src/Orders.java"))).thenReturn("class Orders {\n void save() {}\n}");
        when(mcp.search("acme/orders","Orders")).thenReturn(new GitMcpSearchClient.Search(java.util.List.of(),"GitMCP 搜索超时，使用固定目录"));
        doAnswer(inv->{CodeExplorationService.SourceTools tools=inv.getArgument(1);
            assertThat(json.readTree(tools.searchCode("Orders")).path("paths").get(0).asText()).isEqualTo("src/Orders.java");
            tools.readSource("src/Orders.java",2,3,"异常边界");throw ApiException.model("测试模型中断");
        }).when(model).explore(any(),any(),any(),any());
        new CodeExplorationService(json,github,mcp,model,settings,true).prepare(source,json.createObjectNode(),v->{});
        assertThat(source.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(source.path("evidence").get(0).path("snippet").asText()).contains("save()");
        assertThat(source.path("warnings").toString()).contains("GitMCP 搜索超时","测试模型中断");
        assertThatThrownBy(()->CodeExplorationService.skip(source)).isInstanceOf(ApiException.class);
    }
    @Test void codeQuestionsCannotExposeSourceLinksOrCodeBlocksBeforeReview() {
        var source=json.createObjectNode();source.putArray("evidence").addObject().put("id","code-1");
        var raw=json.createObjectNode();var q=raw.putArray("questions").addObject().put("question","查看 https://github.com/acme/orders/blob/aaaa/A.java 后回答");q.putArray("codeEvidenceIds").add("code-1");
        assertThatThrownBy(()->CodeExplorationService.bindQuestions(raw,raw.withArray("questions"),source,false)).isInstanceOf(ApiException.class);
    }
    @Test void unknownEvidenceCannotBeAttachedToQuestion() {
        var snapshot=json.createObjectNode();snapshot.putArray("evidence").addObject().put("id","code-1");
        var question=json.createObjectNode();question.putArray("codeEvidenceIds").add("code-other");
        assertThatThrownBy(()->CodeExplorationService.references(question,snapshot)).isInstanceOf(ApiException.class);
    }
    @Test void rejectsSourceLeaksInMainAndBackupQuestionsButAllowsSymbolNames() {
        var source=json.createObjectNode();source.putArray("evidence").addObject().put("id","code-1")
                .put("path","src/Orders.java").put("goal","通过事务边界和重复提交分析订单一致性问题")
                .put("snippet","public void save(Order order) {\n repository.save(order);\n}");
        for(String leaked:java.util.List.of("请解释 `void save() {}` 的异常处理","请说明 src/Orders.java 的实现",
                "内部考查目标：事务边界；请作答","通过事务边界和重复提交分析订单一致性问题，请回答",
                "代码调用 repository.save(order);，请说明原因")) {
            for(boolean backup:java.util.List.of(false,true)) {
                var raw=json.createObjectNode();var q=raw.putArray("questions").addObject().put("question",backup?"请说明 Orders 的异常边界":leaked);
                if(backup)q.putArray("backups").addObject().put("question",leaked);
                assertThatThrownBy(()->CodeExplorationService.bindQuestions(raw,raw.withArray("questions"),source,false)).as(leaked).isInstanceOf(ApiException.class);
            }
        }
        var valid=json.createObjectNode();valid.putArray("questions").addObject().put("question","Orders 的 save 方法失败时，如何界定事务回滚边界？");
        assertThatCode(()->CodeExplorationService.bindQuestions(valid,valid.withArray("questions"),source,false)).doesNotThrowAnyException();
    }
    @Test void introductionAndCandidateQuestionsCannotUseSourceBackups() {
        var source=json.createObjectNode();source.putArray("evidence").addObject().put("id","code-1");
        for(String stage:java.util.List.of("INTRO","QUESTIONS")) {
            var raw=json.createObjectNode();var q=raw.putArray("questions").addObject().put("stage",stage).put("question","请继续介绍");
            q.putArray("backups").addObject().put("question","请说明项目事务边界").putArray("codeEvidenceIds").add("code-1");
            assertThatThrownBy(()->CodeExplorationService.bindQuestions(raw,raw.withArray("questions"),source,true)).as(stage).isInstanceOf(ApiException.class);
        }
    }
    @Test void unlinkedInterviewsKeepOriginalQuestionPresentation() {
        var raw=json.createObjectNode();raw.putArray("questions").addObject().put("question","假设有如下 Java 语句 `run();`，异常会如何传播？");
        assertThatCode(()->CodeExplorationService.bindQuestions(raw,raw.withArray("questions"),json.createObjectNode(),false)).doesNotThrowAnyException();
    }
    @Test void knownPythonStatementsCannotAppearInFollowupQuestions() {
        var source=json.createObjectNode();source.putArray("evidence").addObject().put("id","code-1").put("path","src/cache.py")
                .put("snippet","if cached_value:\n    return cached_value\nraise error");
        for(String statement:java.util.List.of("return cached_value","raise error")){
            var question=json.createObjectNode().put("question","请解释已核验源码中的 `"+statement+"` 会在何时执行？");
            assertThatThrownBy(()->CodeExplorationService.validateQuestionPresentation(question,source)).isInstanceOf(ApiException.class);
        }
        assertThatCode(()->CodeExplorationService.validateQuestionPresentation(json.createObjectNode().put("question","cached_value 对应的缓存失效应如何验证？"),source)).doesNotThrowAnyException();
    }
}
