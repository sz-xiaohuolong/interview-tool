package cn.interview.mapper;

import cn.interview.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 综合面试 独立快照，不迁移或回写原 SQLite。所有读取均返回独立对象。 */
@Component
public class InterviewSessionStore {
  private final Path directory;
  private final ObjectMapper json;

  public InterviewSessionStore(ObjectMapper json, @Value("${app.data-dir}") String data) {
    this.json = json;
    this.directory = Path.of(data).toAbsolutePath().normalize().resolve("v3/sessions");
    try {
      Files.createDirectories(directory);
    } catch (Exception e) {
      throw new IllegalStateException("无法创建 综合面试 数据目录", e);
    }
  }

  private Path file(String id) {
    if (id == null || !id.matches("[a-f0-9-]{36}")) throw ApiException.missing("面试不存在");
    try {
      UUID.fromString(id);
    } catch (Exception e) {
      throw ApiException.missing("面试不存在");
    }
    return directory.resolve(id + ".json");
  }

  public synchronized ObjectNode get(String id) {
    Path p = file(id);
    if (!Files.exists(p)) throw ApiException.missing("面试不存在");
    try {
      if (Files.size(p) > 4_000_000) throw new IllegalStateException();
      return (ObjectNode) json.readTree(Files.readAllBytes(p));
    } catch (Exception e) {
      throw new ApiException(500, "V3_STORAGE_FAILED", "面试记录无法读取，请保留数据目录并检查存储");
    }
  }

  public synchronized void save(ObjectNode value) {
    Path p = file(value.path("id").asText());
    Path tmp = null;
    try {
      byte[] bytes = json.writeValueAsBytes(value);
      if (bytes.length > 4_000_000) throw new IllegalStateException();
      tmp = Files.createTempFile(directory, "snapshot-", ".tmp");
      Files.write(tmp, bytes);
      try {
        Files.setPosixFilePermissions(
            tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
      try {
        Files.move(tmp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      throw new ApiException(500, "V3_STORAGE_FAILED", "面试记录无法完整保存，请检查数据目录权限和磁盘空间");
    } finally {
      if (tmp != null)
        try {
          Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
        }
    }
  }

  public synchronized List<ObjectNode> list() {
    try (var files = Files.list(directory)) {
      return files
          .filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
          .map(p -> get(p.getFileName().toString().replace(".json", "")))
          .sorted(Comparator.comparing((ObjectNode s) -> s.path("createdAt").asText()).reversed())
          .toList();
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(500, "V3_STORAGE_FAILED", "无法读取面试列表");
    }
  }
}
