package cn.interview.client.jobsearch;

import cn.interview.entity.jobsearch.JobRecord;
import cn.interview.exception.ApiException;
import cn.interview.service.jobsearch.JobCityCatalog;
import cn.interview.service.jobsearch.JobSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** 全部请求由本机合成 CDP 服务应答，禁止依赖真实招聘网站或 Chrome。 */
class CdpGatewayRecoveryTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final List<CdpJobSourceGateway> gateways = new ArrayList<>();
    @org.junit.jupiter.api.AfterEach void cleanup() { gateways.forEach(CdpJobSourceGateway::close); }

    @ParameterizedTest
    @CsvSource({"BOSS,401,SESSION_EXPIRED", "BOSS,403,SESSION_EXPIRED", "BOSS,429,RATE_LIMITED", "BOSS,404,SOURCE_NETWORK", "BOSS,500,SOURCE_NETWORK", "ZHAOPIN,403,SESSION_EXPIRED", "ZHAOPIN,429,RATE_LIMITED"})
    void failedListStatusEndsWaitingWithoutReadingBody(String source, int status, String code) throws Exception {
        try (var fixture = new Browser(status, false)) {
            var gateway = gateway(fixture);
            try (var cursor = gateway.open(source, "Java", "shenzhen")) {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThatThrownBy(cursor::next).isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code).isEqualTo(code);
                        assertThat(e.getMessage()).doesNotContain("secret");
                    }));
                assertThat(fixture.bodyReads).hasValue(0);
            }
            assertThat(fixture.errors).isEmpty();
        }
    }

    @Test void ignoresPreflightAndUnrelatedFailureBeforeSuccessfulList() throws Exception {
        try (var fixture = new Browser(200, false)) {
            var gateway = gateway(fixture);
            try (var cursor = gateway.open("BOSS", "Java", "shenzhen")) {
            assertThat(cursor.next().jobs()).extracting(JobRecord::key).containsExactly("BOSS:one");
            assertThat(fixture.bodyReads).hasValue(1);
            assertThat(fixture.errors).isEmpty();
            }
        }
    }

    @Test void retryReopensSocketAndReplaysSuccessfulBatchWithoutDuplicatingJobsOrOtherSource() throws Exception {
        try (var fixture = new Browser(200, true)) {
            var gateway = gateway(fixture);
            var service = new JobSearchService(gateway, new JobCityCatalog());
            try {
                var input = json.createObjectNode().put("keyword", "Java").put("cityId", "shenzhen");
                input.putArray("sources").add("BOSS").add("LIEPIN");
                var first = idle(service, service.create(input).id());
                assertThat(first.jobs()).extracting(JobRecord::key).containsExactly("BOSS:one", "LIEPIN:liepin");
                var failed = idle(service, service.more(first.id()).id());
                assertThat(failed.sources().getFirst().errorCode()).isEqualTo("SOURCE_NETWORK");
                var recovered = idle(service, service.retry(first.id(), json.createObjectNode().put("source", "BOSS")).id());
                assertThat(recovered.jobs()).extracting(JobRecord::key).containsExactly("BOSS:one", "LIEPIN:liepin", "BOSS:two");
                assertThat(recovered.sources().get(1)).isEqualTo(first.sources().get(1));
                assertThat(fixture.handshakes).hasValue(2);
                assertThat(fixture.bossPages).containsExactly(1, 2, 1, 2);
                service.stop(first.id());
                assertThatThrownBy(() -> service.retry(first.id(), json.createObjectNode().put("source", "BOSS"))).isInstanceOf(ApiException.class);
            } finally { service.close(); }
            assertThat(fixture.errors).isEmpty();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void newSearchRereadsChangedActivePortAfterDisconnect(boolean graceful) throws Exception {
        try (var old = new Browser(200, true)) {
            var gateway = gateway(old); old.graceful = graceful;
            try (var cursor = gateway.open("BOSS", "Java", "shenzhen")) {
                cursor.next();
                assertThatThrownBy(cursor::next).isInstanceOf(ApiException.class);
                try (var fresh = new Browser(200, false)) {
                    writePort(fresh);
                    try (var next = gateway.open("BOSS", "Java", "shenzhen")) {
                        assertThat(next.next().jobs()).extracting(JobRecord::key).containsExactly("BOSS:one");
                        assertThat(fresh.handshakes).hasValue(1);
                    }
                }
            }
        }
    }

    @Test void retryAfterReplayFailureStillRestoresOriginalFailedBatch() throws Exception {
        try(var fixture=new Browser(200,true)){
            fixture.failReplay.set(true);var gateway=gateway(fixture);
            try(var cursor=gateway.open("BOSS","Java","shenzhen")){
                assertThat(cursor.next().jobs()).extracting(JobRecord::key).containsExactly("BOSS:one");
                assertThatThrownBy(cursor::next).isInstanceOf(ApiException.class);
                assertThatThrownBy(cursor::next).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("RATE_LIMITED"));
                assertThat(cursor.next().jobs()).extracting(JobRecord::key).containsExactly("BOSS:two");
            }
        }
    }

    @Test void stopDuringRecoveredBatchDiscardsLateReplyAndRetryCannotReviveTask() throws Exception {
        try (var fixture = new Browser(200, true)) {
            fixture.holdRecovered = true;
            var gateway = gateway(fixture);
            var service = new JobSearchService(gateway, new JobCityCatalog());
            try {
                var input = json.createObjectNode().put("keyword", "Java").put("cityId", "shenzhen");
                input.putArray("sources").add("BOSS");
                var first = idle(service, service.create(input).id());
                idle(service, service.more(first.id()).id());
                service.retry(first.id(), json.createObjectNode().put("source", "BOSS"));
                assertThat(fixture.recoveryEntered.await(3, TimeUnit.SECONDS)).isTrue();
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.stop(first.id()));
                fixture.releaseRecovery.countDown();
                assertThat(fixture.lateSent.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(service.get(first.id()).status()).isEqualTo("STOPPED");
                assertThat(service.get(first.id()).jobs()).extracting(JobRecord::key).containsExactly("BOSS:one");
                assertThatThrownBy(() -> service.retry(first.id(), json.createObjectNode().put("source", "BOSS"))).isInstanceOf(ApiException.class);
            } finally { service.close(); }
        }
    }

    CdpJobSourceGateway gateway(Browser fixture) throws Exception {
        writePort(fixture);
        var gateway = new CdpJobSourceGateway(json, new JobCityCatalog(), root.resolve("DevToolsActivePort"));
        gateways.add(gateway); return gateway;
    }
    void writePort(Browser fixture) throws Exception {
        Files.writeString(root.resolve("DevToolsActivePort"), fixture.server.getLocalPort() + "\n/devtools/browser/synthetic-browser\n");
    }
    static JobSearchService.TaskSnapshot idle(JobSearchService service, String id) throws Exception {
        for (int i = 0; i < 300; i++) {
            var snapshot = service.get(id);
            if (!snapshot.status().equals("RUNNING")) return snapshot;
            Thread.sleep(10);
        }
        throw new AssertionError("来源未在限定时间内结束");
    }

    final class Browser implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final List<Socket> peers = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<Integer> bossPages = new CopyOnWriteArrayList<>();
        final AtomicInteger handshakes = new AtomicInteger(), targets = new AtomicInteger(), bodyReads = new AtomicInteger();
        final AtomicBoolean dropped = new AtomicBoolean(), failReplay = new AtomicBoolean();
        final int status;
        final boolean disconnect;
        volatile boolean graceful;
        volatile boolean holdRecovered;
        final CountDownLatch recoveryEntered = new CountDownLatch(1), releaseRecovery = new CountDownLatch(1), lateSent = new CountDownLatch(1);
        volatile boolean closed;
        Browser(int status, boolean disconnect) throws Exception {
            this.status = status; this.disconnect = disconnect;
            Thread.ofPlatform().daemon().start(() -> {
                while (!closed) try {
                    Socket socket = server.accept(); peers.add(socket);
                    Thread.ofPlatform().daemon().start(() -> serve(socket));
                } catch (IOException e) { if (!closed) errors.add(e); }
            });
        }
        void serve(Socket socket) {
            try (socket) {
                var in = socket.getInputStream(); var out = socket.getOutputStream();
                String header = CdpBrowserTest.header(in);
                String key = Arrays.stream(header.split("\r\n")).filter(h -> h.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].strip();
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1)); out.flush();
                handshakes.incrementAndGet();
                var urls = new HashMap<String, String>(); var pages = new HashMap<String, Integer>();
                String frame;
                while ((frame = CdpBrowserTest.readFrame(in)) != null) {
                    JsonNode command = json.readTree(frame), params = command.path("params");
                    String method = command.path("method").asText(), session = command.path("sessionId").asText();
                    var result = json.createObjectNode(); boolean list = false;
                    switch (method) {
                        case "Target.createTarget" -> result.put("targetId", "target-" + targets.incrementAndGet());
                        case "Target.attachToTarget" -> result.put("sessionId", "session-" + params.path("targetId").asText());
                        case "Page.enable", "Runtime.enable", "Network.enable", "Fetch.enable", "Fetch.continueRequest", "Fetch.failRequest" -> { }
                        case "Target.closeTarget" -> result.put("success", true);
                        case "Page.navigate" -> { urls.put(session, params.path("url").asText()); pages.put(session, 1); list = urls.get(session).contains("zhipin.com") || urls.get(session).contains("zhaopin.com"); }
                        case "Runtime.evaluate" -> {
                            String expression = params.path("expression").asText();
                            if (expression.contains("fetch(")) { pages.put(session, 2); list = true; }
                            var value = json.createObjectNode();
                            if (urls.getOrDefault(session, "").contains("liepin.com")) {
                                value.put("hasMore", false); value.putArray("jobs").addObject().put("sourceId", "liepin").put("url", "https://www.liepin.com/job/liepin.shtml").put("title", "合成岗位");
                            }
                            result.set("result", json.createObjectNode().set("value", value));
                        }
                        case "Network.getResponseBody" -> {
                            bodyReads.incrementAndGet();
                            if (!params.path("requestId").asText().equals("actual")) throw new AssertionError("不得读取失败或预检正文");
                            var body = json.createObjectNode(); var payload = body.putObject("zpData"); payload.put("hasMore", pages.get(session) == 1);
                            var rows = payload.putArray("jobList"); rows.addObject().put("encryptJobId", "one").put("jobName", "合成岗位");
                            if (pages.get(session) == 2) rows.addObject().put("encryptJobId", "two").put("jobName", "合成新增岗位");
                            result.put("body", body.toString());
                        }
                        default -> throw new AssertionError("未知方法：" + method);
                    }
                    if (list) {
                        bossPages.add(pages.get(session));
                        if (disconnect && pages.get(session) == 2 && dropped.compareAndSet(false, true)) {
                            if (graceful) { out.write(new byte[]{(byte)0x88, 2, 3, (byte)0xe8}); out.flush(); }
                            return;
                        }
                        if (holdRecovered && pages.get(session) == 2 && dropped.get()) {
                            recoveryEntered.countDown(); releaseRecovery.await(5, TimeUnit.SECONDS);
                        }
                    }
                    CdpBrowserTest.writeFrame(out, json.createObjectNode().put("id", command.path("id").asLong()).set("result", result).toString());
                    if (list) {
                        response(out, session, "unrelated", 403, "https://unrelated.example/list");
                        String listUrl = urls.get(session).contains("zhaopin.com") ? "https://www.zhaopin.com/c/i/search/positions?page=" : "https://www.zhipin.com/wapi/zpgeek/search/joblist.json?page=";
                        response(out, session, "preflight", 204, listUrl + pages.get(session));
                        int actualStatus=handshakes.get()>1&&pages.get(session)==1&&failReplay.compareAndSet(true,false)?429:status;
                        response(out, session, "actual", actualStatus, listUrl + pages.get(session));
                        // 失败响应不发送 loadingFinished，验证不等待正文完成。
                        if (actualStatus == 200) event(out, session, "Network.loadingFinished", json.createObjectNode().put("requestId", "actual"));
                        if (holdRecovered && pages.get(session) == 2) lateSent.countDown();
                    }
                }
            } catch (SocketException ignored) {
            } catch (Throwable e) { if (!closed) errors.add(e); }
        }
        void response(OutputStream out, String session, String id, int code, String url) throws Exception {
            var params = json.createObjectNode().put("requestId", id);
            params.putObject("response").put("status", code).put("url", url);
            event(out, session, "Network.responseReceived", params);
        }
        void event(OutputStream out, String session, String method, JsonNode params) throws Exception {
            CdpBrowserTest.writeFrame(out, json.createObjectNode().put("sessionId", session).put("method", method).set("params", params).toString());
        }
        @Override public void close() throws Exception {
            closed = true; releaseRecovery.countDown(); server.close(); for (Socket socket : peers) socket.close();
        }
    }
}
