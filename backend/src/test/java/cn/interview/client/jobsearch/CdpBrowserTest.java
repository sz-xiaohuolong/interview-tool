package cn.interview.client.jobsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 严格的合成 CDP WebSocket 服务，只实现本测试列出的协议。 */
class CdpBrowserTest {
    @TempDir Path root;
    @Test void directBrowserSocketRoutesFlattenedSessionAndClosesOnlyOwnedTarget() throws Exception {
        var json=new ObjectMapper();var requests=new CopyOnWriteArrayList<JsonNode>();var done=new CountDownLatch(1);var errors=new CopyOnWriteArrayList<Throwable>();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            var thread=Thread.ofPlatform().daemon().start(()->{try(Socket peer=server.accept()){
                peer.setSoTimeout(5000);var in=peer.getInputStream();var out=peer.getOutputStream();String header=header(in);
                if(!header.startsWith("GET /devtools/browser/synthetic-browser HTTP/1.1"))throw new AssertionError(header.split("\\r?\\n")[0]);
                String key=Arrays.stream(header.split("\\r\\n")).filter(x->x.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":",2)[1].strip();
                String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));out.flush();
                while(true){String frame=readFrame(in);if(frame==null)break;JsonNode command=json.readTree(frame);requests.add(command);String method=command.path("method").asText();var result=json.createObjectNode();switch(method){
                    case "Target.createTarget"->{assertThat(command.path("params").path("url").asText()).isEqualTo("about:blank");result.put("targetId","owned-target");}
                    case "Target.attachToTarget"->{assertThat(command.path("params").path("flatten").asBoolean()).isTrue();result.put("sessionId","owned-session");}
                    case "Page.enable","Runtime.enable"->assertThat(command.path("sessionId").asText()).isEqualTo("owned-session");
                    case "Runtime.evaluate"->{assertThat(command.path("sessionId").asText()).isEqualTo("owned-session");result.set("result",json.createObjectNode().put("type","string").put("value","合成主页面"));}
                    case "Target.closeTarget"->{assertThat(command.path("params").path("targetId").asText()).isEqualTo("owned-target");result.put("success",true);}
                    default->throw new AssertionError("未知 CDP 方法："+method);
                }
                writeFrame(out,json.createObjectNode().put("id",command.path("id").asLong()).set("result",result).toString());if(method.equals("Target.closeTarget")){done.countDown();break;}
                }
            }catch(Throwable e){errors.add(e);done.countDown();}});
            Path active=root.resolve("DevToolsActivePort");Files.writeString(active,server.getLocalPort()+"\n/devtools/browser/synthetic-browser\n");
            try(CdpBrowser browser=new CdpBrowser(active,json)){var page=browser.page();assertThat(page.evaluate("document.title").asText()).isEqualTo("合成主页面");page.close();}
            assertThat(done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(errors).isEmpty();assertThat(requests).extracting(n->n.path("method").asText()).containsExactly("Target.createTarget","Target.attachToTarget","Page.enable","Runtime.enable","Runtime.evaluate","Target.closeTarget");thread.join(1000);
        }
    }
    @Test void activePortCannotEscapeLoopbackOrBrowserPath() throws Exception {
        Path active=root.resolve("active");for(String value:new String[]{"0\n/devtools/browser/id","65536\n/devtools/browser/id","9222\nws://evil/secret","9222\n/devtools/browser/id?token=secret","9222\n/devtools/page/id"}){Files.writeString(active,value);assertThatThrownBy(()->CdpBrowser.endpoint(active)).hasMessageContaining("配置不可用").hasMessageNotContaining("secret");}
    }
    static String header(InputStream in)throws Exception{var b=new ByteArrayOutputStream();while(b.size()<10000){int c=in.read();if(c<0)throw new EOFException();b.write(c);if(b.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n"))return b.toString(StandardCharsets.ISO_8859_1);}throw new IOException("请求头过长");}
    static String readFrame(InputStream in)throws Exception{int first=in.read();if(first<0)return null;int second=in.read();int length=second&127;if(length==126)length=(in.read()<<8)|in.read();else if(length==127){long size=0;for(int i=0;i<8;i++)size=(size<<8)|in.read();if(size>100000)throw new IOException("消息过长");length=(int)size;}byte[] mask=(second&128)!=0?in.readNBytes(4):null;byte[] body=in.readNBytes(length);if(mask!=null)for(int i=0;i<body.length;i++)body[i]^=mask[i%4];if((first&15)==8)return null;return new String(body,StandardCharsets.UTF_8);}
    static void writeFrame(OutputStream out,String text)throws Exception{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.write(0x81);if(bytes.length<126)out.write(bytes.length);else{out.write(126);out.write(bytes.length>>8);out.write(bytes.length&255);}out.write(bytes);out.flush();}
}
