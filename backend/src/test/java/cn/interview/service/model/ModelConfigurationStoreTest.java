package cn.interview.service.model;

import cn.interview.mapper.ModelConfigStore;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ModelConfigurationStoreTest {
    @TempDir Path data;
    final ObjectMapper json = new ObjectMapper();
    final MockEnvironment env = new MockEnvironment().withProperty("app.llm.provider", "mock");
    @Test void 损坏文件明确阻止加载且异常不回显凭证() throws Exception {
        Path file = data.resolve("model-config.json");
        Files.writeString(file, "{\"apiKey\":\"synthetic-private-key\"");
        assertThatThrownBy(() -> new ModelConfigurationService(env, new ModelConfigStore(data.toString(), json), json))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("无法读取").hasMessageNotContaining("synthetic-private-key").hasNoCause();
    }
    @Test void 保存失败保留当前快照且不会回退到其他模型() throws Exception {
        var settings = new ModelConfigurationService(env, new ModelConfigStore(data.toString(), json), json);
        var before = settings.connection();
        Files.createDirectory(data.resolve("model-config.json"));
        var body = json.createObjectNode().put("expectedRevision", 0).put("preset", "CUSTOM")
            .put("baseUrl", "https://synthetic.example/v1").put("model", "synthetic-model").put("apiKey", "synthetic-private-key");
        assertThatThrownBy(() -> settings.save(body)).isInstanceOf(cn.interview.exception.ApiException.class).hasMessageContaining("保存失败");
        assertThat(settings.connection()).isSameAs(before);
        assertThat(settings.view().path("revision").asLong()).isZero();
        try (var files = Files.list(data)) { assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("model-config.json"); }
    }
}
