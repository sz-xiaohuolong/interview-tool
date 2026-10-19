package cn.interview.client.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 仅供受限真实验收使用，在外部请求前持久登记共享额度，不提供续跑或重置入口。 */
public final class AgentTopicContinuationLedger implements AutoCloseable {
  private static final int PILOT_ATTEMPTS = 6;
  private static final int TOTAL_BUDGET = 28;
  private static final int CONTINUATION_BUDGET = 22;

  private final ObjectMapper json;
  private final Path directory;
  private final FileChannel channel;
  private final FileLock lock;
  private final ObjectNode ledger;
  private final ArrayNode attempts;
  private final ArrayNode pilotSnapshot;
  private final ObjectNode continuation;
  private final ObjectNode approvalSnapshot;
  private final String materialSha256;
  private final List<Reservation> reservations = new ArrayList<>();
  private final int[] plans = new int[3];
  private final int[] followups = new int[3];
  private final int[] reviews = new int[3];
  private boolean terminal;
  private boolean closed;

  public AgentTopicContinuationLedger(ObjectMapper json, Path sharedDirectory) throws Exception {
    this.json = Objects.requireNonNull(json);
    this.directory = Objects.requireNonNull(sharedDirectory).toAbsolutePath().normalize();
    if (!Files.isDirectory(directory)) throw new IllegalStateException("共享试跑目录不存在");
    var openedChannel = FileChannel.open(directory.resolve("pilot.lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquiredLock = null;
    try {
      try { acquiredLock = openedChannel.tryLock(); }
      catch (OverlappingFileLockException error) {
        throw new IllegalStateException("同一试跑账本已在执行", error);
      }
      if (acquiredLock == null) throw new IllegalStateException("同一试跑账本已在执行");
      var parsed = json.readTree(Files.readAllBytes(directory.resolve("ledger.json")));
      if (!(parsed instanceof ObjectNode original)) throw new IllegalStateException("共享账本必须为对象");
      validatePilot(original);
      this.materialSha256 = original.path("humanApproval").path("materialSha256").asText();
      requireApprovedMaterial();
      this.channel = openedChannel;
      this.lock = acquiredLock;
      this.ledger = original;
      this.attempts = (ArrayNode) original.path("attempts");
      this.pilotSnapshot = attempts.deepCopy();
      this.approvalSnapshot = ((ObjectNode) original.path("humanApproval")).deepCopy();
      this.continuation = ledger.putObject("continuation")
          .put("status", "PREPARED").put("maxAttempts", CONTINUATION_BUDGET)
          .put("attemptCount", 0).put("humanConfirmed", false)
          .put("approvedMaterialSha256", materialSha256).put("startedAt", Instant.now().toString());
      ledger.put("status", "CONTINUATION_PREPARED");
      persist();
    } catch (Exception | Error error) {
      if (acquiredLock != null) {
        try { acquiredLock.release(); }
        catch (Exception cleanup) { error.addSuppressed(cleanup); }
      }
      try { openedChannel.close(); }
      catch (Exception cleanup) { error.addSuppressed(cleanup); }
      throw error;
    }
  }

  public synchronized ObjectNode reserve(int sessionNumber, String stage, ObjectNode input) throws Exception {
    requireActive();
    validateIntegrity();
    if (sessionNumber < 1 || sessionNumber > 2) throw new IllegalArgumentException("场号只能为1或2");
    if (stage == null || !List.of("PLAN", "FOLLOWUP", "REVIEW").contains(stage))
      throw new IllegalArgumentException("模型阶段只能为PLAN、FOLLOWUP或REVIEW");
    if (input == null) throw new IllegalArgumentException("请求输入不能为空");
    requireApprovedMaterial();
    if (reservations.size() >= CONTINUATION_BUDGET || attempts.size() >= TOTAL_BUDGET)
      throw new IllegalStateException("续跑或总请求预算已用尽");
    if (reservations.stream().anyMatch(record -> !serviceValidated(record.attempt())))
      throw new IllegalStateException("上一请求尚未通过完整业务验证，不能发送下一请求");
    if (sessionNumber == 2 && reviews[1] != 1)
      throw new IllegalStateException("第一场复盘尚未完成，不能开始第二场");
    if (sessionNumber == 1 && plans[2] != 0)
      throw new IllegalStateException("第二场开始后不能回到第一场");
    switch (stage) {
      case "PLAN" -> {
        if (plans[sessionNumber] != 0) throw new IllegalStateException("每场只能规划一次");
      }
      case "FOLLOWUP" -> {
        if (plans[sessionNumber] != 1 || reviews[sessionNumber] != 0)
          throw new IllegalStateException("追问必须位于规划之后、复盘之前");
        if (followups[sessionNumber] >= 9) throw new IllegalStateException("每场最多九次回答推进");
      }
      case "REVIEW" -> {
        if (plans[sessionNumber] != 1 || reviews[sessionNumber] != 0)
          throw new IllegalStateException("复盘必须在本场规划后且只能一次");
      }
      default -> throw new IllegalArgumentException("不支持的模型阶段");
    }
    var inputSnapshot = input.deepCopy();
    String inputHash = digest(inputSnapshot.toString().getBytes(StandardCharsets.UTF_8));
    var attempt = attempts.addObject().put("number", attempts.size())
        .put("sessionNumber", sessionNumber).put("stage", stage)
        .put("status", "RESERVED").put("reservedAt", Instant.now().toString())
        .put("inputSha256", inputHash);
    attempt.set("input", inputSnapshot.deepCopy());
    reservations.add(new Reservation(sessionNumber, stage, inputSnapshot, inputHash, attempt));
    switch (stage) {
      case "PLAN" -> plans[sessionNumber]++;
      case "FOLLOWUP" -> followups[sessionNumber]++;
      case "REVIEW" -> reviews[sessionNumber]++;
      default -> throw new IllegalStateException("不支持的模型阶段");
    }
    continuation.put("status", "RUNNING");
    ledger.put("status", "RUNNING");
    persist();
    return attempt;
  }

  public synchronized void persist() throws Exception {
    requireOpen();
    Path temporary = null;
    Throwable failure = null;
    try {
      validateIntegrity();
      ledger.put("attemptCount", attempts.size());
      continuation.put("attemptCount", reservations.size());
      temporary = Files.createTempFile(directory, "continuation-", ".tmp");
      Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
      Files.write(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(ledger));
      try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) { output.force(true); }
      Files.move(temporary, directory.resolve("ledger.json"),
          StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception | Error error) {
      terminal = true;
      failure = error;
      throw error;
    } finally {
      if (temporary != null) {
        try { Files.deleteIfExists(temporary); }
        catch (Exception | Error cleanup) {
          terminal = true;
          if (failure != null) failure.addSuppressed(cleanup);
          else throw cleanup;
        }
      }
    }
  }

  public synchronized ObjectNode ledger() { return ledger; }

  public synchronized void stop(String reason) throws Exception {
    requireOpen();
    terminal = true;
    continuation.put("status", "STOPPED_ON_FAILURE").put("stopReason", Objects.requireNonNull(reason))
        .put("finishedAt", Instant.now().toString());
    ledger.put("status", "STOPPED_ON_FAILURE");
    persist();
  }

  public synchronized void complete() throws Exception {
    requireActive();
    validateIntegrity();
    if (plans[1] != 1 || plans[2] != 1 || reviews[1] != 1 || reviews[2] != 1
        || reservations.stream().anyMatch(record -> !serviceValidated(record.attempt())))
      throw new IllegalStateException("两场准备、问答及复盘尚未全部通过业务验证");
    terminal = true;
    continuation.put("status", "STRUCTURE_VALIDATED_WAITING_HUMAN")
        .put("finishedAt", Instant.now().toString());
    ledger.put("status", "STRUCTURE_VALIDATED_WAITING_HUMAN");
    persist();
  }

  @Override public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    try { lock.release(); }
    finally { channel.close(); }
  }

  private static void validatePilot(ObjectNode original) {
    if (original.has("continuation")) throw new IllegalStateException("续跑已登记，不能再次运行或重置额度");
    if (!original.path("humanConfirmed").isBoolean() || !original.path("humanConfirmed").asBoolean())
      throw new IllegalStateException("首批尚未获得人工确认");
    if (!original.path("humanApproval").isObject()
        || original.path("humanApproval").path("materialSha256").asText().isBlank())
      throw new IllegalStateException("缺少与首批材料绑定的人工批准");
    if (!original.path("totalBudget").isIntegralNumber() || original.path("totalBudget").asInt() != TOTAL_BUDGET
        || !original.path("pilotBudget").isIntegralNumber() || original.path("pilotBudget").asInt() != PILOT_ATTEMPTS)
      throw new IllegalStateException("共享账本预算与已批准协议不一致");
    if (!(original.path("attempts") instanceof ArrayNode prior) || prior.size() != PILOT_ATTEMPTS)
      throw new IllegalStateException("必须保留首批六次规划记录");
    for (int i = 0; i < PILOT_ATTEMPTS; i++) {
      var attempt = prior.get(i);
      if (!attempt.isObject() || !attempt.path("number").isIntegralNumber() || attempt.path("number").asInt() != i + 1
          || !attempt.path("stage").asText().equals("PLAN")
          || !attempt.path("status").asText().equals("STRUCTURE_VALIDATED"))
        throw new IllegalStateException("首批六次规划必须全部通过结构检查");
    }
    if (original.path("status").asText().equals("STOPPED_ON_FAILURE"))
      throw new IllegalStateException("失败停批账本不能继续运行");
  }

  private void validateIntegrity() {
    if (ledger.get("attempts") != attempts || ledger.get("continuation") != continuation
        || attempts.size() != PILOT_ATTEMPTS + reservations.size()
        || attempts.size() > TOTAL_BUDGET || reservations.size() > CONTINUATION_BUDGET
        || ledger.path("totalBudget").asInt() != TOTAL_BUDGET
        || ledger.path("pilotBudget").asInt() != PILOT_ATTEMPTS
        || !ledger.path("humanConfirmed").asBoolean()
        || !ledger.path("humanApproval").equals(approvalSnapshot))
      throw new IllegalStateException("账本身份、人工批准或预算被改变");
    for (int i = 0; i < PILOT_ATTEMPTS; i++) {
      if (!attempts.get(i).equals(pilotSnapshot.get(i)))
        throw new IllegalStateException("不能更改首批原始记录");
    }
    for (int i = 0; i < reservations.size(); i++) {
      var record = reservations.get(i);
      var attempt = record.attempt();
      if (attempts.get(PILOT_ATTEMPTS + i) != attempt
          || attempt.path("number").asInt() != PILOT_ATTEMPTS + i + 1
          || attempt.path("sessionNumber").asInt() != record.sessionNumber()
          || !attempt.path("stage").asText().equals(record.stage())
          || !attempt.path("inputSha256").asText().equals(record.inputSha256())
          || !attempt.path("input").equals(record.input()))
        throw new IllegalStateException("已预登记的请求身份或输入被改变");
    }
  }

  private void requireApprovedMaterial() throws Exception {
    if (!digest(Files.readAllBytes(directory.resolve("human-review.md"))).equals(materialSha256))
      throw new IllegalStateException("人工批准对应的评阅材料已改变");
  }

  private void requireActive() {
    requireOpen();
    if (terminal || !List.of("PREPARED", "RUNNING").contains(continuation.path("status").asText()))
      throw new IllegalStateException("续跑已经停止或完成，不能再发送请求");
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("续跑账本已关闭");
  }

  private static boolean serviceValidated(ObjectNode attempt) {
    return attempt.path("status").asText().equals("SERVICE_VALIDATED");
  }

  private static String digest(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private record Reservation(int sessionNumber, String stage, ObjectNode input,
                             String inputSha256, ObjectNode attempt) {}
}
