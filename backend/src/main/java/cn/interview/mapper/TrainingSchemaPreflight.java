package cn.interview.mapper;

import cn.interview.common.ContentHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/** 只读检查必须在 SqliteStore、WAL 和恢复写入之前完成。 */
public final class TrainingSchemaPreflight {
    private TrainingSchemaPreflight() {}

    static String migration() throws Exception {
        return new ClassPathResource("db/migrations/001-training.sql").getContentAsString(StandardCharsets.UTF_8);
    }

    public static void check(Path data, boolean enabled) {
        Path db = data.toAbsolutePath().normalize().resolve("interview.db");
        if (!Files.exists(db)) {
            if (enabled) throw new IllegalStateException("V2_SCHEMA_REQUIRED：请先在维护模式迁移，再开启 专项训练");
            return;
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toUri() + "?mode=ro")) {
            var actual = new LinkedHashMap<String, String>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT name,sql FROM sqlite_master WHERE sql IS NOT NULL")) {
                while (rows.next()) actual.put(rows.getString(1), rows.getString(2));
            }
            boolean migrated = actual.containsKey("v2_schema_migrations");
            if (migrated) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version,checksum FROM v2_schema_migrations")) {
                    if (!rows.next() || rows.getInt(1) != 1 || !ContentHash.sha(migration()).equals(rows.getString(2)) || rows.next())
                        throw new IllegalStateException("V2_SCHEMA_UNSUPPORTED：版本或校验和不受支持，未写入数据库");
                }
            }
            verify(actual, new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8), !actual.isEmpty());
            if (migrated) verify(actual, migration(), true);
            else if (actual.keySet().stream().anyMatch(name -> name.startsWith("v2_")))
                throw new IllegalStateException("V2_SCHEMA_UNSUPPORTED：存在未登记的 专项训练 结构");
            String allowed=new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8)+(migrated?migration():"");
            var names=new java.util.HashSet<String>();var matcher=Pattern.compile("(?i)CREATE\\s+(?:UNIQUE\\s+)?(?:TABLE|INDEX)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)").matcher(allowed);
            while(matcher.find())names.add(matcher.group(1));
            if(!names.containsAll(actual.keySet()))throw new IllegalStateException("V2_SCHEMA_UNSUPPORTED：存在未知表、索引或触发器");
            if (enabled && !migrated) throw new IllegalStateException("V2_SCHEMA_REQUIRED：请先迁移，再开启 专项训练");
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("V2_SCHEMA_UNSUPPORTED：无法只读验证本地结构，未启动业务服务", e); }
    }

    private static void verify(Map<String, String> actual, String schema, boolean required) {
        var pattern = Pattern.compile("(?i)CREATE\\s+(?:UNIQUE\\s+)?(?:TABLE|INDEX)\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)");
        for (String sql : schema.split(";")) {
            var matcher = pattern.matcher(sql);
            if (!matcher.find()) continue;
            String name = matcher.group(1), found = actual.get(name);
            // 旧库可缺少新增的仓库关联表，随后由 SqliteStore 建空表；已有表仍须完整校验。
            if (found == null && (!required || name.equals("project_repositories"))) continue;
            if (found == null || !normalize(found).equals(normalize(sql)))
                throw new IllegalStateException("V2_SCHEMA_UNSUPPORTED：表或索引约束与已知结构不一致");
        }
    }

    private static String normalize(String sql) {
        var out=new StringBuilder();var matcher=Pattern.compile("'(?:(?:'')|[^'])*'|[^']+").matcher(sql);
        while(matcher.find()){String part=matcher.group();out.append(part.startsWith("'")?part:part.replaceAll("(?i)IF\\s+NOT\\s+EXISTS\\s+", "").replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT));}return out.toString();
    }
}
