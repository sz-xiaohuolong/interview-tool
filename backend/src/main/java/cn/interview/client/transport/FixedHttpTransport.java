package cn.interview.client.transport;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** 固定公开域名、禁止重定向，并对响应正文大小及完整请求时间设置上限。 */
public final class FixedHttpTransport implements KnowledgeTransport {
  private static final Set<String> HOSTS =
      Set.of(
          "docs.oracle.com",
          "docs.spring.io",
          "dev.mysql.com",
          "raw.githubusercontent.com",
          "modelcontextprotocol.io",
          "docs.langchain.com",
          "platform.claude.com",
          "learn.microsoft.com",
          "www.anthropic.com",
          "xiaolincoding.com");
  private final HttpClient client =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(3))
          .build();
  private final boolean github;

  public FixedHttpTransport() { this(false); }
  private FixedHttpTransport(boolean github) { this.github = github; }
  public static FixedHttpTransport github() { return new FixedHttpTransport(true); }

  public static void validatePublicUrl(URI uri) {
    if (!"https".equals(uri.getScheme())
        || !HOSTS.contains(uri.getHost())
        || uri.getRawUserInfo() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("来源必须是受控公开 HTTPS 地址");
    }
  }

  @Override
  public KnowledgeTransport.Payload fetch(URI uri, Duration timeout, int maximum) throws Exception {
    if (github) {
      if (!"https".equals(uri.getScheme()) || !"api.github.com".equals(uri.getHost())
          || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
          || !uri.getRawPath().startsWith("/repos/")
          || (uri.getRawQuery() != null && !"recursive=1".equals(uri.getRawQuery())))
        throw new IllegalArgumentException("仅允许受限 GitHub 仓库读取");
    } else validatePublicUrl(uri);
    for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
      byte[] bytes = address.getAddress();
      boolean privateV6 = bytes.length == 16 && ((bytes[0] & 0xfe) == 0xfc);
      if (address.isAnyLocalAddress()
          || address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isMulticastAddress()
          || privateV6) {
        throw new IllegalArgumentException("来源解析到非公开地址");
      }
    }
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", "InterviewCoach-V3/3.0 (public-document-retrieval)")
            .header("Accept", github ? "application/vnd.github+json" : "text/html,text/plain,text/markdown;q=0.9")
            .header("Accept-Encoding", "identity")
            .GET()
            .build();
    var body = new BoundedBody(maximum);
    var pending = client.sendAsync(request, response -> body);
    try {
      var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return new KnowledgeTransport.Payload(
          response.statusCode(),
          response.headers().firstValue("content-type").orElse(""),
          response.body());
    } catch (Exception error) {
      // HTTP/2取消可能将原始大小错误包装成IOException，仍须保留准确原因。
      if (body.exceeded) throw new IllegalStateException("公开资料超过大小限制", error);
      throw error;
    } finally {
      if (!pending.isDone()) pending.cancel(true);
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> completion = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private volatile boolean exceeded;

    BoundedBody(int maximum) {
      this.maximum = maximum;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return completion;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if ((long) output.size() + buffer.remaining() > maximum) {
          exceeded = true;
          completion.completeExceptionally(new IllegalStateException("公开资料超过大小限制"));
          subscription.cancel();
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        output.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      completion.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      completion.complete(output.toByteArray());
    }
  }
}
