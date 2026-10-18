package cn.interview.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 独立于环境文件保存本机配置，写入完成后再原子替换。 */
@Component
public class ModelConfigStore {
    private final Path file;
    private final ObjectMapper json;
    public ModelConfigStore(@Value("${app.data-dir}") String dataDir, ObjectMapper json) {
        this.file = Path.of(dataDir).toAbsolutePath().normalize().resolve("model-config.json");
        this.json = json;
    }
    public JsonNode read() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32_768) throw new IllegalStateException();
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readAllBytes(file));
        } catch (Exception error) { throw new IllegalStateException("本机模型配置无法读取，请检查 data/model-config.json 后重新启动。"); }
    }
    public void write(JsonNode value) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".model-config-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, json.writeValueAsBytes(value));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new cn.interview.exception.ApiException(503, "MODEL_CONFIG_SAVE_FAILED", "本机模型配置保存失败，当前配置未改变。"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { /* 不输出可能含凭证的文件内容。 */ } }
    }
}
