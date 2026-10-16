package cn.interview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.*;

class V2StartupTest {
    @TempDir Path root;

    @Test void enablingWithoutMigrationFailsBeforeCreatingAnyData() {
        Path data = root.resolve("未初始化");
        assertThatThrownBy(() -> start(data, true)).hasStackTraceContaining("V2_SCHEMA_REQUIRED");
        assertThat(Files.exists(data)).isFalse();
    }

    @Test void futureSchemaIsRejectedBeforeLegacyTablesOrRecoveryCanWrite() throws Exception {
        Path db = root.resolve("interview.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) {
            s.execute("CREATE TABLE v2_schema_migrations(version INTEGER PRIMARY KEY,checksum TEXT,applied_at TEXT)");
            s.execute("INSERT INTO v2_schema_migrations VALUES(999,'future','2026-10-15')");
        }
        assertThatThrownBy(() -> start(root, false)).hasStackTraceContaining("V2_SCHEMA_UNSUPPORTED");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement();
             var rows = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
            assertThat(rows.getInt(1)).isEqualTo(1);
        }
    }

    @Test void disabledFreshInstallationStillSupportsV1() {
        try (var app = start(root.resolve("v1"), false)) {
            assertThat(app.getBean(InterviewService.class).create(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                    .path("status").asText()).isEqualTo("IN_PROGRESS");
        }
    }

    private org.springframework.context.ConfigurableApplicationContext start(Path data, boolean enabled) {
        return new SpringApplicationBuilder(Application.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--app.data-dir=" + data, "--app.llm.provider=mock", "--app.v2.enabled=" + enabled,
                        "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }
}
