package cn.interview.client.transport;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FixedHttpTransportTest {
    @SuppressWarnings("unchecked")
    @Test void 大小超限先保留准确原因再取消HTTP响应流() throws Exception {
        // 模拟HTTP/2取消订阅后同步上报IOException，复现原错误原因被覆盖。
        var type=Class.forName("cn.interview.client.transport.FixedHttpTransport$BoundedBody");
        var constructor=type.getDeclaredConstructor(int.class);constructor.setAccessible(true);
        var subscriber=(HttpResponse.BodySubscriber<byte[]>)constructor.newInstance(4);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription(){
            public void request(long count) {}
            public void cancel(){cancelled.set(true);subscriber.onError(new IOException("Stream 1 cancelled"));}
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        assertThat(cancelled).isTrue();
        assertThatThrownBy(()->subscriber.getBody().toCompletableFuture().join())
            .hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("公开资料超过大小限制");
    }
}
