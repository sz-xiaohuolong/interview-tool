package cn.interview.service.knowledge;

import cn.interview.client.transport.FixedHttpTransport;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentOfficialSourceBoundaryTest {
  @Test void 只放行新增受控官方文档域名仍拒绝任意地址与重定向参数() {
    for (String url : new String[] {
        "https://docs.langchain.com/oss/python/langgraph/persistence",
        "https://platform.claude.com/docs/en/build-with-claude/prompt-caching",
        "https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview"})
      assertThatCode(() -> FixedHttpTransport.validatePublicUrl(URI.create(url))).doesNotThrowAnyException();
    for (String url : new String[] {
        "http://docs.langchain.com/", "https://docs.langchain.com.evil.test/",
        "https://127.0.0.1/", "https://example.com/", "https://docs.langchain.com/?redirect=private"})
      assertThatThrownBy(() -> FixedHttpTransport.validatePublicUrl(URI.create(url))).isInstanceOf(IllegalArgumentException.class);
  }
}
