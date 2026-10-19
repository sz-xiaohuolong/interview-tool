package cn.interview.client.training;

import cn.interview.client.interview.FocusedInterviewModelClient;
import cn.interview.client.transport.OpenAiJsonClient;
import cn.interview.exception.RecoveryException;
import cn.interview.mapper.SqliteStore;
import cn.interview.mapper.TrainingStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class TrainingHttpBoundaryTest {
    @TempDir Path root;
    @Test void realHttpAdapterRejectsOversizeAndTruncatedOutputWithoutMockFallback()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var response=new java.util.concurrent.atomic.AtomicReference<String>("x".repeat(2_000_001));
        server.createContext("/v1/chat/completions",e->{byte[] body=response.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);try(var out=e.getResponseBody()){out.write(body);}catch(java.io.IOException ignored){}});server.start();
        try{
            var json=new ObjectMapper();var store=new SqliteStore(root.toString(),json);var db=new TrainingStore(store,new MockEnvironment().withProperty("app.training.enabled","true"));var base=new FocusedInterviewModelClient(json,"openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-key","synthetic-model",3,"high");var llm=new TrainingModelClient(base::connection,db,new OpenAiJsonClient(json),3);
            assertThatThrownBy(()->llm.call(ModelTask.PRACTICE_EVALUATOR,db.object(),llm.protocol(ModelTask.PRACTICE_EVALUATOR))).isInstanceOf(RecoveryException.class);
            response.set(json.writeValueAsString(Map.of("model","returned-synthetic-model","choices",new Object[]{Map.of("finish_reason","stop","message",Map.of("content","{}"))})));
            var protocol=llm.protocol(ModelTask.PRACTICE_EVALUATOR);
            assertThat(llm.call(ModelTask.PRACTICE_EVALUATOR,db.object(),protocol)).isEmpty();
            assertThat(protocol.path("returnedModel").asText()).isEqualTo("returned-synthetic-model");
            response.set(json.writeValueAsString(Map.of("choices",new Object[]{Map.of("finish_reason","length","message",Map.of("content","{}"))})));
            assertThatThrownBy(()->llm.call(ModelTask.PRACTICE_EVALUATOR,db.object(),llm.protocol(ModelTask.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(RecoveryException.class,e->assertThat(e.code).isEqualTo("MODEL_INCOMPLETE_OUTPUT"));
            response.set(json.writeValueAsString(Map.of("choices",new Object[]{Map.of("finish_reason","stop","message",Map.of("content","{} garbage"))})));
            assertThatThrownBy(()->llm.call(ModelTask.PRACTICE_EVALUATOR,db.object(),llm.protocol(ModelTask.PRACTICE_EVALUATOR))).isInstanceOf(RecoveryException.class);
        }finally{server.stop(0);}
    }
    @Test void missingKeyRateLimitAndTimeoutRemainExplicitFailures()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var delayed=new java.util.concurrent.atomic.AtomicBoolean(false);var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/chat/completions",e->{requests.incrementAndGet();if(delayed.get())try{Thread.sleep(1600);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}try{e.sendResponseHeaders(429,-1);}catch(java.io.IOException ignored){}finally{e.close();}});server.start();
        try{
            var json=new ObjectMapper();var db=new TrainingStore(new SqliteStore(root.toString(),json),new MockEnvironment().withProperty("app.training.enabled","true"));String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            var missing=new TrainingModelClient(() -> new cn.interview.dto.ModelSettings("openai","CUSTOM",url,"","synthetic-model","","high",""),db,new OpenAiJsonClient(json),1);
            assertThatThrownBy(()->missing.call(ModelTask.PRACTICE_EVALUATOR,db.object(),missing.protocol(ModelTask.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(RecoveryException.class,e->assertThat(e.code).isEqualTo("MODEL_NOT_CONFIGURED"));assertThat(requests).hasValue(0);
            var llm=new TrainingModelClient(() -> new cn.interview.dto.ModelSettings("openai","CUSTOM",url,"synthetic-key","synthetic-model","","high",""),db,new OpenAiJsonClient(json),1);
            assertThatThrownBy(()->llm.call(ModelTask.PRACTICE_EVALUATOR,db.object(),llm.protocol(ModelTask.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(RecoveryException.class,e->assertThat(e.code).isEqualTo("MODEL_RATE_LIMITED"));
            delayed.set(true);assertThatThrownBy(()->llm.call(ModelTask.PRACTICE_EVALUATOR,db.object(),llm.protocol(ModelTask.PRACTICE_EVALUATOR))).isInstanceOfSatisfying(RecoveryException.class,e->assertThat(e.code).isIn("MODEL_TIMEOUT","MODEL_INVALID_OUTPUT"));
        }finally{server.stop(0);}
    }
}
