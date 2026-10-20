package cn.interview.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class TrainingMigrationTest {
    @TempDir Path root;

    @Test void migrationIsExplicitRepeatableAndPreservesLegacyRecords() throws Exception {
        Path data = root.resolve("数据");
        var legacy = new SqliteStore(data.toString(), new ObjectMapper());
        legacy.jdbc.update("INSERT INTO sessions VALUES('original','{\"id\":\"original\",\"status\":\"COMPLETED\"}','2026-10-15')");
        String before = legacy.jdbc.queryForObject("SELECT payload FROM sessions WHERE id='original'", String.class);
        assertThat(migrate(data, "before-first")).isZero();
        TrainingSchemaPreflight.check(data, true);
        assertThat(legacy.jdbc.queryForObject("SELECT payload FROM sessions WHERE id='original'", String.class)).isEqualTo(before);
        assertThat(legacy.jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'v2_%'", Integer.class)).isEqualTo(14);
        assertThat(migrate(data, "before-repeat")).isZero();
        assertThat(legacy.jdbc.queryForObject("SELECT count(*) FROM v2_schema_migrations", Integer.class)).isEqualTo(1);
    }

    @Test void badChecksumOrModifiedConstraintsFailWithoutChangingLegacyRows() throws Exception {
        Path data = root.resolve("tampered");
        assertThat(migrate(data, "before-tampered")).isZero();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("interview.db")); var s = c.createStatement()) {
            s.execute("UPDATE v2_schema_migrations SET checksum='not-the-approved-schema'");
        }
        assertThatThrownBy(() -> TrainingSchemaPreflight.check(data, false)).hasMessageContaining("V2_SCHEMA_UNSUPPORTED");
        assertThat(migrate(data, "before-refused")).isNotZero();
    }

    @Test void trainingMigrationAcceptsLegacyDatabaseBeforeRepositoryTableWasAdded() throws Exception {
        Path data = root.resolve("before-repositories");
        var legacy = new SqliteStore(data.toString(), new ObjectMapper());
        legacy.insertMaterial("project", "PROJECT", "合成项目", "原始资料", "2026-10-20");
        legacy.jdbc.execute("DROP TABLE project_repositories");
        assertThat(migrate(data, "before-repository-support")).isZero();
        TrainingSchemaPreflight.check(data, true);
        assertThat(legacy.material("project").content()).isEqualTo("原始资料");
        assertThat(legacy.jdbc.queryForObject("SELECT count(*) FROM project_repositories", Integer.class)).isZero();
    }

    private int migrate(Path data, String backup) throws Exception {
        var process = new ProcessBuilder("python3", "../scripts/prepare-training.py", "--data-dir", data.toString(),
                "--backup", root.resolve(backup).toString(), "--confirm-maintenance").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) System.out.println(output);
        return exit;
    }

    @Test void missingDatabaseStillBacksUpExistingMaterials() throws Exception {
        Path data=root.resolve("uploads-only");
        java.nio.file.Files.createDirectories(data.resolve("uploads"));
        java.nio.file.Files.writeString(data.resolve("uploads/source.txt"),"需保留的合成资料");
        assertThat(migrate(data,"uploads-backup")).isZero();
        assertThat(root.resolve("uploads-backup/uploads/source.txt")).exists();
        assertThat(java.nio.file.Files.readString(root.resolve("uploads-backup/uploads/source.txt"))).isEqualTo("需保留的合成资料");
    }

    @Test void unknownSchemaObjectsAndChangedLiteralAreRejected() throws Exception {
        for(String mutation:new String[]{"CREATE TABLE v2_unknown(id TEXT)","CREATE TRIGGER unknown_write AFTER INSERT ON sessions BEGIN DELETE FROM reports; END", "PRAGMA writable_schema=ON;UPDATE sqlite_master SET sql=replace(sql,'''PENDING''','''pending''') WHERE name='v2_operations';PRAGMA writable_schema=OFF"}) {
            Path data=root.resolve(java.util.UUID.randomUUID().toString());
            assertThat(migrate(data,"backup-"+data.getFileName())).isZero();
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("interview.db"));var s=c.createStatement()) {
                for(String sql:mutation.split(";(?=UPDATE|PRAGMA)"))s.execute(sql);
            }
            assertThatThrownBy(()->TrainingSchemaPreflight.check(data,false)).hasMessageContaining("V2_SCHEMA_UNSUPPORTED");
            assertThat(migrate(data,"refuse-"+data.getFileName())).isNotZero();
        }
    }

    @Test void interruptedDdlRollsBackAndDirectoryBackupCanBeRestored() throws Exception {
        Path data=root.resolve("rollback-data"),backup=root.resolve("rollback-backup");
        var legacy=new SqliteStore(data.toString(),new ObjectMapper());
        legacy.jdbc.update("INSERT INTO sessions VALUES('kept','{\"id\":\"kept\"}','2026-10-15')");
        java.nio.file.Files.createDirectories(data.resolve("uploads"));java.nio.file.Files.writeString(data.resolve("uploads/kept.txt"),"合成回退资料");
        String script="""
            import runpy,sqlite3,sys
            module=runpy.run_path('../scripts/prepare-training.py',run_name='migration_test')
            connect=sqlite3.connect
            class Interrupted:
                def __init__(self,c): self.c=c;self.created=0
                def __enter__(self): self.c.__enter__();return self
                def __exit__(self,*a): return self.c.__exit__(*a)
                def execute(self,sql,*args):
                    if sql.strip().startswith('CREATE TABLE v2_'):
                        self.created+=1
                        if self.created==3: raise sqlite3.OperationalError('合成中断')
                    return self.c.execute(sql,*args)
            sqlite3.connect=lambda *a,**k: Interrupted(connect(*a,**k))
            sys.argv=['migrate-v2.py','--data-dir',sys.argv[1],'--backup',sys.argv[2],'--confirm-maintenance']
            module['main']()
            """;
        var process=new ProcessBuilder("python3","-c",script,data.toString(),backup.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();assertThat(process.waitFor()).isNotZero();
        assertThat(legacy.jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name LIKE 'v2_%'",Integer.class)).isZero();
        assertThat(legacy.jdbc.queryForObject("SELECT payload FROM sessions WHERE id='kept'",String.class)).isEqualTo("{\"id\":\"kept\"}");
        assertThat(migrate(data,"before-success")).isZero();TrainingSchemaPreflight.check(data,true);
        // 此处没有应用进程；隔离迁移后目录，完整复制迁移前备份，演练停服回退。
        java.nio.file.Files.move(data,root.resolve("after-migration"));
        try(var paths=java.nio.file.Files.walk(backup)){for(Path source:paths.toList()){Path target=data.resolve(backup.relativize(source));if(java.nio.file.Files.isDirectory(source))java.nio.file.Files.createDirectories(target);else java.nio.file.Files.copy(source,target);}}
        TrainingSchemaPreflight.check(data,false);
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+data.resolve("interview.db"));var s=c.createStatement();var r=s.executeQuery("SELECT payload FROM sessions WHERE id='kept'")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("{\"id\":\"kept\"}");}
        assertThat(java.nio.file.Files.readString(data.resolve("uploads/kept.txt"))).isEqualTo("合成回退资料");
    }

    @Test void allFiveLegacyTablesAndUploadedBytesRemainUnchangedAcrossMigrationAndReopen() throws Exception {
        Path data=root.resolve("complete-legacy");var legacy=new SqliteStore(data.toString(),new ObjectMapper());
        String time="2026-10-15T00:00:00Z";
        legacy.jdbc.update("INSERT INTO materials VALUES(?,?,?,?,?,?)","resume","RESUME","合成简历","个人负责事务接口；没有业绩数字。",time,time);
        legacy.jdbc.update("INSERT INTO materials VALUES(?,?,?,?,?,?)","jd","JD","合成岗位","熟悉 Java 事务与幂等。",time,time);
        legacy.jdbc.update("INSERT INTO materials VALUES(?,?,?,?,?,?)","project","PROJECT","合成项目","订单服务，容量未知。",time,time);
        byte[] uploaded="合成原始附件\n个人贡献与团队成果分别保留。🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.createDirectories(data.resolve("uploads"));java.nio.file.Files.write(data.resolve("uploads/synthetic.txt"),uploaded);
        legacy.jdbc.update("INSERT INTO documents (id,material_id,name,storage_name,content,truncated,created_at) VALUES(?,?,?,?,?,?,?)","doc","project","附件.txt","synthetic.txt","合成附件提取正文",0,time);
        for(String mode:new String[]{"QUICK","RESUME","PROJECT"}) {
            String id="session-"+mode;
            String payload=new ObjectMapper().writeValueAsString(java.util.Map.of("id",id,"status","COMPLETED","mode",mode,"reportStatus","READY","messages",new Object[]{java.util.Map.of("role","ASSISTANT","content","合成问题：解释事务边界？"),java.util.Map.of("role","USER","content","合成原答：本地事务不保证外部支付只执行一次。\n未知容量。")},"mock",true));
            legacy.jdbc.update("INSERT INTO sessions VALUES(?,?,?)",id,payload,time);
            legacy.jdbc.update("INSERT INTO reports VALUES(?,?)",id,"{\"questions\":[{\"questionIndex\":0,\"uncertain\":[\"合成未知项\"]}],\"mock\":true}");
        }
        legacy.jdbc.update("INSERT INTO predictions VALUES(?,?,?,?)","prediction","resume","{\"questions\":[{\"question\":\"合成押题\",\"reason\":\"简历未说明事务边界\"}],\"mock\":true}",time);
        var before=new java.util.LinkedHashMap<String,java.util.List<java.util.Map<String,Object>>>();
        for(String table:new String[]{"materials","documents","sessions","predictions","reports"})before.put(table,legacy.jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        assertThat(migrate(data,"complete-legacy-backup")).isZero();TrainingSchemaPreflight.check(data,true);
        var reopened=new SqliteStore(data.toString(),new ObjectMapper());
        for(var entry:before.entrySet())assertThat(reopened.jdbc.queryForList("SELECT * FROM "+entry.getKey()+" ORDER BY 1")).as(entry.getKey()).isEqualTo(entry.getValue());
        assertThat(java.nio.file.Files.readAllBytes(data.resolve("uploads/synthetic.txt"))).isEqualTo(uploaded);
        assertThat(java.nio.file.Files.readAllBytes(root.resolve("complete-legacy-backup/uploads/synthetic.txt"))).isEqualTo(uploaded);
        assertThat(reopened.jdbc.queryForObject("SELECT count(*) FROM v2_schema_migrations",Integer.class)).isEqualTo(1);
    }

}
