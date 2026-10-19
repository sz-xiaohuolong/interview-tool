package cn.interview.service;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.List;

/** 四阶段进度与用户作答时钟；只由显式写操作落盘，查询按服务端时间推导。 */
final class InterviewProgress {
  static final List<String> STAGES = List.of("INTRO", "PROJECT", "TECHNICAL", "QUESTIONS");
  private static final List<String> LABELS = List.of("开场介绍", "项目深挖", "岗位技术与场景", "候选人反问");
  private final ObjectMapper json;
  private final Clock clock;

  InterviewProgress(ObjectMapper json, Clock clock) { this.json = json; this.clock = clock; }
  static boolean enabled(JsonNode s) { return s.path("experienceVersion").asInt() == 1; }
  private boolean timed(JsonNode s) { return enabled(s) && s.path("interviewMode").asText().equals("TIMED"); }
  private ObjectNode state(ObjectNode s) {
    if (!s.path("timingState").isObject()) s.putObject("timingState").put("elapsedMs", 0);
    return (ObjectNode) s.path("timingState");
  }
  long elapsed(JsonNode s) {
    var timer = s.path("timingState");
    long elapsed = timer.path("elapsedMs").asLong(), active = timer.path("activeSince").asLong();
    if (active > 0) elapsed += Math.max(0, clock.millis() - Math.max(active, timer.path("waitUntil").asLong()));
    return timed(s) ? Math.min(s.path("timeLimitMinutes").asLong(30) * 60_000, elapsed) : elapsed;
  }
  boolean expired(JsonNode s) { return timed(s) && s.path("timingState").has("startedAt") && elapsed(s) >= s.path("timeLimitMinutes").asLong(30) * 60_000; }
  void start(ObjectNode s) {
    if (!enabled(s)) return;
    state(s).put("startedAt", clock.instant().toString()).put("elapsedMs", 0);
  }
  void resume(ObjectNode s) {
    if (!timed(s) || expired(s)) return;
    var timer = state(s);
    timer.put("activeSince", clock.millis()).put("waitUntil", 0);
  }
  void stop(ObjectNode s) {
    if (!enabled(s)) return;
    long used = elapsed(s);
    state(s).put("elapsedMs", used).put("activeSince", 0).put("waitUntil", 0);
  }
  void recover(ObjectNode s) {
    if (!timed(s)) return;
    // 硬崩后只恢复最近已保存的累计值，避免将未知宕机时段算作用户时间。
    state(s).put("activeSince", clock.millis()).put("waitUntil", 0);
  }
  void recognition(ObjectNode s, boolean waiting, String questionId) {
    if (!timed(s) || !s.path("status").asText().equals("IN_PROGRESS") || expired(s))
      throw ApiException.conflict("当前无需等待语音识别，请核对面试状态");
    if (!questionId.equals(s.path("currentQuestion").path("questionId").asText()))
      throw ApiException.conflict("问题已变化，未暂停旧问题的计时");
    var timer = state(s);
    if (waiting) {
      if (timer.path("waitUntil").asLong() > clock.millis())
        throw ApiException.conflict("当前识别仍在等待，重复通知不会延长等待窗口");
      stop(s);
      timer.put("activeSince", clock.millis()).put("waitUntil", clock.millis() + 10_000)
          .put("waitUsedQuestion", questionId);
    } else {
      if (!questionId.equals(timer.path("waitUsedQuestion").asText()) || timer.path("waitUntil").asLong() == 0)
        throw ApiException.conflict("本题没有待结束的语音识别等待");
      stop(s); resume(s);
    }
  }
  ObjectNode timing(JsonNode s) {
    long limit = timed(s) ? s.path("timeLimitMinutes").asLong(30) * 60_000 : 0;
    long used = elapsed(s);
    boolean expired = expired(s);
    var out = json.createObjectNode().put("enabled", timed(s)).put("limitMs", limit)
        .put("elapsedMs", used).put("remainingMs", Math.max(0, limit - used))
        .put("expired", expired).put("serverNow", clock.millis())
        .put("running", timed(s) && s.path("status").asText().equals("IN_PROGRESS") && !expired
            && s.path("timingState").path("activeSince").asLong() > 0
            && clock.millis() >= s.path("timingState").path("waitUntil").asLong());
    long until = s.path("timingState").path("waitUntil").asLong();
    if (until > clock.millis()) out.put("waitingUntil", until);
    return out;
  }
  ArrayNode stages(JsonNode s) {
    var out = json.createArrayNode();
    String current = s.path("stage").asText();
    int currentIndex = STAGES.indexOf(current);
    boolean closed = List.of("COMPLETED", "REPORTING", "REPORT_READY", "CANCELLED").contains(s.path("status").asText());
    for (int i = 0; i < STAGES.size(); i++) {
      String id = STAGES.get(i); int answered = 0; boolean asked = false;
      for (var m : s.path("messages")) if (m.path("stage").asText().equals(id)) {
        if (m.path("role").asText().equals("USER")) answered++;
        else asked = true;
      }
      boolean skipped = false;
      for (var value : s.path("skippedStages")) if (value.asText().equals(id)) skipped = true;
      String status = skipped ? "SKIPPED" : answered > 0 && (closed || i < currentIndex) ? "COMPLETED"
          : asked ? "IN_PROGRESS" : "NOT_STARTED";
      out.addObject().put("id", id).put("label", LABELS.get(i)).put("status", status).put("answeredCount", answered);
    }
    return out;
  }
  /** 留出后续阶段；临近阶段建议预算或总时限时只收敛追问，不打断当前作答。 */
  boolean narrowFollowups(JsonNode s) {
    if (!timed(s)) return false;
    long remaining = Math.max(0, s.path("timeLimitMinutes").asLong(30) * 60_000 - elapsed(s));
    int later = Math.max(0, 3 - STAGES.indexOf(s.path("stage").asText()));
    long limit = s.path("timeLimitMinutes").asLong(30) * 60_000;
    int stage = STAGES.indexOf(s.path("stage").asText());
    double fraction = switch (stage) { case 0 -> .10; case 1 -> .50; case 2 -> .90; default -> 1.0; };
    return remaining <= Math.max(60_000, later * 90_000L) || elapsed(s) >= (long) (limit * fraction);
  }
  int nextIndex(JsonNode s, boolean stageComplete) {
    int next = s.path("questionIndex").asInt() + 1;
    if (enabled(s) && (narrowFollowups(s) || (s.has("plannedQuestionCount") && stageComplete))) {
      String stage = s.path("stage").asText();
      while (next < s.path("questions").size() && s.path("questions").get(next).path("stage").asText().equals(stage)) next++;
    }
    return next;
  }
}
