package cn.interview.mapper;

import org.springframework.jdbc.core.JdbcTemplate;

/** 测试专用：构造数据库异常或核验原始持久化记录，不向业务层暴露 JDBC。 */
public final class SqliteStoreTestAccess {
    private SqliteStoreTestAccess() {}
    public static JdbcTemplate jdbc(SqliteStore store) {return store.jdbc;}
}
