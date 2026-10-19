package cn.interview.client;

import cn.interview.validation.InterviewOutputValidator;
import static org.assertj.core.api.Assertions.*;

import cn.interview.validation.InterviewOutputValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NetworkTopicTest {
  @Test void networkTopicPassesBothBoundariesAndIsDeclaredToAnalyzer() throws Exception {
    var json = new ObjectMapper();
    var result = json.readTree("{\"summary\":{\"technicalStack\":[\"TCP\"],\"responsibilities\":[],\"clarifications\":[],\"focus\":[]},\"topics\":[\"NETWORK\"]}");
    assertThatCode(() -> InterviewOutputValidator.validateModel("ANALYZE", json.createObjectNode(), (com.fasterxml.jackson.databind.node.ObjectNode) result)).doesNotThrowAnyException();
    assertThat(new InterviewOutputValidator(json).analysis(result).path("topics").get(0).asText()).isEqualTo("NETWORK");
    try (var input = getClass().getResourceAsStream("/prompts/interview/analyze.txt")) {
      assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).contains("NETWORK");
    }
  }
}
