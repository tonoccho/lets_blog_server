package com.letsblog.common.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実Spring Context(HikariDataSource + 後処理)で、起動・計上・コンテキスト終了時のclose呼び出しを確認する(issue #1736)。 */
class DbQueryMetricsSpringContextTest {

    @Configuration
    static class Config {
        @Bean
        static BeanPostProcessor dbQueryMetricsPostProcessor() {
            return new DbQueryMetricsDataSourcePostProcessor();
        }

        @Bean
        HikariDataSource dataSource() {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            pool.setMaximumPoolSize(2);
            return pool;
        }
    }

    @AfterEach
    void tearDown() {
        DbQueryRecorder.end();
    }

    @Test
    void コンテキストが起動しクエリが計上され終了時にプールが閉じられる() throws Exception {
        HikariDataSource pool;
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
        try {
            DataSource bean = context.getBean(DataSource.class);
            assertTrue(bean instanceof AutoCloseable, "プロキシは対象の全インターフェース(AutoCloseable)を保つ");
            DbQueryRecorder.begin(DbQueryRecorder.DEFAULT_SLOW_QUERY_THRESHOLD_MS,
                    DbQueryRecorder.DEFAULT_REPEAT_THRESHOLD);
            try (Connection c = bean.getConnection(); Statement s = c.createStatement()) {
                s.execute("select 1");
            }
            assertEquals(1, DbQueryRecorder.end().queries());
            pool = bean.unwrap(HikariDataSource.class);
            assertFalse(pool.isClosed());
        } finally {
            context.close();
        }
        assertTrue(pool.isClosed(), "コンテキスト終了でHikariプールのcloseが呼ばれる");
    }
}
