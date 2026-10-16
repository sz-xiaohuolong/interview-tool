package cn.interview.client;

import cn.interview.exception.V2Exception;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
public class V2ModelCapacity {
    private final Semaphore semaphore=new Semaphore(1);
    public Lease acquire() {
        if(!semaphore.tryAcquire()) throw new V2Exception(503,"MODEL_CAPACITY_BUSY","另一个 V2 模型操作正在执行，草稿已保留，请稍后再提交。","RETRY_REQUEST").detail("retryable",true);
        return new Lease();
    }
    public final class Lease implements AutoCloseable {
        private boolean released;
        @Override public void close() { if(!released) {released=true;semaphore.release();} }
    }
}
