package cn.interview.client.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 所有批准、输入及账本均为临时目录中的合成夹具，不代表真实人工评分。 */
class AgentTopicContinuationLedgerTest {
  private final ObjectMapper json = new ObjectMapper();
  @TempDir Path directory;

  @Test
  void persistsPreparationAndReservationBeforeReturningWithoutChangingPilot() throws Exception {
    var original = approvedPilot();
    var pilot = original.path("attempts").deepCopy();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      assertThat(read().path("continuation").path("status").asText()).isEqualTo("PREPARED");
      var input = json.createObjectNode().put("case", "合成样本");
      var reserved = ledger.reserve(1, "PLAN", input);
      input.put("case", "调用后修改输入");
      var disk = read();
      assertThat(disk.path("attempts")).hasSize(7);
      for (int i = 0; i < 6; i++) assertThat(disk.path("attempts").get(i)).isEqualTo(pilot.get(i));
      var stored = disk.path("attempts").get(6);
      assertThat(stored.path("number").asInt()).isEqualTo(7);
      assertThat(stored.path("sessionNumber").asInt()).isEqualTo(1);
      assertThat(stored.path("stage").asText()).isEqualTo("PLAN");
      assertThat(stored.path("status").asText()).isEqualTo("RESERVED");
      assertThat(stored.path("reservedAt").asText()).isNotBlank();
      assertThat(stored.path("input").path("case").asText()).isEqualTo("合成样本");
      assertThat(stored.path("inputSha256").asText()).isEqualTo(sha256("{\"case\":\"合成样本\"}"));
      reserved.put("status", "SERVICE_VALIDATED").put("rawOutput", "合成模型正文");
      ledger.persist();
      assertThat(read().path("attempts").get(6).path("rawOutput").asText()).isEqualTo("合成模型正文");
      assertThat(read().path("attemptCount").asInt()).isEqualTo(7);
    }
  }

  @Test
  void refusesMissingHumanConfirmationWithoutChangingTheLedger() throws Exception {
    var pilot = approvedPilot();
    pilot.put("humanConfirmed", false);
    write(pilot);
    String before = Files.readString(directory.resolve("ledger.json"));
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Files.readString(directory.resolve("ledger.json"))).isEqualTo(before);
  }

  @Test
  void refusesMissingApprovalAndReleasesTheLockAfterRejectedConstruction() throws Exception {
    var pilot = approvedPilot();
    pilot.remove("humanApproval");
    write(pilot);
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      assertThat(ledger.reserve(1, "PLAN", json.createObjectNode()).path("number").asInt()).isEqualTo(7);
    }
  }

  @Test
  void refusesChangedHumanMaterials() throws Exception {
    approvedPilot();
    Files.writeString(directory.resolve("human-review.md"), "合成材料已改变", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    assertThat(read().has("continuation")).isFalse();
  }

  @Test
  void requiresExactlySixSuccessfullyValidatedPlanAttempts() throws Exception {
    var pilot = approvedPilot();
    ((ObjectNode) pilot.path("attempts").get(5)).put("status", "FAILED");
    write(pilot);
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    pilot = approvedPilot();
    ((ObjectNode) pilot.path("attempts").get(5)).put("stage", "FOLLOWUP");
    write(pilot);
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    pilot = approvedPilot();
    ((com.fasterxml.jackson.databind.node.ArrayNode) pilot.path("attempts")).remove(5);
    write(pilot);
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    assertThat(read().has("continuation")).isFalse();
  }

  @Test
  void refusesRaisedBudgetInsteadOfResettingAuthorization() throws Exception {
    var pilot = approvedPilot();
    pilot.put("totalBudget", 100);
    write(pilot);
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    assertThat(read().path("attempts")).hasSize(6);
  }

  @Test
  void holdsTheExclusivePilotLockForTheWholeRun() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
          .isInstanceOf(IllegalStateException.class);
      assertThat(read().path("attempts")).hasSize(6);
    }
  }

  @Test
  void refusesStageAndSessionOrderWithoutSpendingAttempts() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      assertThatThrownBy(() -> ledger.reserve(1, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> ledger.reserve(1, "REVIEW", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> ledger.reserve(2, "PLAN", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      validated(ledger, 1, "PLAN");
      assertThatThrownBy(() -> ledger.reserve(1, "PLAN", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      validated(ledger, 1, "FOLLOWUP");
      validated(ledger, 1, "REVIEW");
      assertThatThrownBy(() -> ledger.reserve(1, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> ledger.reserve(1, "REVIEW", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(read().path("attempts")).hasSize(9);
    }
  }

  @Test
  void rejectsUnknownStagesAndOutOfRangeSessionsWithoutWriting() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      for (String stage : List.of("ANALYZE", "FOLLOW_UP", "plan", "")) {
        assertThatThrownBy(() -> ledger.reserve(1, stage, json.createObjectNode()))
            .isInstanceOf(IllegalArgumentException.class);
      }
      for (int number : List.of(0, 3)) {
        assertThatThrownBy(() -> ledger.reserve(number, "PLAN", json.createObjectNode()))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThat(read().path("attempts")).hasSize(6);
    }
  }

  @Test
  void blocksAnotherRequestUntilTheReservedResponseHasBeenValidated() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      var reserved = ledger.reserve(1, "PLAN", json.createObjectNode());
      assertThatThrownBy(() -> ledger.reserve(1, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      reserved.put("status", "CLIENT_VALIDATED");
      ledger.persist();
      assertThatThrownBy(() -> ledger.reserve(1, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(read().path("attempts")).hasSize(7);
    }
  }

  @Test
  void enforcesNineFollowupsPerSessionAndTwentyEightTotalAttempts() throws Exception {
    var original = approvedPilot().path("attempts").deepCopy();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      for (int session = 1; session <= 2; session++) {
        validated(ledger, session, "PLAN");
        for (int followup = 0; followup < 9; followup++) validated(ledger, session, "FOLLOWUP");
        int currentSession = session;
        assertThatThrownBy(() -> ledger.reserve(currentSession, "FOLLOWUP", json.createObjectNode()))
            .isInstanceOf(IllegalStateException.class);
        validated(ledger, session, "REVIEW");
      }
      assertThat(read().path("attempts")).hasSize(28);
      assertThat(read().path("continuation").path("attemptCount").asInt()).isEqualTo(22);
      assertThatThrownBy(() -> ledger.reserve(2, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      for (int i = 0; i < 6; i++) assertThat(read().path("attempts").get(i)).isEqualTo(original.get(i));
    }
  }

  @Test
  void persistsFailureAndDoesNotAllowRetryOrReopening() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      var attempt = ledger.reserve(1, "PLAN", json.createObjectNode());
      attempt.put("rawOutput", "失败原文").put("status", "FAILED");
      ledger.stop("合成结构校验失败");
      assertThat(read().path("status").asText()).isEqualTo("STOPPED_ON_FAILURE");
      assertThat(read().path("continuation").path("stopReason").asText()).isEqualTo("合成结构校验失败");
      assertThat(read().path("attempts").get(6).path("rawOutput").asText()).isEqualTo("失败原文");
      assertThatThrownBy(() -> ledger.reserve(1, "PLAN", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    assertThat(read().path("attempts")).hasSize(7);
  }

  @Test
  void doesNotTreatAnUnfinishedCloseAsPermissionToRestart() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      ledger.reserve(1, "PLAN", json.createObjectNode());
    }
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
    assertThat(read().path("attempts")).hasSize(7);
  }

  @Test
  void blocksFurtherRequestsWhenTheLedgerDirectoryBecomesUnavailable() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      ledger.reserve(1, "PLAN", json.createObjectNode()).put("status", "SERVICE_VALIDATED");
      Path moved = directory.resolveSibling(directory.getFileName() + "-unavailable");
      Files.move(directory, moved);
      Files.writeString(directory, "合成磁盘故障：目录被普通文件占用", StandardCharsets.UTF_8);
      try {
        assertThatThrownBy(ledger::persist).isInstanceOf(java.io.IOException.class);
      } finally {
        Files.delete(directory);
        Files.move(moved, directory);
      }
      assertThatThrownBy(() -> ledger.reserve(1, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(read().path("attempts")).hasSize(7);
    }
  }

  @Test
  void requiresBothValidatedSessionsBeforeCompletingAndKeepsHumanJudgmentPending() throws Exception {
    approvedPilot();
    try (var ledger = new AgentTopicContinuationLedger(json, directory)) {
      assertThatThrownBy(ledger::complete).isInstanceOf(IllegalStateException.class);
      for (int session = 1; session <= 2; session++) {
        validated(ledger, session, "PLAN");
        for (int answer = 0; answer < 3; answer++) validated(ledger, session, "FOLLOWUP");
        validated(ledger, session, "REVIEW");
      }
      ledger.complete();
      assertThat(read().path("continuation").path("status").asText())
          .isEqualTo("STRUCTURE_VALIDATED_WAITING_HUMAN");
      assertThat(read().path("continuation").path("humanConfirmed").asBoolean()).isFalse();
      assertThat(read().path("humanConfirmed").asBoolean()).isTrue();
      assertThat(read().path("attempts")).hasSize(16);
      assertThatThrownBy(() -> ledger.reserve(2, "FOLLOWUP", json.createObjectNode()))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> new AgentTopicContinuationLedger(json, directory))
        .isInstanceOf(IllegalStateException.class);
  }

  private void validated(AgentTopicContinuationLedger ledger, int session, String stage) throws Exception {
    ledger.reserve(session, stage, json.createObjectNode().put("synthetic", true))
        .put("status", "SERVICE_VALIDATED");
    ledger.persist();
  }

  private ObjectNode approvedPilot() throws Exception {
    String material = "# 单元测试合成评阅材料\n仅验证授权与账本边界，不代表人工评分。\n";
    Files.writeString(directory.resolve("human-review.md"), material, StandardCharsets.UTF_8);
    var pilot = json.createObjectNode().put("totalBudget", 28).put("pilotBudget", 6)
        .put("automaticRetries", 0).put("humanConfirmed", true).put("status", "WAITING_HUMAN")
        .put("attemptCount", 6);
    pilot.putObject("humanApproval").put("materialSha256", sha256(material));
    var attempts = pilot.putArray("attempts");
    for (int i = 1; i <= 6; i++) attempts.addObject().put("number", i).put("caseId", "SYNTHETIC-" + i)
        .put("stage", "PLAN").put("status", "STRUCTURE_VALIDATED").put("rawOutput", "合成原始输出" + i);
    write(pilot);
    return pilot;
  }

  private ObjectNode read() throws Exception {
    return (ObjectNode) json.readTree(Files.readString(directory.resolve("ledger.json")));
  }

  private void write(ObjectNode value) throws Exception {
    Files.writeString(directory.resolve("ledger.json"), json.writeValueAsString(value), StandardCharsets.UTF_8);
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
