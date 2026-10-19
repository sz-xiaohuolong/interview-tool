package cn.interview.client;

import java.net.URI;
import java.time.Duration;

/** 公开知识文档的受限获取接口。 */
public interface KnowledgeTransport {
    record Payload(int status, String contentType, byte[] body) {}
    Payload fetch(URI uri, Duration timeout, int maximumBytes) throws Exception;
}
