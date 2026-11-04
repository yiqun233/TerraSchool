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
import org.teraschool.archive.api.*;

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
  @Autowired CustomerFollowupCommands followupCommands;
  @Autowired CustomerFollowupQueries followupQueries;
  @Autowired CustomerConversionService conversions;
  @Autowired StudentCommands studentCommands;
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
    for (var action : List.of("STUDENT_READ", "STUDENT_MAINTAIN")) grant(A, SCHOOL, action);
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
  void followupsAreOwnerOnlyIdempotentAndAuditedWithoutContent() throws Exception {
    var customer = created(A, input("跟进测试", "13900001234"));
    var command =
        new CustomerFollowupCommands.FollowupCreate(
            UUID.randomUUID(), "电话沟通：敏感内容", "2026-11-01", "2026-11-10");
    var first = as(A, () -> followupCommands.create(customer.id(), command));
    assertThat(as(A, () -> followupCommands.create(customer.id(), command))).isEqualTo(first);
    assertThat(as(A, () -> followupQueries.list(customer.id(), 0, 20)).items())
        .containsExactly(first);
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action='FOLLOWUP_VIEWED'",
                TENANT,
                customer.id()))
        .isEqualTo(1);
    assertThatThrownBy(
            () -> as(A, () -> tx.execute(TENANT, () -> followupQueries.list(customer.id(), 0, 20))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("CUSTOMER_FOLLOWUP_REQUIRES_INDEPENDENT_TRANSACTION");
    assertThatThrownBy(() -> as(B, () -> followupQueries.list(customer.id(), 0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> as(LIMITED, () -> followupQueries.list(customer.id(), 0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and actor_id=? and action='CUSTOMER_FOLLOWUP_READ' and code='ACCESS_DENIED'",
                TENANT,
                B))
        .isGreaterThanOrEqualTo(1);
    var response =
        as(
            A,
            () ->
                new CustomerHttp(queries, commands, followupQueries, followupCommands, conversions)
                    .followups(customer.id(), 0, 20));
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    var changed =
        new CustomerFollowupCommands.FollowupCreate(
            command.requestId(), "不同内容", command.occurredOn(), command.nextContactOn());
    assertThatThrownBy(() -> as(A, () -> followupCommands.create(customer.id(), changed)))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThatThrownBy(
            () ->
                as(
                    A,
                    () ->
                        followupCommands.create(
                            customer.id(),
                            new CustomerFollowupCommands.FollowupCreate(
                                UUID.randomUUID(), "内容", "2026-02-30", null))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action='FOLLOWUP_ADDED'",
                TENANT,
                customer.id()))
        .isEqualTo(1);
  }

  @Test
  void customerConversionCreatesStudentAndOwnerLinkAtomicallyAndIdempotently() throws Exception {
    var customer = created(A, input("王家长", "13900004567"));
    var requestId = UUID.randomUUID();
    var command =
        new ConvertCustomerToStudent(
            requestId,
            customer.version(),
            null,
            "王同学",
            null,
            "UNKNOWN",
            null,
            null,
            null,
            null,
            null,
            "GUARDIAN",
            false);
    var first = as(A, () -> conversions.convert(customer.id(), command));
    var replay = as(A, () -> conversions.convert(customer.id(), command));
    assertThat(replay.conversion()).isEqualTo(first.conversion());
    assertThat(replay.creation().profile().id()).isEqualTo(first.creation().profile().id());
    assertThat(
            count(
                "select count(*) from admissions.customer_student_link where tenant_id=? and customer_id=?",
                TENANT,
                customer.id()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from archive.student where tenant_id=? and id=?",
                TENANT,
                first.creation().profile().id()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from archive.student_contact sc join archive.contact c on c.tenant_id=sc.tenant_id and c.id=sc.contact_id where sc.student_id=? and c.name='王家长' and c.phone='13900004567' and sc.relation='GUARDIAN' and sc.is_primary",
                first.creation().profile().id()))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action='STUDENT_LINKED'",
                TENANT,
                customer.id()))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                as(
                    A,
                    () ->
                        conversions.convert(
                            customer.id(),
                            new ConvertCustomerToStudent(
                                requestId,
                                customer.version(),
                                null,
                                "不同姓名",
                                null,
                                "UNKNOWN",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "GUARDIAN",
                                false))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThatThrownBy(
            () ->
                as(
                    A,
                    () ->
                        conversions.convert(
                            customer.id(),
                            new ConvertCustomerToStudent(
                                UUID.randomUUID(),
                                customer.version(),
                                null,
                                "王同学",
                                null,
                                "UNKNOWN",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "GUARDIAN",
                                false))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("CUSTOMER_ALREADY_CONVERTED");
    assertThat(as(A, () -> conversions.link(customer.id()))).isEqualTo(first.conversion());
  }

  @Test
  void failedStudentCreationDoesNotLeaveCustomerConversionLink() throws Exception {
    as(
        A,
        () ->
            studentCommands.create(
                new CreateStudent(
                    UUID.randomUUID(),
                    SCHOOL,
                    null,
                    "证件已占用学员",
                    null,
                    "UNKNOWN",
                    null,
                    "OTHER",
                    "ABCD1234",
                    null,
                    null,
                    List.of(),
                    false)));
    var customer = created(A, input("待转家长", "13900004568"));
    var command =
        new ConvertCustomerToStudent(
            UUID.randomUUID(),
            customer.version(),
            null,
            "待转学生",
            null,
            "UNKNOWN",
            null,
            "OTHER",
            "ABCD1234",
            null,
            null,
            "GUARDIAN",
            false);
    assertThatThrownBy(() -> as(A, () -> conversions.convert(customer.id(), command)))
        .isInstanceOf(StudentConflict.class)
        .hasMessage("DUPLICATE_CERTIFICATE");
    assertThat(
            count(
                "select count(*) from admissions.customer_student_link where tenant_id=? and customer_id=?",
                TENANT,
                customer.id()))
        .isZero();
    assertThat(
            count(
                "select count(*) from archive.student where tenant_id=? and school_id=? and cert_code='ABCD1234'",
                TENANT,
                SCHOOL))
        .isEqualTo(1);
  }

  @Test
  void customerConversionRequiresPhoneReadPermission() throws Exception {
    var customer = created(A, input("需电话权限的联系人", "13900004569"));
    update(
        "delete from identity.account_permission where tenant_id=? and account_id=? and school_id=? and action='CUSTOMER_PHONE_READ'",
        TENANT,
        A,
        SCHOOL);
    try {
      assertThatThrownBy(
              () ->
                  as(
                      A,
                      () ->
                          conversions.convert(
                              customer.id(),
                              new ConvertCustomerToStudent(
                                  UUID.randomUUID(),
                                  customer.version(),
                                  null,
                                  "无权读取联系人的学员",
                                  null,
                                  "UNKNOWN",
                                  null,
                                  null,
                                  null,
                                  null,
                                  null,
                                  "GUARDIAN",
                                  false))))
          .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      assertThat(
              count(
                  "select count(*) from admissions.customer_student_link where tenant_id=? and customer_id=?",
                  TENANT,
                  customer.id()))
          .isZero();
    } finally {
      grant(A, SCHOOL, "CUSTOMER_PHONE_READ");
    }
  }

  @Test
  void latestFollowupControlsOwnerOnlyDueReminder() {
    var customer = created(A, input("提醒客户", "13900001236"));
    var first =
        new CustomerFollowupCommands.FollowupCreate(
            UUID.randomUUID(), "安排本周回访", "2026-11-01", "2026-11-10");
    var followup = as(A, () -> followupCommands.create(customer.id(), first));
    var reminders = as(A, () -> followupQueries.reminders(SCHOOL, "2026-11-10", 0, 20));
    assertThat(reminders.items())
        .anySatisfy(
            reminder -> {
              assertThat(reminder.customerId()).isEqualTo(customer.id());
              assertThat(reminder.id()).isEqualTo(followup.id());
              assertThat(reminder.nextContactOn()).isEqualTo("2026-11-10");
            });
    assertThat(as(B, () -> followupQueries.reminders(SCHOOL, "2026-11-10", 0, 20)).items())
        .noneMatch(reminder -> reminder.customerId().equals(customer.id()));

    as(
        A,
        () ->
            followupCommands.create(
                customer.id(),
                new CustomerFollowupCommands.FollowupCreate(
                    UUID.randomUUID(), "已完成回访", "2026-11-10", null)));
    assertThat(as(A, () -> followupQueries.reminders(SCHOOL, "2026-11-20", 0, 20)).items())
        .noneMatch(reminder -> reminder.customerId().equals(customer.id()));
  }

  @Test
  void sameOwnerCustomerMergePreservesHistoryAndCanBeReversed() throws Exception {
    var source = created(A, input("合并来源线索", "13900002001"));
    var primary = created(A, input("保留主线索", "13900002002"));
    var followup =
        as(
            A,
            () ->
                followupCommands.create(
                    source.id(),
                    new CustomerFollowupCommands.FollowupCreate(
                        UUID.randomUUID(), "合并前沟通", "2026-11-01", null)));

    var merged = as(A, () -> commands.merge(source.id(), primary.id(), 0, 0));

    assertThat(merged.primaryId()).isEqualTo(primary.id());
    assertThat(merged.sourceVersion()).isEqualTo(1);
    assertThat(merged.primaryVersion()).isEqualTo(1);
    assertThat(as(A, () -> queries.list(SCHOOL, 0, 100)).items())
        .extracting(Customer::id)
        .doesNotContain(source.id());
    assertThat(as(A, () -> queries.mergedInto(primary.id())))
        .extracting(CustomerQueries.MergedCustomer::sourceId)
        .containsExactly(source.id());
    assertThat(as(A, () -> followupQueries.list(primary.id(), 0, 20)).items())
        .containsExactly(followup);
    assertThatThrownBy(() -> as(B, () -> commands.reverseMerge(source.id(), 1)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(
            count(
                "select count(*) from audit.operation_failure where tenant_id=? and actor_id=? and action='CUSTOMER_MERGE_REVERSE' and code='ACCESS_DENIED'",
                TENANT,
                B))
        .isGreaterThanOrEqualTo(1);

    var reversed = as(A, () -> commands.reverseMerge(source.id(), 1));
    assertThat(reversed.primaryId()).isEqualTo(primary.id());
    assertThat(reversed.sourceVersion()).isEqualTo(2);
    assertThat(reversed.primaryVersion()).isEqualTo(2);
    assertThat(as(A, () -> queries.list(SCHOOL, 0, 100)).items())
        .extracting(Customer::id)
        .contains(source.id());
    assertThat(as(A, () -> followupQueries.list(source.id(), 0, 20)).items())
        .containsExactly(followup);
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action in ('MERGED','MERGE_REVERSED')",
                TENANT,
                source.id()))
        .isEqualTo(2);
  }

  @Test
  void concurrentCustomerMergeWithSameVersionsCanOnlyCommitOnce() throws Exception {
    var source = created(A, input("并发合并来源", "13900002011"));
    var primary = created(A, input("并发合并主档", "13900002012"));
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var one =
          pool.submit(
              () -> {
                start.await();
                return as(A, () -> commands.merge(source.id(), primary.id(), 0, 0));
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return as(A, () -> commands.merge(source.id(), primary.id(), 0, 0));
              });
      start.countDown();
      var succeeded = 0;
      var conflicted = 0;
      for (var future : List.of(one, two)) {
        try {
          future.get(10, TimeUnit.SECONDS);
          succeeded++;
        } catch (ExecutionException failure) {
          if (failure.getCause() instanceof CustomerConflict conflict
              && "VERSION_CONFLICT".equals(conflict.code())) conflicted++;
          else throw failure;
        }
      }
      assertThat(succeeded).isEqualTo(1);
      assertThat(conflicted).isEqualTo(1);
      assertThat(as(A, () -> queries.mergedInto(primary.id())))
          .extracting(CustomerQueries.MergedCustomer::sourceId)
          .containsExactly(source.id());
    } finally {
      pool.shutdownNow();
    }
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
    assertThat(as(B, () -> commands.create(input("丙", "13800001020")).status()))
        .isEqualTo("DUPLICATE_CONFIRMATION_REQUIRED");
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
    var challenge = as(B, () -> commands.create(input("别人", "13800001002")));
    assertThat(challenge.status()).isEqualTo("DUPLICATE_CONFIRMATION_REQUIRED");
    assertThat(challenge.customer()).isNull();
    assertThat(challenge.confirmationToken()).isNotNull();
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
    var response =
        as(
            A,
            () ->
                new CustomerHttp(queries, commands, followupQueries, followupCommands, conversions)
                    .phone(own.id()));
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

  @Test
  void sameSchoolPublicPoolTransfersOwnerWithVersionAndPreservesFollowups() throws Exception {
    grant(A, SCHOOL, "CUSTOMER_POOL_MANAGE");
    grant(B, SCHOOL, "CUSTOMER_POOL_READ");
    var customer = created(A, input("公海流转", "13900001991"));
    var oldFollowup =
        as(
            A,
            () ->
                followupCommands.create(
                    customer.id(),
                    new CustomerFollowupCommands.FollowupCreate(
                        UUID.randomUUID(), "转入前跟进", "2026-11-01", null)));

    var released = as(A, () -> commands.releaseToPool(customer.id(), customer.version()));
    assertThat(released.version()).isEqualTo(customer.version() + 1);
    assertThat(as(A, () -> queries.list(SCHOOL, 0, 20)).items())
        .noneMatch(row -> row.id().equals(customer.id()));
    assertThat(as(B, () -> queries.pool(SCHOOL, 0, 20)).items())
        .anyMatch(row -> row.id().equals(customer.id()));

    var claimed = as(B, () -> commands.claim(customer.id(), released.version()));
    assertThat(claimed.version()).isEqualTo(released.version() + 1);
    assertThat(as(B, () -> queries.list(SCHOOL, 0, 20)).items())
        .anyMatch(row -> row.id().equals(customer.id()));
    assertThat(as(A, () -> queries.list(SCHOOL, 0, 20)).items())
        .noneMatch(row -> row.id().equals(customer.id()));
    assertThat(as(B, () -> followupQueries.list(customer.id(), 0, 20)).items())
        .anyMatch(row -> row.id().equals(oldFollowup.id()));
    assertThatThrownBy(() -> as(A, () -> commands.releaseToPool(customer.id(), released.version())))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThatThrownBy(() -> as(B, () -> commands.claim(customer.id(), released.version())))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("CUSTOMER_NOT_IN_POOL");
    as(
        B,
        () ->
            followupCommands.create(
                customer.id(),
                new CustomerFollowupCommands.FollowupCreate(
                    UUID.randomUUID(), "转入后跟进", "2026-11-04", null)));
    assertThat(as(B, () -> followupQueries.list(customer.id(), 0, 20)).items()).hasSize(2);
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action in ('POOL_RELEASED','POOL_CLAIMED')",
                TENANT,
                customer.id()))
        .isEqualTo(2);
  }

  @Test
  void manualOwnerAssignmentIsScopedVersionedAuditedAndIdempotent() throws Exception {
    grant(A, SCHOOL, "CUSTOMER_ASSIGN");
    var employee = UUID.randomUUID();
    update(
        "insert into organization.employee(id,tenant_id,school_id,name,account_id) values (?,?,?,?,?)",
        employee,
        TENANT,
        SCHOOL,
        "负责人乙",
        B);
    var customer = created(A, input("负责人转派", "13900001771"));
    var priorFollowup =
        as(
            A,
            () ->
                followupCommands.create(
                    customer.id(),
                    new CustomerFollowupCommands.FollowupCreate(
                        UUID.randomUUID(), "转派前记录", "2026-11-03", null)));
    assertThat(as(A, () -> queries.assignableEmployees(SCHOOL)))
        .contains(new CustomerQueries.CustomerAssignee(B, "负责人乙"));

    var requestId = UUID.randomUUID();
    var request =
        new CustomerCommands.CustomerAssign(requestId, customer.version(), B, "按校区调整负责范围");
    var assigned = as(A, () -> commands.assignOwner(customer.id(), request));
    assertThat(assigned.version()).isEqualTo(customer.version() + 1);
    assertThat(as(A, () -> commands.assignOwner(customer.id(), request))).isEqualTo(assigned);
    assertThat(as(A, () -> queries.list(SCHOOL, 0, 20)).items())
        .noneMatch(row -> row.id().equals(customer.id()));
    assertThat(as(B, () -> queries.list(SCHOOL, 0, 20)).items())
        .anyMatch(row -> row.id().equals(customer.id()));
    assertThat(as(B, () -> followupQueries.list(customer.id(), 0, 20)).items())
        .contains(priorFollowup);
    assertThatThrownBy(
            () ->
                as(
                    A,
                    () ->
                        commands.assignOwner(
                            customer.id(),
                            new CustomerCommands.CustomerAssign(
                                requestId, customer.version(), B, "不同原因"))))
        .isInstanceOf(CustomerConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(
            count(
                "select count(*) from audit.customer_event where tenant_id=? and customer_id=? and action='OWNER_ASSIGNED'",
                TENANT,
                customer.id()))
        .isEqualTo(1);
    assertThat(
            scalar(
                "select reason from admissions.customer_assignment_request where tenant_id=? and actor_id=? and request_id=?",
                TENANT,
                A,
                requestId))
        .isEqualTo("按校区调整负责范围");
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

  private String scalar(String query, Object... args) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement(query)) {
      for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
      try (var r = s.executeQuery()) {
        r.next();
        return r.getString(1);
      }
    }
  }
}
