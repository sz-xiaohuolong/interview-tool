package cn.interview.client.jobsearch;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** CDP 消息仅在内存内路由；任何协议原文都不得进入日志或用户错误。 */
final class CdpBrowser implements AutoCloseable {
    private final ObjectMapper json;
    private final WebSocket socket;
    private final AtomicLong sequence=new AtomicLong();
    private final Map<Long,CompletableFuture<JsonNode>> pending=new ConcurrentHashMap<>();
    private final Map<String,Consumer<JsonNode>> listeners=new ConcurrentHashMap<>();
    private final Set<String> owned=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private CompletableFuture<?> outbound=CompletableFuture.completedFuture(null);

    static URI endpoint(Path file){
        try{
            if(!Files.isRegularFile(file)||Files.size(file)>2048)throw new IllegalArgumentException();
            List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);
            if(lines.size()<2||!lines.getFirst().matches("[0-9]{1,5}")||!lines.get(1).matches("/devtools/browser/[A-Za-z0-9-]+"))throw new IllegalArgumentException();
            int port=Integer.parseInt(lines.getFirst());if(port<1||port>65535)throw new IllegalArgumentException();
            return URI.create("ws://127.0.0.1:"+port+lines.get(1));
        }catch(Exception e){throw new ApiException(503,"ACCESS_UNAVAILABLE","本机 Chrome 访问配置不可用，请由开发者检查浏览器会话");}
    }
    CdpBrowser(Path activePort,ObjectMapper json){
        this.json=json;
        try{socket=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(endpoint(activePort),new Listener()).get(8,TimeUnit.SECONDS);}
        catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ApiException(503,"ACCESS_UNAVAILABLE","无法连接本机 Chrome，请由开发者检查浏览器访问配置");}
    }
    Page page(){
        String target=command(null,"Target.createTarget",Map.of("url","about:blank","background",true)).path("targetId").asText();
        if(target.isBlank())throw failure("SOURCE_NETWORK");owned.add(target);
        try{
            String session=command(null,"Target.attachToTarget",Map.of("targetId",target,"flatten",true)).path("sessionId").asText();
            if(session.isBlank())throw failure("SOURCE_NETWORK");Page page=new Page(target,session);page.command("Page.enable",Map.of());page.command("Runtime.enable",Map.of());return page;
        }catch(RuntimeException e){closeTarget(target);throw e;}
    }
    JsonNode command(String session,String method,Map<String,?> params){
        if(closed)throw failure("SOURCE_NETWORK");long id=sequence.incrementAndGet();var response=new CompletableFuture<JsonNode>();pending.put(id,response);
        try{send(message(id,session,method,params)).get(5,TimeUnit.SECONDS);JsonNode result=response.get(25,TimeUnit.SECONDS);if(result.has("error"))throw failure("SOURCE_CHANGED");return result.path("result");}
        catch(TimeoutException e){throw failure("SOURCE_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw failure("SOURCE_TIMEOUT");}
        catch(ApiException e){throw e;}catch(Exception e){throw failure("SOURCE_NETWORK");}finally{pending.remove(id);}
    }
    private ObjectNode message(long id,String session,String method,Map<String,?> params){ObjectNode n=json.createObjectNode().put("id",id).put("method",method);n.set("params",json.valueToTree(params));if(session!=null)n.put("sessionId",session);return n;}
    private synchronized CompletableFuture<WebSocket> send(ObjectNode message){CompletableFuture<WebSocket> sent=outbound.handle((v,e)->null).thenCompose(v->socket.sendText(message.toString(),true));outbound=sent;return sent;}
    private CompletableFuture<WebSocket> fire(String session,String method,Map<String,?> params){if(closed)return CompletableFuture.completedFuture(socket);return send(message(sequence.incrementAndGet(),session,method,params));}
    private void closeTarget(String target){if(owned.remove(target))fire(null,"Target.closeTarget",Map.of("targetId",target));}
    static ApiException failure(String code){return switch(code){case "SOURCE_TIMEOUT"->new ApiException(504,code,"招聘来源读取超时，请稍后重试");case "SOURCE_CHANGED"->new ApiException(502,code,"招聘来源页面结构发生变化，请由开发者检查");default->new ApiException(502,"SOURCE_NETWORK","本机浏览器连接中断，请稍后重试");};}
    @Override public void close(){
        if(closed)return;var sends=new ArrayList<CompletableFuture<WebSocket>>();for(String target:List.copyOf(owned))if(owned.remove(target))sends.add(fire(null,"Target.closeTarget",Map.of("targetId",target)));
        closed=true;listeners.clear();pending.values().forEach(f->f.completeExceptionally(new IllegalStateException("已关闭")));pending.clear();
        CompletableFuture<?> last; synchronized(this){last=outbound;}last.orTimeout(2,TimeUnit.SECONDS).whenComplete((v,e)->socket.abort());
    }
    final class Page implements AutoCloseable {
        private final String target,session;private volatile boolean disposed;
        Page(String target,String session){this.target=target;this.session=session;}
        JsonNode command(String method,Map<String,?> params){if(disposed)throw failure("SOURCE_NETWORK");return CdpBrowser.this.command(session,method,params);}
        void fire(String method,Map<String,?> params){if(!disposed)CdpBrowser.this.fire(session,method,params);}
        void events(Consumer<JsonNode> listener){listeners.put(session,listener);}
        JsonNode evaluate(String expression){JsonNode result=command("Runtime.evaluate",Map.of("expression",expression,"awaitPromise",true,"returnByValue",true,"timeout",23000));if(result.has("exceptionDetails"))throw failure("SOURCE_CHANGED");return result.path("result").path("value");}
        void navigate(String url){command("Page.navigate",Map.of("url",url));}
        void foreground(){command("Page.bringToFront",Map.of());}
        @Override public void close(){if(disposed)return;disposed=true;listeners.remove(session);closeTarget(target);}
    }
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence part,boolean last){
            if(text.length()+part.length()>16*1024*1024){text.setLength(0);ws.abort();return null;}text.append(part);
            if(last){try{JsonNode n=json.readTree(text.toString());if(n.has("id")){var f=pending.get(n.path("id").asLong());if(f!=null)f.complete(n);}else{Consumer<JsonNode> handler=listeners.get(n.path("sessionId").asText());if(handler!=null)handler.accept(n);}}catch(Exception ignored){}finally{text.setLength(0);}}
            ws.request(1);return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws,int status,String reason){pending.values().forEach(f->f.completeExceptionally(new IllegalStateException("连接关闭")));return null;}
        @Override public void onError(WebSocket ws,Throwable error){pending.values().forEach(f->f.completeExceptionally(new IllegalStateException("连接失败")));}
    }
}
