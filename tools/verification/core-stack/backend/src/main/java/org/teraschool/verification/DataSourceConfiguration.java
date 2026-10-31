package org.teraschool.verification;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.session.jdbc.config.annotation.SpringSessionTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** 业务与会话分别使用受限账号；超级用户仅在临时库迁移和测试核对时使用。 */
@Configuration
class DataSourceConfiguration {
    @Bean @Primary
    HikariDataSource appDataSource() { return pool("probe_app", "PROBE_APP_PASSWORD", "probe-app"); }

    @Bean @SpringSessionDataSource
    HikariDataSource sessionDataSource() { return pool("probe_session", "PROBE_SESSION_PASSWORD", "probe-session"); }

    @Bean @Primary
    PlatformTransactionManager appTransactionManager(@Qualifier("appDataSource") DataSource source) {
        return new JdbcTransactionManager(source);
    }

    @Bean @SpringSessionTransactionManager
    PlatformTransactionManager sessionTransactionManager(@Qualifier("sessionDataSource") DataSource source) {
        return new JdbcTransactionManager(source);
    }

    private HikariDataSource pool(String user, String passwordVariable, String name) {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(required("PROBE_JDBC_URL"));
        source.setUsername(user);
        source.setPassword(required(passwordVariable));
        source.setPoolName(name);
        // 故意只留一条业务连接，强制验证租户切换、回滚和池复用。
        source.setMaximumPoolSize(1);
        source.setMinimumIdle(0);
        return source;
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少隔离验证配置：" + name);
        return value;
    }
}
