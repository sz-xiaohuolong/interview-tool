package cn.interview.config;

import cn.interview.Application;
import cn.interview.service.interview.FocusedInterviewService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.*;

class TrainingStartupTest {
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

    @Test void freshInstallationSupportsFocusedInterviews() {
        try (var app = start(root.resolve("v1"), false)) {
            assertThat(app.getBean(FocusedInterviewService.class).create(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                    .path("status").asText()).isEqualTo("IN_PROGRESS");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyDatabaseStartsWithoutRepositoryTableAndPreservesRecords(boolean trainingEnabled) throws Exception {
        var store = new cn.interview.mapper.SqliteStore(root.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        store.insertMaterial("project", "PROJECT", "合成项目", "旧资料正文", "2026-10-20");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("interview.db")); var s = c.createStatement()) {
            s.execute("INSERT INTO sessions VALUES('kept','{\"id\":\"kept\",\"status\":\"COMPLETED\"}','2026-10-20')");
            if (trainingEnabled) {
                String migration = new org.springframework.core.io.ClassPathResource("db/migrations/001-training.sql")
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                for (String sql : migration.split(";")) if (!sql.isBlank()) s.execute(sql);
                try (var insert = c.prepareStatement("INSERT INTO v2_schema_migrations VALUES(1,?,'2026-10-20')")) {
                    insert.setString(1, cn.interview.common.ContentHash.sha(migration));
                    insert.executeUpdate();
                }
            }
            s.execute("DROP TABLE project_repositories");
        }
        byte[] before = Files.readAllBytes(root.resolve("interview.db"));
        cn.interview.mapper.TrainingSchemaPreflight.check(root, trainingEnabled);
        assertThat(Files.readAllBytes(root.resolve("interview.db"))).isEqualTo(before);
        try (var app = start(root, trainingEnabled)) {
            var reopened = app.getBean(cn.interview.mapper.SqliteStore.class);
            assertThat(reopened.material("project").content()).isEqualTo("旧资料正文");
            assertThat(reopened.material("project").repository()).isNull();
            assertThat(reopened.session("kept").toString()).isEqualTo("{\"id\":\"kept\",\"status\":\"COMPLETED\"}");
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("interview.db")); var s = c.createStatement();
                 var rows = s.executeQuery("SELECT count(*) FROM project_repositories")) {
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    @Test void malformedRepositoryTableIsStillRejectedBeforeStartupWrites() throws Exception {
        new cn.interview.mapper.SqliteStore(root.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("interview.db")); var s = c.createStatement()) {
            s.execute("DROP TABLE project_repositories");
            s.execute("CREATE TABLE project_repositories(material_id TEXT PRIMARY KEY,payload TEXT)");
        }
        byte[] before = Files.readAllBytes(root.resolve("interview.db"));
        assertThatThrownBy(() -> start(root, false)).hasStackTraceContaining("V2_SCHEMA_UNSUPPORTED");
        assertThat(Files.readAllBytes(root.resolve("interview.db"))).isEqualTo(before);
    }

    @Test void missingOriginalTableIsStillRejectedBeforeStartupWrites() throws Exception {
        new cn.interview.mapper.SqliteStore(root.toString(), new com.fasterxml.jackson.databind.ObjectMapper());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("interview.db")); var s = c.createStatement()) {
            s.execute("DROP TABLE predictions");
        }
        byte[] before = Files.readAllBytes(root.resolve("interview.db"));
        assertThatThrownBy(() -> start(root, false)).hasStackTraceContaining("V2_SCHEMA_UNSUPPORTED");
        assertThat(Files.readAllBytes(root.resolve("interview.db"))).isEqualTo(before);
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyDatabaseStartsBeforeJobApplicationsWereAdded(boolean trainingEnabled) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy=new cn.interview.mapper.SqliteStore(root.toString(),mapper);
        legacy.insertMaterial("kept-material","JD","旧岗位描述","中文原文与备注","2026-10-20");
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("interview.db"));var statement=c.createStatement()) {
            statement.execute("INSERT INTO sessions VALUES('kept','{\"id\":\"kept\",\"status\":\"COMPLETED\"}','2026-10-20')");
            statement.execute("DROP TABLE job_applications");
            if(trainingEnabled) {
                String migration=new org.springframework.core.io.ClassPathResource("db/migrations/001-training.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                for(String sql:migration.split(";"))if(!sql.isBlank())statement.execute(sql);
                try(var insert=c.prepareStatement("INSERT INTO v2_schema_migrations VALUES(1,?,'2026-10-20')")) {
                    insert.setString(1,cn.interview.common.ContentHash.sha(migration));insert.executeUpdate();
                }
            }
        }
        byte[] before=Files.readAllBytes(root.resolve("interview.db"));
        cn.interview.mapper.TrainingSchemaPreflight.check(root,trainingEnabled);
        assertThat(Files.readAllBytes(root.resolve("interview.db"))).isEqualTo(before);
        cn.interview.entity.JobApplication created;
        try(var app=start(root,trainingEnabled)) {
            created=app.getBean(cn.interview.service.job.JobApplicationService.class).create(mapper.createObjectNode().put("company","新公司").put("position","新岗位"));
            var reopened=app.getBean(cn.interview.mapper.SqliteStore.class);
            assertThat(reopened.material("kept-material").content()).isEqualTo("中文原文与备注");
            assertThat(reopened.session("kept").path("status").asText()).isEqualTo("COMPLETED");
        }
        try(var app=start(root,trainingEnabled)) {
            assertThat(app.getBean(cn.interview.service.job.JobApplicationService.class).get(created.id())).isEqualTo(created);
        }
    }

    @Test void malformedJobTableIsRejectedBeforeStartupWrites() throws Exception {
        new cn.interview.mapper.SqliteStore(root.toString(),new com.fasterxml.jackson.databind.ObjectMapper());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("interview.db"));var statement=c.createStatement()) {
            statement.execute("DROP TABLE job_applications");statement.execute("CREATE TABLE job_applications(id TEXT)");
        }
        byte[] before=Files.readAllBytes(root.resolve("interview.db"));
        assertThatThrownBy(()->start(root,false)).hasStackTraceContaining("V2_SCHEMA_UNSUPPORTED");
        assertThat(Files.readAllBytes(root.resolve("interview.db"))).isEqualTo(before);
    }

    private org.springframework.context.ConfigurableApplicationContext start(Path data, boolean enabled) {
        return new SpringApplicationBuilder(Application.class).web(org.springframework.boot.WebApplicationType.NONE)
                .run("--app.data-dir=" + data, "--app.llm.provider=mock", "--app.training.enabled=" + enabled,
                        "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }
}
