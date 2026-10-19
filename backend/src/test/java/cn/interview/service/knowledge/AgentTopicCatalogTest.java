package cn.interview.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentTopicCatalogTest {
  final ObjectMapper json = new ObjectMapper();

  ObjectNode fixture() throws Exception {
    return (ObjectNode) json.readTree("""
        {"schemaVersion":1,"themes":[
          {"id":"AGENT_ARCHITECTURE","label":"架构"},{"id":"AGENT_CONTEXT_MEMORY","label":"上下文"},
          {"id":"AGENT_HARNESS_TOOL","label":"工具"},{"id":"AGENT_RAG","label":"检索"},
          {"id":"AGENT_EVALUATION","label":"评测"},{"id":"AGENT_FRAMEWORK","label":"框架"}],
         "references":[{"id":"official","title":"合成官方参考","url":"https://example.test/docs","kind":"OFFICIAL","scope":"只支持合成边界"}],
         "points":[{"id":"arc-1","theme":"AGENT_ARCHITECTURE","title":"编排边界","capability":"说明职责",
          "scenario":"合成工程场景","followupDirections":["异常边界"],"evaluationBoundary":"不认定真实经历",
          "referenceIds":["official"],"discoveryReferences":["https://example.test/discovery"]}]}
        """);
  }

  @Test void 独立小目录能解析且公开选项不泄露题目与判定边界() throws Exception {
    var catalog = new AgentTopicCatalog(json, fixture());
    assertThat(catalog.points()).hasSize(1);
    assertThat(catalog.point("arc-1").path("title").asText()).isEqualTo("编排边界");
    assertThat(catalog.publicView().path("themes")).hasSize(6);
    assertThat(catalog.publicView().has("points")).isFalse();
    var copy = catalog.point("arc-1"); copy.put("title", "外部改写");
    assertThat(catalog.point("arc-1").path("title").asText()).isEqualTo("编排边界");
  }

  @Test void 拒绝重复考点未知主题空参考和悬空参考编号() throws Exception {
    for (String defect : List.of("duplicate", "theme", "emptyReference", "reference")) {
      var value = fixture();
      var point = (ObjectNode) value.path("points").get(0);
      if (defect.equals("duplicate")) value.withArray("points").add(point.deepCopy());
      if (defect.equals("theme")) point.put("theme", "UNKNOWN");
      if (defect.equals("emptyReference")) point.putArray("referenceIds");
      if (defect.equals("reference")) point.withArray("referenceIds").add("missing");
      assertThatThrownBy(() -> new AgentTopicCatalog(json, value)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test void 来源不是技术事实认证且拒绝可执行或非网页发现链接() throws Exception {
    var value = fixture();
    ((ObjectNode) value.path("references").get(0)).put("kind", "COMMUNITY");
    assertThat(new AgentTopicCatalog(json, value).point("arc-1").path("referenceIds")).hasSize(1);
    ((ObjectNode) value.path("points").get(0)).putArray("discoveryReferences").add("javascript:alert(1)");
    assertThatThrownBy(() -> new AgentTopicCatalog(json, value)).isInstanceOf(IllegalStateException.class);
  }

  @Test void 运行资源入口可加载且六类各至少十点合计至少六十点() {
    var catalog = new AgentTopicCatalog(json);
    assertThat(catalog.publicView().path("themes")).hasSize(6);
    assertThat(catalog.points()).hasSizeGreaterThanOrEqualTo(60);
    for (var theme : catalog.publicView().path("themes"))
      assertThat(theme.path("pointCount").asInt()).isGreaterThanOrEqualTo(10);
  }
}
