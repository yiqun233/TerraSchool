package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 在隔离真实PG事务中运行本地种子；回滚全部合成资料，不触碰WSL日常库。 */
class LocalAuthorizationSeedTest {
  @Test
  void freshSeedAndRepeatedPreparationPreserveEditedPermissions() throws Exception {
    Path repository = Path.of("").toAbsolutePath();
    while (repository != null && !Files.isRegularFile(repository.resolve("tools/dev/seed.sql")))
      repository = repository.getParent();
    assertThat(repository).as("从模块目录定位仓库种子，不依赖未传播到测试JVM的Maven系统属性").isNotNull();
    String seed =
        Files.readString(repository.resolve("tools/dev/seed.sql"))
            .replace("@@LOGIN_PASSWORD@@", "'" + UUID.randomUUID() + "'");
    try (var c = EngineeringTest.admin();
        var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute(seed);
      var row =
          s.executeQuery(
              "select count(*) from identity.account_permission p join identity.account a on a.id=p.account_id where a.login='local-admin' and p.action='AUTHORIZATION_MANAGE'");
      row.next();
      assertThat(row.getInt(1)).isEqualTo(2);
      row =
          s.executeQuery(
              "select count(*) from identity.permission_role where code in ('LOCAL_ORG_READER','LOCAL_ORG_MAINTAINER')");
      row.next();
      assertThat(row.getInt(1)).isEqualTo(2);
      s.execute(
          "delete from identity.account_permission where account_id=(select id from identity.account where login='local-admin') and action='AUTHORIZATION_MANAGE';update identity.account set login='renamed-local-admin' where login='local-admin'");
      s.execute(seed);
      row =
          s.executeQuery(
              "select count(*) from identity.account_permission p join identity.account a on a.id=p.account_id where a.login='renamed-local-admin' and p.action='AUTHORIZATION_MANAGE'");
      row.next();
      assertThat(row.getInt(1)).isZero();
      row =
          s.executeQuery(
              "select count(*) from dev.authorization_seed_history where seed_id='LOCAL-AUTHORIZATION-001'");
      row.next();
      assertThat(row.getInt(1)).isEqualTo(1);
      c.rollback();
    }
  }
}
