package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
import java.util.concurrent.*;
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
class CustomerTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER = UUID.randomUUID(),
      A = UUID.randomUUID(),
      B = UUID.randomUUID(),
      LIMITED = UUID.randomUUID();
  @Autowired CustomerCommands commands;
  @Autowired CustomerQueries queries;
  @Autowired ChannelCommands channels;
  @Autowired org.teraschool.platform.api.TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,'客户机构')", TENANT);
    for (var school : List.of(SCHOOL, OTHER))
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','客户学校')",
          school,
          TENANT,
          school);
    for (var user : List.of(A, B, LIMITED)) {
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          user,
          TENANT,
          "customer-" + user,
          "unused");
      for (var action : List.of("CUSTOMER_READ", "CUSTOMER_MAINTAIN")) grant(user, SCHOOL, action);
    }
    for (var action : List.of("CUSTOMER_PHONE_READ", "CHANNEL_READ", "CHANNEL_MAINTAIN"))
      grant(A, SCHOOL, action);
    for (var action : List.of("CUSTOMER_READ", "CUSTOMER_MAINTAIN", "CUSTOMER_PHONE_READ"))
      grant(A, OTHER, action);
    grant(B, SCHOOL, "CUSTOMER_PHONE_READ");
  }

  static void grant(UUID user, UUID school, String action) throws Exception {
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
        TENANT,
        user,
        school,
        action);
  }

  <T> T as(UUID user, Supplier<T> work) {
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

  CustomerCommands.CustomerCreate input(String name, String phone) {
    return new CustomerCommands.CustomerCreate(UUID.randomUUID(), SCHOOL, name, phone, null, null);
  }

  CustomerCommands.CustomerCreate withToken(CustomerCommands.CustomerCreate input, UUID token) {
    return new CustomerCommands.CustomerCreate(
        input.requestId(), input.schoolId(), input.name(), input.phone(), input.channelId(), token);
  }

  Customer created(UUID user, CustomerCommands.CustomerCreate command) {
    var result = as(user, () -> commands.create(command));
    assertThat(result.status()).isEqualTo("CREATED");
    return result.customer();
  }

  @Test
  void duplicateNeedsServerChallengeAndExplicitConfirmation() throws Exception {
    var first = created(A, input("甲", "138 0000-1001"));
    assertThat(first.maskedPhone()).isEqualTo("****1001");
    var request = input("乙", "13800001001");
    var challenge = as(A, () -> commands.create(request));
    assertThat(challenge.status()).isEqualTo("DUPLICATE_CONFIRMATION_REQUIRED");
    assertThat(challenge.customer()).isNull();
    assertThat(challenge.confirmationToken()).isNotNull();
    assertThat(as(A, () -> commands.create(request)).confirmationToken())
        .isEqualTo(challenge.confirmationToken());
    assertThatThrownBy(() -> as(A, () -> commands.create(withToken(request, UUID.randomUUID()))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("DUPLICATE_CONFIRMATION_INVALID");
    var altered =
        new CustomerCommands.CustomerCreate(
            request.requestId(),
            SCHOOL,
            "其他名字",
            request.phone(),
            null,
            challenge.confirmationToken());
    assertThatThrownBy(() -> as(A, () -> commands.create(altered)))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    var confirmed = withToken(request, challenge.confirmationToken());
    var second = created(A, confirmed);
    assertThat(second.id()).isNotEqualTo(first.id());
    assertThat(created(A, confirmed)).isEqualTo(second);
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action='CREATED' and duplicate_confirmed",
                TENANT,
                second.id()))
        .isEqualTo(1);
    var reuse =
        new CustomerCommands.CustomerCreate(
            UUID.randomUUID(),
            SCHOOL,
            request.name(),
            request.phone(),
            null,
            challenge.confirmationToken());
    assertThatThrownBy(() -> as(A, () -> commands.create(reuse)))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("DUPLICATE_CONFIRMATION_INVALID");
    assertThatThrownBy(
            () ->
                as(
                    A,
                    () ->
                        commands.create(
                            new CustomerCommands.CustomerCreate(
                                request.requestId(),
                                SCHOOL,
                                "修改名称",
                                request.phone(),
                                null,
                                challenge.confirmationToken()))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void expiredOrOtherActorChallengeCannotConfirm() throws Exception {
    created(A, input("甲", "13800001020"));
    var request = input("乙", "13800001020");
    var challenge = as(A, () -> commands.create(request));
    update(
        "update admissions.customer_duplicate_challenge set expires_at=CURRENT_TIMESTAMP - interval '1 second' where id=?",
        challenge.confirmationToken());
    assertThatThrownBy(
            () -> as(A, () -> commands.create(withToken(request, challenge.confirmationToken()))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("DUPLICATE_CONFIRMATION_INVALID");
    created(B, input("丙", "13800001020"));
    var other =
        new CustomerCommands.CustomerCreate(
            request.requestId(),
            SCHOOL,
            request.name(),
            request.phone(),
            null,
            challenge.confirmationToken());
    assertThatThrownBy(() -> as(B, () -> commands.create(other)))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("DUPLICATE_CONFIRMATION_INVALID");
  }

  @Test
  void otherCreatorCannotSeeOrRevealAndDuplicateDoesNotLeak() {
    var existing = created(A, input("本人", "13800001002"));
    assertThat(as(B, () -> queries.list(SCHOOL, 0, 100)).items())
        .extracting(Customer::id)
        .doesNotContain(existing.id());
    assertThatThrownBy(() -> as(B, () -> commands.revealPhone(existing.id())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(created(B, input("别人", "13800001002")).id()).isNotEqualTo(existing.id());
  }

  @Test
  void phoneRequiresSeparatePermissionAndIndependentCommittedAudit() throws Exception {
    grant(LIMITED, SCHOOL, "CUSTOMER_PHONE_READ");
    var customer = created(LIMITED, input("脱敏", "13800001003"));
    update(
        "delete from identity.account_permission where tenant_id=? and account_id=? and action='CUSTOMER_PHONE_READ'",
        TENANT,
        LIMITED);
    assertThatThrownBy(() -> as(LIMITED, () -> commands.create(input("无电话权限", "13800001030"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(as(LIMITED, () -> queries.list(SCHOOL, 0, 100)).items())
        .filteredOn(row -> row.id().equals(customer.id()))
        .singleElement()
        .satisfies(row -> assertThat(row.toString()).doesNotContain("13800001003"));
    assertThatThrownBy(() -> as(LIMITED, () -> commands.revealPhone(customer.id())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    var own = created(A, input("审计", "13800001004"));
    assertThatThrownBy(() -> as(A, () -> tx.execute(TENANT, () -> commands.revealPhone(own.id()))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("CUSTOMER_PHONE_REQUIRES_INDEPENDENT_TRANSACTION");
    var response = as(A, () -> new CustomerHttp(queries, commands).phone(own.id()));
    assertThat(response.getBody().phone()).isEqualTo("13800001004");
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action='PHONE_VIEWED'",
                TENANT,
                own.id()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and actor_id=? and action='CUSTOMER_PHONE_VIEW' and code='ACCESS_DENIED'",
                TENANT,
                LIMITED))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void otherTenantCannotAccessCustomer() throws Exception {
    var tenant = UUID.randomUUID();
    var school = UUID.randomUUID();
    var actor = UUID.randomUUID();
    update("insert into identity.tenant(id,name) values (?,'另一机构')", tenant);
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','另一机构学校')",
        school,
        tenant,
        school);
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        actor,
        tenant,
        "foreign-" + actor,
        "unused");
    for (var action : List.of("CUSTOMER_READ", "CUSTOMER_PHONE_READ"))
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,?,'ALLOW')",
          tenant,
          actor,
          school,
          action);
    var own = created(A, input("机构甲", "13800001022"));
    assertThatThrownBy(() -> as(actor, () -> queries.list(SCHOOL, 0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> as(actor, () -> commands.revealPhone(own.id())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void duplicateDetectionIncludesReadableSchoolsOnly() {
    var original =
        new CustomerCommands.CustomerCreate(
            UUID.randomUUID(), OTHER, "跨校本人", "13800001005", null, null);
    created(A, original);
    assertThat(as(A, () -> commands.create(input("重复", "13800001005"))).status())
        .isEqualTo("DUPLICATE_CONFIRMATION_REQUIRED");
  }

  @Test
  void cancellationTombstonePreventsDelayedCreateAndDuplicateAuditUsesRequestSchool()
      throws Exception {
    var original =
        new CustomerCommands.CustomerCreate(
            UUID.randomUUID(), OTHER, "跨校原客户", "13800001031", null, null);
    created(A, original);
    var request = input("新校重复", "13800001031");
    var challenge = as(A, () -> commands.create(request));
    assertThat(challenge.status()).isEqualTo("DUPLICATE_CONFIRMATION_REQUIRED");
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and school_id=? and request_id=? and action='DUPLICATE_DETECTED' and customer_id is null",
                TENANT,
                SCHOOL,
                request.requestId()))
        .isEqualTo(1);
    assertThat(as(A, () -> commands.cancel(request)).status()).isEqualTo("CANCELLED");
    assertThat(
            as(A, () -> commands.create(withToken(request, challenge.confirmationToken())))
                .status())
        .isEqualTo("CANCELLED");
    assertThat(as(A, () -> commands.cancel(request)).status()).isEqualTo("CANCELLED");
    assertThat(
            count(
                "select count(*) from admissions.customer where tenant_id=? and school_id=? and name='新校重复'",
                TENANT,
                SCHOOL))
        .isZero();
  }

  @Test
  void cancellationAfterSuccessfulCreateReportsExistingResult() {
    var request = input("已完成客户", "13800001032");
    var customer = created(A, request);
    var result = as(A, () -> commands.cancel(request));
    assertThat(result.status()).isEqualTo("CREATED");
    assertThat(result.customer()).isEqualTo(customer);
  }

  @Test
  void channelMustBeReadableActiveAndInSameSchool() {
    var channel =
        as(
            A,
            () ->
                channels.create(
                    new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "客户来源")));
    var command =
        new CustomerCommands.CustomerCreate(
            UUID.randomUUID(), SCHOOL, "关联来源", "13800001006", channel.id(), null);
    assertThat(created(A, command).channelId()).isEqualTo(channel.id());
    as(
        A,
        () ->
            channels.update(
                channel.id(), new ChannelCommands.ChannelUpdate(channel.name(), false, 0L)));
    assertThatThrownBy(
            () ->
                as(
                    A,
                    () ->
                        commands.create(
                            new CustomerCommands.CustomerCreate(
                                UUID.randomUUID(),
                                SCHOOL,
                                "停用来源",
                                "13800001007",
                                channel.id(),
                                null))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("CHANNEL_UNAVAILABLE");
  }

  @Test
  void revokedChannelReadDoesNotLeakChannelIdentifierOnListOrReplay() throws Exception {
    var user = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        user,
        TENANT,
        "customer-channel-" + user,
        "unused");
    for (var action :
        List.of(
            "CUSTOMER_READ",
            "CUSTOMER_MAINTAIN",
            "CUSTOMER_PHONE_READ",
            "CHANNEL_READ",
            "CHANNEL_MAINTAIN")) grant(user, SCHOOL, action);
    var channel =
        as(
            user,
            () ->
                channels.create(
                    new ChannelCommands.ChannelCreate(UUID.randomUUID(), SCHOOL, "权限渠道")));
    var command =
        new CustomerCommands.CustomerCreate(
            UUID.randomUUID(), SCHOOL, "渠道受限客户", "13800001033", channel.id(), null);
    var customer = created(user, command);
    assertThat(customer.channelId()).isEqualTo(channel.id());
    update(
        "delete from identity.account_permission where tenant_id=? and account_id=? and action='CHANNEL_READ'",
        TENANT,
        user);
    assertThat(as(user, () -> queries.list(SCHOOL, 0, 20)).items())
        .singleElement()
        .satisfies(item -> assertThat(item.channelId()).isNull());
    assertThat(as(user, () -> commands.create(command)).customer().channelId()).isNull();
  }

  @Test
  void concurrentDuplicatesRequireConfirmationForOneRequest() throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return as(A, () -> commands.create(input("并发", "13800001008"))).status();
                }));
      start.countDown();
      var statuses = new ArrayList<String>();
      for (var f : futures) statuses.add(f.get(20, TimeUnit.SECONDS));
      assertThat(statuses).containsExactlyInAnyOrder("CREATED", "DUPLICATE_CONFIRMATION_REQUIRED");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void waitingCustomerListRechecksPermissionAfterRevocation() throws Exception {
    var user = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        user,
        TENANT,
        "customer-queued-" + user,
        "unused");
    grant(user, SCHOOL, "CUSTOMER_READ");
    var executor = Executors.newSingleThreadExecutor();
    try (var connection = EngineeringTest.admin()) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
        lock.setString(1, "authorization:" + TENANT);
        lock.execute();
      }
      try (var revoke =
          connection.prepareStatement(
              "delete from identity.account_permission where tenant_id=? and account_id=? and action='CUSTOMER_READ'")) {
        revoke.setObject(1, TENANT);
        revoke.setObject(2, user);
        revoke.executeUpdate();
      }
      var queued = executor.submit(() -> as(user, () -> queries.list(SCHOOL, 0, 20)));
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
      assertThat(waiting).isTrue();
      connection.commit();
      assertThatThrownBy(() -> queued.get(20, TimeUnit.SECONDS))
          .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    } finally {
      executor.shutdownNow();
    }
  }

  private long count(String query, Object... args) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement(query)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }
}
