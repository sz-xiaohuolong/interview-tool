package cn.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class V2HttpBoundaryTest {
    @TempDir Path root;
    @Test void realHttpAdapterRejectsOversizeAndTruncatedOutputWithoutMockFallback()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var response=new java.util.concurrent.atomic.AtomicReference<String>("x".repeat(2_000_001));
        server.createContext("/v1/chat/completions",e->{byte[] body=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}catch(java.io.IOException ignored){}});server.start();
        try{
            var json=new ObjectMapper();var store=new Store(root.toString(),json);var db=new V2Store(store,new MockEnvironment().withProperty("app.v2.enabled","true"));var base=new LlmClient(json,"openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-key","synthetic-model",3,"high");var llm=new V2LlmClient(base,db);
            assertThatThrownBy(()->llm.call(V2Task.PRACTICE_EVALUATOR,db.object(),llm.protocol(V2Task.PRACTICE_EVALUATOR))).isInstanceOf(V2Exception.class);
            response.set(json.writeValueAsString(Map.of("choices",new Object[]{Map.of("finish_reason","length","message",Map.of("content","{}"))})));
            assertThatThrownBy(()->llm.call(V2Task.PRACTICE_EVALUATOR,db.object(),llm.protocol(V2Task.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(V2Exception.class,e->assertThat(e.code).isEqualTo("MODEL_INCOMPLETE_OUTPUT"));
            response.set(json.writeValueAsString(Map.of("choices",new Object[]{Map.of("finish_reason","stop","message",Map.of("content","{} garbage"))})));
            assertThatThrownBy(()->llm.call(V2Task.PRACTICE_EVALUATOR,db.object(),llm.protocol(V2Task.PRACTICE_EVALUATOR))).isInstanceOf(V2Exception.class);
        }finally{server.stop(0);}
    }
    @Test void missingKeyRateLimitAndTimeoutRemainExplicitFailures()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var delayed=new java.util.concurrent.atomic.AtomicBoolean(false);var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions",e->{requests.incrementAndGet();if(delayed.get())try{Thread.sleep(1600);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}try{e.sendResponseHeaders(429,-1);}catch(java.io.IOException ignored){}finally{e.close();}});server.start();
        try{
            var json=new ObjectMapper();var db=new V2Store(new Store(root.toString(),json),new MockEnvironment().withProperty("app.v2.enabled","true"));String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            var missing=new V2LlmClient(new LlmClient(json,"openai",url,"","synthetic-model",1,"high"),db);
            assertThatThrownBy(()->missing.call(V2Task.PRACTICE_EVALUATOR,db.object(),missing.protocol(V2Task.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(V2Exception.class,e->assertThat(e.code).isEqualTo("MODEL_NOT_CONFIGURED"));assertThat(requests).hasValue(0);
            var llm=new V2LlmClient(new LlmClient(json,"openai",url,"synthetic-key","synthetic-model",1,"high"),db);
            assertThatThrownBy(()->llm.call(V2Task.PRACTICE_EVALUATOR,db.object(),llm.protocol(V2Task.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(V2Exception.class,e->assertThat(e.code).isEqualTo("MODEL_RATE_LIMITED"));
            delayed.set(true);assertThatThrownBy(()->llm.call(V2Task.PRACTICE_EVALUATOR,db.object(),llm.protocol(V2Task.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(V2Exception.class,e->assertThat(e.code).isIn("MODEL_TIMEOUT","MODEL_INVALID_OUTPUT"));
        }finally{server.stop(0);}
    }
}
