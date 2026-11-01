package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.DockerImageName;

/** 独立真实旧结构输入，防止只验证空库能执行V5却遗漏旧权限转换。 */
class PermissionMigrationTest {
  @Test
  void legacyFunctionScopeAndDenySurviveV5() throws Exception {
    String password = UUID.randomUUID().toString();
    try (var pg =
        new GenericContainer<>(DockerImageName.parse(System.getenv("TS_POSTGRES_IMAGE")))
            .withEnv("POSTGRES_PASSWORD", password)
            .withEnv("POSTGRES_DB", "permission_upgrade")
            .withExposedPorts(5432)
            // 镜像初始化会先启动临时PG再重启，不能只等端口第一次打开。
            .waitingFor(
                new WaitAllStrategy()
                    .withStrategy(
                        Wait.forLogMessage(
                            ".*database system is ready to accept connections.*\\s", 2))
                    .withStrategy(Wait.forListeningPort()))) {
      pg.start();
      String url =
          "jdbc:postgresql://"
              + pg.getHost()
              + ":"
              + pg.getMappedPort(5432)
              + "/permission_upgrade";
      try (var c = DriverManager.getConnection(url, "postgres", password);
          var s = c.createStatement()) {
        s.execute(
            "CREATE ROLE ts_app;CREATE ROLE ts_identity;CREATE ROLE ts_session;CREATE ROLE ts_audit;");
      }
      Flyway.configure()
          .dataSource(url, "postgres", password)
          .locations("classpath:db/migration")
          .target("4")
          .load()
          .migrate();
      UUID tenant = UUID.randomUUID(),
          school = UUID.randomUUID(),
          enabled = UUID.randomUUID(),
          disabled = UUID.randomUUID(),
          empty = UUID.randomUUID();
      try (var c = DriverManager.getConnection(url, "postgres", password);
          var s = c.createStatement()) {
        s.execute(
            "insert into identity.tenant(id,name) values ('"
                + tenant
                + "','迁移机构');insert into organization.unit(id,tenant_id,school_id,kind,name) values ('"
                + school
                + "','"
                + tenant
                + "','"
                + school
                + "','SCHOOL','迁移学校');");
        for (UUID id : new UUID[] {enabled, disabled, empty})
          s.execute(
              "insert into identity.account(id,tenant_id,login,password_hash,organization_read) values ('"
                  + id
                  + "','"
                  + tenant
                  + "','"
                  + id
                  + "','synthetic-hash',"
                  + !id.equals(disabled)
                  + ")");
        for (UUID id : new UUID[] {enabled, disabled})
          s.execute(
              "insert into identity.school_scope(account_id,tenant_id,school_id) values ('"
                  + id
                  + "','"
                  + tenant
                  + "','"
                  + school
                  + "')");
        for (String effect : new String[] {"ALLOW", "DENY"})
          s.execute(
              "insert into identity.organization_maintenance_permission(tenant_id,account_id,school_id,effect) values ('"
                  + tenant
                  + "','"
                  + enabled
                  + "','"
                  + school
                  + "','"
                  + effect
                  + "')");
      }
      Flyway.configure()
          .dataSource(url, "postgres", password)
          .locations("classpath:db/migration")
          .load()
          .migrate();
      try (var c = DriverManager.getConnection(url, "postgres", password);
          var s = c.createStatement()) {
        var r =
            s.executeQuery(
                "select count(*) from identity.account_permission where account_id='"
                    + enabled
                    + "' and action='ORGANIZATION_MAINTAIN'");
        r.next();
        assertThat(r.getInt(1)).isEqualTo(2);
        r =
            s.executeQuery(
                "select effect from identity.account_permission where account_id='"
                    + disabled
                    + "' and school_id is null and action='ORGANIZATION_READ'");
        r.next();
        assertThat(r.getString(1)).isEqualTo("DENY");
        r =
            s.executeQuery(
                "select count(*) from identity.account_permission where account_id='"
                    + empty
                    + "' and effect='ALLOW' and school_id is null");
        r.next();
        assertThat(r.getInt(1)).isEqualTo(1);
        r =
            s.executeQuery(
                "select count(*) from identity.account_permission where account_id='"
                    + disabled
                    + "' and school_id is not null and action='ORGANIZATION_READ'");
        r.next();
        // 暂时关闭功能时仍保留原学校分配，全局DENY负责阻止实际读取。
        assertThat(r.getInt(1)).isEqualTo(1);
        r =
            s.executeQuery(
                "select to_regclass('identity.school_scope'),to_regclass('identity.organization_maintenance_permission'),password_hash from identity.account where id='"
                    + enabled
                    + "'");
        r.next();
        assertThat(r.getObject(1)).isNull();
        assertThat(r.getObject(2)).isNull();
        assertThat(r.getString(3)).isEqualTo("synthetic-hash");
      }
    }
  }
}
