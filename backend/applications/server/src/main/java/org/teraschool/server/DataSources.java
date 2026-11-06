package org.teraschool.server;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.session.jdbc.config.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** 三类连接分别授权；密钥可由外部 YAML/configtree 覆盖，缺项拒绝启动。 */
@Configuration
class DataSources {
  private final Environment env;

  DataSources(Environment env) {
    this.env = env;
  }

  private HikariDataSource pool(String kind) {
    var ds = new HikariDataSource();
    ds.setJdbcUrl(env.getRequiredProperty("teraschool.database.url"));
    ds.setUsername(env.getRequiredProperty("teraschool.database." + kind + ".username"));
    ds.setPassword(env.getRequiredProperty("teraschool.database." + kind + ".password"));
    ds.setMaximumPoolSize(env.getProperty("teraschool.database.pool-size", Integer.class, 4));
    ds.setMinimumIdle(0);
    return ds;
  }

  @Bean
  @Primary
  HikariDataSource appDataSource() {
    return pool("app");
  }

  @Bean
  HikariDataSource identityDataSource() {
    return pool("identity");
  }

  @Bean
  HikariDataSource auditDataSource() {
    var source = pool("audit");
    source.setMaximumPoolSize(
        Math.min(2, env.getProperty("teraschool.database.pool-size", Integer.class, 4)));
    source.setConnectionTimeout(2000);
    return source;
  }

  @Bean
  HikariDataSource schedulerDataSource() {
    var source = pool("scheduler");
    source.setMaximumPoolSize(3);
    source.setConnectionTimeout(2000);
    return source;
  }

  /** 平台运营连接：只按 ts_platform 授权，读不到任何业务表。 */
  @Bean
  HikariDataSource platformDataSource() {
    var source = pool("platform");
    source.setMaximumPoolSize(
        Math.min(2, env.getProperty("teraschool.database.pool-size", Integer.class, 4)));
    source.setConnectionTimeout(2000);
    return source;
  }

  @Bean
  @SpringSessionDataSource
  HikariDataSource sessionDataSource() {
    return pool("session");
  }

  @Bean
  @Primary
  PlatformTransactionManager transactionManager(@Qualifier("appDataSource") DataSource ds) {
    return new JdbcTransactionManager(ds);
  }

  @Bean
  @SpringSessionTransactionManager
  PlatformTransactionManager sessionTransactionManager(
      @Qualifier("sessionDataSource") DataSource ds) {
    return new JdbcTransactionManager(ds);
  }
}
