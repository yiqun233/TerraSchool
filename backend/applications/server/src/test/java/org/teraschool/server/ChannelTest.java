package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.admissions.api.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      READER = UUID.randomUUID();
  @Autowired ChannelQueries queries;
  @Autowired ChannelCommands commands;
  @Autowired org.teraschool.platform.api.TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry registry) {
    EngineeringTest.config(registry);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,'渠道机构')", TENANT);
    for (var school : List.of(SCHOOL, OTHER))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','渠道学校')",
          school,
          TENANT,
          school);
    for (var user : List.of(OPS, READER))
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          user,
          TENANT,
          "channel-" + user,
          "unused");
    for (var action : List.of("CHANNEL_READ", "CHANNEL_MAINTAIN"))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'CHANNEL_READ','ALLOW')",
        TENANT,
        READER,
        SCHOOL);
  }

  <T> T asActor(UUID user, Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(EngineeringTest.principal(user), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  @Test
  void addressRespectsSchoolPermissionsAndSharedVersion() throws Exception {
    var channel =
        asActor(
            OPS,
            () ->
                commands.create(
                    new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "地址渠道")));
    assertThat(asActor(READER, () -> queries.address(channel.id())).address()).isEmpty();
    var saved =
        asActor(
            OPS,
            () ->
                commands.updateAddress(
                    channel.id(), new ChannelCommands.ChannelAddressUpdate("  上海市示例路  ", 0L)));
    assertThat(saved.address()).isEqualTo("上海市示例路");
    assertThat(saved.version()).isEqualTo(1);
    assertThat(asActor(READER, () -> queries.address(channel.id()))).isEqualTo(saved);
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        commands.updateAddress(
                            channel.id(), new ChannelCommands.ChannelAddressUpdate("其他地址", 1L))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.update(
                            channel.id(), new ChannelCommands.ChannelUpdate("新名称", true, 0L))))
        .isInstanceOf(ChannelConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.updateAddress(
                            channel.id(), new ChannelCommands.ChannelAddressUpdate("过期地址", 0L))))
        .isInstanceOf(ChannelConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.updateAddress(
                            channel.id(),
                            new ChannelCommands.ChannelAddressUpdate("x".repeat(51), 1L))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            count(
                "select count(*) from audit.channel_event where tenant_id=? and channel_id=? and action='UPDATED' and changed_fields='address'",
                TENANT,
                channel.id()))
        .isEqualTo(1);
    var other = UUID.randomUUID();
    update(
        "insert into admissions.channel(id,tenant_id,school_id,name) values (?,?,?,'其他学校渠道')",
        other,
        TENANT,
        OTHER);
    assertThatThrownBy(() -> asActor(OPS, () -> queries.address(other)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    var response = asActor(READER, () -> new ChannelHttp(queries, commands).address(channel.id()));
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
  }

  @Test
  void createIsIdempotentAndConflictingReplayIsRejected() {
    var request = new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "口碑推荐");
    var created = asActor(OPS, () -> commands.create(request));
    assertThat(asActor(OPS, () -> commands.create(request))).isEqualTo(created);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.create(
                            new ChannelCommands.ChannelCreate(
                                request.requestId(), SCHOOL, "其他渠道"))))
        .isInstanceOf(ChannelConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    var updated =
        asActor(
            OPS,
            () ->
                commands.update(
                    created.id(), new ChannelCommands.ChannelUpdate("口碑渠道", false, 0L)));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.active()).isFalse();
    assertThat(asActor(OPS, () -> commands.create(request))).isEqualTo(created);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.update(
                            created.id(), new ChannelCommands.ChannelUpdate("过期修改", true, 0L))))
        .isInstanceOf(ChannelConflict.class)
        .hasMessage("VERSION_CONFLICT");
  }

  @Test
  void readOnlyAndOutOfScopeCannotMutateOrReadAnotherSchool() {
    var channel =
        asActor(
            OPS,
            () ->
                commands.create(
                    new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "公开课咨询")));
    assertThat(asActor(READER, () -> queries.list(SCHOOL, 0, 20)).items())
        .extracting(Channel::id)
        .contains(channel.id());
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        commands.update(
                            channel.id(), new ChannelCommands.ChannelUpdate("修改", true, 0L))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> asActor(OPS, () -> queries.list(OTHER, 0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.create(
                            new ChannelCommands.ChannelCreate(UUID.randomUUID(), OTHER, "越范围"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void validationAndPaginationAreExplicit() {
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        commands.create(
                            new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "  "))))
        .isInstanceOf(IllegalArgumentException.class);
    for (int i = 0; i < 3; i++) {
      final int n = i;
      asActor(
          OPS,
          () ->
              commands.create(
                  new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "分页" + n)));
    }
    var first = asActor(OPS, () -> queries.list(SCHOOL, 0, 2));
    var second = asActor(OPS, () -> queries.list(SCHOOL, 1, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.hasMore()).isTrue();
    assertThat(first.items())
        .extracting(Channel::id)
        .doesNotContainAnyElementsOf(second.items().stream().map(Channel::id).toList());
  }

  @Test
  void otherTenantCannotReadOrModifyChannel() throws Exception {
    var tenant = UUID.randomUUID();
    var school = UUID.randomUUID();
    var user = UUID.randomUUID();
    update("insert into identity.tenant(id,name) values (?,'其他机构')", tenant);
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','其他机构学校')",
        school,
        tenant,
        school);
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        user,
        tenant,
        "channel-foreign-" + user,
        "unused");
    for (var action : List.of("CHANNEL_READ", "CHANNEL_MAINTAIN"))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          tenant,
          user,
          school,
          action);
    var channel =
        asActor(
            OPS,
            () ->
                commands.create(
                    new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "本机构渠道")));
    assertThatThrownBy(
            () ->
                asActor(
                    user,
                    () ->
                        commands.update(
                            channel.id(), new ChannelCommands.ChannelUpdate("越租户", true, 0L))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> asActor(user, () -> queries.list(SCHOOL, 0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void deniedAndRolledBackWritesHaveIndependentAudit() throws Exception {
    var request = new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "回滚渠道");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        tx.execute(
                            TENANT,
                            () -> {
                              commands.create(request);
                              throw new IllegalStateException("回滚外层事务");
                            })))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            count(
                "select count(*) from admissions.channel where tenant_id=? and name='回滚渠道'",
                TENANT))
        .isZero();
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and actor_id=? and action='CHANNEL_CREATE' and code='TRANSACTION_ROLLED_BACK'",
                TENANT,
                OPS))
        .isGreaterThanOrEqualTo(1);
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        commands.create(
                            new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "只读写入"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and actor_id=? and action='CHANNEL_CREATE' and code='ACCESS_DENIED'",
                TENANT,
                READER))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void waitingWriteRechecksPermissionAfterRevocation() throws Exception {
    var user = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        user,
        TENANT,
        "channel-queued-" + user,
        "unused");
    for (var action : List.of("CHANNEL_READ", "CHANNEL_MAINTAIN"))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          TENANT,
          user,
          SCHOOL,
          action);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (var connection = EngineeringTest.admin()) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
        lock.setString(1, "authorization:" + TENANT);
        lock.execute();
      }
      try (var revoke =
          connection.prepareStatement(
              "delete from identity.account_permission where tenant_id=? and account_id=? and action='CHANNEL_MAINTAIN'")) {
        revoke.setObject(1, TENANT);
        revoke.setObject(2, user);
        revoke.executeUpdate();
      }
      var queued =
          executor.submit(
              () ->
                  asActor(
                      user,
                      () ->
                          commands.create(
                              new ChannelCommands.ChannelCreate(
                                  UUID.randomUUID(), SCHOOL, "撤权排队"))));
      boolean waiting = false;
      for (int i = 0; i < 100; i++) {
        if (count(
                "select count(*) from pg_stat_activity where usename='ts_app' and wait_event='advisory' and query like '%pg_advisory_xact_lock%'")
            > 0) {
          waiting = true;
          break;
        }
        Thread.sleep(25);
      }
      assertThat(waiting).as("渠道写入等待机构锁").isTrue();
      connection.commit();
      assertThatThrownBy(() -> queued.get(20, java.util.concurrent.TimeUnit.SECONDS))
          .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(
              count(
                  "select count(*) from admissions.channel where tenant_id=? and name='撤权排队'",
                  TENANT))
          .isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  private long count(String query, Object... args) throws Exception {
    try (var connection = EngineeringTest.admin();
        var statement = connection.prepareStatement(query)) {
      for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }
}
