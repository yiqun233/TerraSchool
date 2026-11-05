package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.approval.api.*;
import org.teraschool.enrollment.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.platform.api.TenantTransactions;

/** 审批流：策略阈值、申请与决定、禁止自批、单据一次性消耗，以及退费闸门。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalWorkflowTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      PRICE = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      APPLICANT = UUID.randomUUID(),
      APPROVER = UUID.randomUUID(),
      MANAGER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired ApprovalCommands approvalCommands;
  @Autowired ApprovalQueries approvalQueries;
  @Autowired EnrollmentCommands enrollments;
  @Autowired FundsCommands funds;
  @Autowired FundsQueries fundsQueries;
  @Autowired org.jooq.DSLContext sql;
  @Autowired TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update(
        "insert into identity.tenant(id,name) values (?,?),(?,?)",
        TENANT,
        "审批机构甲",
        OTHER_TENANT,
        "审批机构乙");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','审批学校甲')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'审批课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'审批产品',1)",
        PRODUCT,
        TENANT,
        SCHOOL,
        CURRICULUM);
    update(
        "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,"
            + " service_fee,settlement_mode,study_days,effective_from)"
            + " values (?,?,?,1,'PUBLISHED',1000.00,10.00,0,1,180,'2026-01-01')",
        PRICE,
        TENANT,
        PRODUCT);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry :
        Map.of(
                OPS,
                "approval-ops",
                APPLICANT,
                "approval-applicant",
                APPROVER,
                "approval-approver",
                MANAGER,
                "approval-manager")
            .entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var action :
        new String[] {
          "ENROLLMENT_READ",
          "ENROLLMENT_MAINTAIN",
          "ENROLLMENT_APPROVE",
          "FUNDS_READ",
          "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE",
          "REFUND_READ",
          "REFUND_APPLY",
          "REFUND_PAY",
          "APPROVAL_READ"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
    for (var action :
        new String[] {"REFUND_READ", "REFUND_APPLY", "APPROVAL_READ", "ENROLLMENT_MAINTAIN"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          APPLICANT,
          SCHOOL,
          action);
    for (var action : new String[] {"APPROVAL_READ", "APPROVAL_DECIDE"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          APPROVER,
          SCHOOL,
          action);
    for (var action : new String[] {"APPROVAL_READ", "APPROVAL_MANAGE"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          MANAGER,
          SCHOOL,
          action);
  }

  <T> T asActor(UUID account, Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            EngineeringTest.principal(account), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  UUID freshStudent() {
    var id = UUID.randomUUID();
    try {
      update(
          "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
          id,
          TENANT,
          "S6" + id.toString().replace("-", "").substring(0, 6),
          "审批学员" + id.toString().substring(0, 4),
          "FEMALE",
          SCHOOL);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return id;
  }

  /** 已收 1000 并全额分配、可退的报名。 */
  EnrollmentView paidEnrollment(UUID student) {
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, PRICE, "2026-02-10")));
    var approved =
        asActor(
            OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
    var receipt =
        asActor(
            OPS,
            () ->
                funds.createReceipt(
                    new CreateReceipt(
                        UUID.randomUUID(), SCHOOL, student, "1000", "CASH", "2026-02-10")));
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), approved.id(), "1000")));
    return approved;
  }

  void policy(String action, String freeBelow) {
    asActor(
        MANAGER,
        () ->
            approvalCommands.upsertPolicy(new UpsertPolicy(SCHOOL, action, freeBelow, true, null)));
  }

  ApprovalRequestView request(EnrollmentView enrollment, String action, String amount) {
    var requestAmount = "ENROLLMENT_DEFER".equals(action) ? "0.00" : amount;
    return asActor(
        APPLICANT,
        () ->
            approvalCommands.request(
                new CreateApprovalRequest(
                    UUID.randomUUID(),
                    enrollment.id(),
                    action,
                    requestAmount,
                    "测试申请",
                    "ENROLLMENT_DEFER".equals(action)
                        ? java.time.LocalDate.parse(enrollment.validUntil()).plusDays(30)
                        : null)));
  }

  private CreateApprovalRequest deferCommand(UUID requestId, UUID enrollmentId, String until) {
    return new CreateApprovalRequest(
        requestId,
        enrollmentId,
        "ENROLLMENT_DEFER",
        "0.00",
        "延期申请",
        java.time.LocalDate.parse(until));
  }

  ApprovalRequestView approve(ApprovalRequestView request) {
    return asActor(
        APPROVER,
        () ->
            approvalCommands.decide(
                request.id(), new DecideApproval(request.version(), "APPROVED", "同意")));
  }

  RefundResult refund(EnrollmentView enrollment, String hours, UUID approvalId) {
    return asActor(
        OPS,
        () ->
            funds.cancelHoursAndReturnToAccount(
                enrollment.id(),
                new CancelHoursAndReturnCommand(UUID.randomUUID(), hours, "退课时", approvalId)));
  }

  @Test
  void withoutPolicyRefundIsRejected() throws Exception {
    update("delete from approval.policy where school_id=? and action='REFUND_HOURS'", SCHOOL);
    var enrollment = paidEnrollment(freshStudent());
    assertThatThrownBy(() -> refund(enrollment, "2.00", null))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_POLICY_NOT_CONFIGURED");
  }

  @Test
  void disabledPolicyDoesNotGrantApprovalExemption() throws Exception {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "1000");
    update(
        "update approval.policy set active=false where school_id=? and action='REFUND_HOURS'",
        SCHOOL);
    assertThatThrownBy(() -> refund(enrollment, "2.00", null))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_POLICY_NOT_CONFIGURED");
  }

  @Test
  void thresholdControlsApproval() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "100");
    // 200 元超过阈值：无审批单被拒。
    assertThatThrownBy(() -> refund(enrollment, "2.00", null))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_REQUIRED");
    // 50 元低于阈值：免审批。
    assertThat(refund(enrollment, "0.50", null).amount()).isEqualTo("50.00");
  }

  @Test
  void approvedRequestIsConsumedOnce() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    var request = request(enrollment, "REFUND_HOURS", "200.00");
    assertThat(request.status()).isEqualTo("PENDING");
    var approved = approve(request);
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(refund(enrollment, "2.00", approved.id()).amount()).isEqualTo("200.00");
    assertThatThrownBy(() -> refund(enrollment, "2.00", approved.id()))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_ALREADY_USED");
  }

  @Test
  void requestMustMatchAmountAndEnrollment() {
    var first = paidEnrollment(freshStudent());
    var second = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    var approved = approve(request(first, "REFUND_HOURS", "200.00"));
    assertThatThrownBy(() -> refund(first, "3.00", approved.id()))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_MISMATCH");
    assertThatThrownBy(() -> refund(second, "2.00", approved.id()))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_MISMATCH");
  }

  @Test
  void applicantCannotDecideOwnRequest() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    var request = request(enrollment, "REFUND_HOURS", "200.00");
    assertThatThrownBy(
            () ->
                asActor(
                    APPLICANT,
                    () ->
                        approvalCommands.decide(
                            request.id(), new DecideApproval(request.version(), "APPROVED", "自批"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void pendingRequestIsUniquePerEnrollmentAndAction() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    request(enrollment, "REFUND_HOURS", "200.00");
    assertThatThrownBy(() -> request(enrollment, "REFUND_HOURS", "300.00"))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_ALREADY_PENDING");
  }

  @Test
  void requestRetryWithSameIdentityReturnsOriginalApprovalRequest() {
    var enrollment = paidEnrollment(freshStudent());
    var requestId = UUID.randomUUID();
    var command = deferCommand(requestId, enrollment.id(), "2026-09-01");

    var first = asActor(APPLICANT, () -> approvalCommands.request(command));
    var retry = asActor(APPLICANT, () -> approvalCommands.request(command));

    assertThat(retry.id()).isEqualTo(first.id());
    assertThat(retry.status()).isEqualTo(first.status());
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) as n from approval.request where enrollment_id=? and action='ENROLLMENT_DEFER'",
                            enrollment.id())
                        .get("n", Long.class)))
        .isEqualTo(1L);
  }

  @Test
  void requestIdCannotBeReusedForDifferentApprovalContent() {
    var enrollment = paidEnrollment(freshStudent());
    var requestId = UUID.randomUUID();
    asActor(
        APPLICANT,
        () ->
            approvalCommands.request(
                new CreateApprovalRequest(
                    requestId,
                    enrollment.id(),
                    "ENROLLMENT_DEFER",
                    "0.00",
                    "延期申请",
                    java.time.LocalDate.parse("2026-09-01"))));

    assertThatThrownBy(
            () ->
                asActor(
                    APPLICANT,
                    () ->
                        approvalCommands.request(
                            new CreateApprovalRequest(
                                requestId,
                                enrollment.id(),
                                "ENROLLMENT_DEFER",
                                "0.00",
                                "延期申请",
                                java.time.LocalDate.parse("2026-10-01")))))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void approvedEnrollmentDeferChangesOnlyTheFrozenEndDateAndRetryIsIdempotent() {
    var enrollment = paidEnrollment(freshStudent());
    policy("ENROLLMENT_DEFER", "0");
    var requestedUntil = java.time.LocalDate.parse("2026-09-01");
    var request =
        asActor(
            APPLICANT,
            () ->
                approvalCommands.request(
                    deferCommand(UUID.randomUUID(), enrollment.id(), requestedUntil.toString())));
    var approved = approve(request);

    var deferred =
        asActor(
            OPS,
            () ->
                enrollments.defer(
                    enrollment.id(), new DeferEnrollment(approved.id(), requestedUntil)));
    var retry =
        asActor(
            OPS,
            () ->
                enrollments.defer(
                    enrollment.id(), new DeferEnrollment(approved.id(), requestedUntil)));

    assertThat(enrollment.validUntil()).isEqualTo("2026-08-08");
    assertThat(deferred.validUntil()).isEqualTo("2026-09-01");
    assertThat(retry.version()).isEqualTo(deferred.version());
    assertThat(retry.validUntil()).isEqualTo(deferred.validUntil());
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne("select used_at from approval.request where id=?", approved.id())
                        .get("used_at", java.time.OffsetDateTime.class)))
        .isNotNull();
  }

  @Test
  void approvedDeferCannotChangeItsRequestedDateOrExtendBackward() {
    var enrollment = paidEnrollment(freshStudent());
    policy("ENROLLMENT_DEFER", "0");
    var requestedUntil = java.time.LocalDate.parse("2026-09-01");
    var approved =
        approve(
            asActor(
                APPLICANT,
                () ->
                    approvalCommands.request(
                        deferCommand(
                            UUID.randomUUID(), enrollment.id(), requestedUntil.toString()))));

    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        enrollments.defer(
                            enrollment.id(),
                            new DeferEnrollment(approved.id(), requestedUntil.plusDays(1)))))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_MISMATCH");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        enrollments.defer(
                            enrollment.id(),
                            new DeferEnrollment(
                                approved.id(), java.time.LocalDate.parse("2026-08-01")))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("DEFER_DATE_MUST_EXTEND_TERM");
  }

  @Test
  void enrollmentChangeApprovalRequiresEnrollmentMaintenancePermission() {
    var enrollment = paidEnrollment(freshStudent());
    assertThatThrownBy(
            () ->
                asActor(
                    MANAGER,
                    () ->
                        approvalCommands.request(
                            new CreateApprovalRequest(
                                UUID.randomUUID(),
                                enrollment.id(),
                                "ENROLLMENT_TRANSFER",
                                "100.00",
                                "转班申请"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void concurrentRequestRetryWithSameIdentityCreatesOneApproval() throws Exception {
    var enrollment = paidEnrollment(freshStudent());
    var command = deferCommand(UUID.randomUUID(), enrollment.id(), "2026-09-01");
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<ApprovalRequestView> submit =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            return asActor(APPLICANT, () -> approvalCommands.request(command));
          };
      var first = pool.submit(submit);
      var second = pool.submit(submit);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      var firstResult = first.get(10, TimeUnit.SECONDS);
      var secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(secondResult.id()).isEqualTo(firstResult.id());
      assertThat(
              tx.execute(
                  TENANT,
                  () ->
                      sql.fetchOne(
                              "select count(*) as n from approval.request where enrollment_id=? and action='ENROLLMENT_DEFER'",
                              enrollment.id())
                          .get("n", Long.class)))
          .isEqualTo(1L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void applicantCanWithdrawOnlyTheirOwnPendingRequestAndReapply() {
    var enrollment = paidEnrollment(freshStudent());
    var request = request(enrollment, "ENROLLMENT_TRANSFER", "100.00");
    assertThat(request.status()).isEqualTo("PENDING");
    assertThatThrownBy(
            () ->
                asActor(APPROVER, () -> approvalCommands.withdraw(request.id(), request.version())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> asActor(APPLICANT, () -> approvalCommands.withdraw(request.id(), 10L)))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("VERSION_CONFLICT");

    var withdrawn =
        asActor(APPLICANT, () -> approvalCommands.withdraw(request.id(), request.version()));
    assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
    assertThat(withdrawn.version()).isEqualTo(request.version() + 1);
    assertThat(request(enrollment, "ENROLLMENT_TRANSFER", "120.00").status()).isEqualTo("PENDING");
  }

  @Test
  void approverCanExpireAnotherApplicantsPendingRequestWithReason() {
    var enrollment = paidEnrollment(freshStudent());
    var request = request(enrollment, "ENROLLMENT_DEFER", "50.00");
    assertThatThrownBy(
            () ->
                asActor(
                    APPROVER, () -> approvalCommands.expire(request.id(), request.version(), " ")))
        .isInstanceOf(IllegalArgumentException.class);
    var expired =
        asActor(
            APPROVER, () -> approvalCommands.expire(request.id(), request.version(), "原业务不再办理"));
    assertThat(expired.status()).isEqualTo("EXPIRED");
    assertThat(expired.decidedBy()).isEqualTo(APPROVER);
    assertThat(expired.decisionNote()).isEqualTo("原业务不再办理");
    assertThat(request(enrollment, "ENROLLMENT_DEFER", "60.00").status()).isEqualTo("PENDING");
  }

  @Test
  void decisionRequiresVersionAndPermission() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    var request = request(enrollment, "REFUND_HOURS", "200.00");
    assertThatThrownBy(
            () ->
                asActor(
                    APPROVER,
                    () ->
                        approvalCommands.decide(
                            request.id(), new DecideApproval(99L, "APPROVED", "过期版本"))))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("VERSION_CONFLICT");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        approvalCommands.decide(
                            request.id(), new DecideApproval(request.version(), "APPROVED", "无权"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(approve(request).status()).isEqualTo("APPROVED");
  }

  @Test
  void rejectedRequestCannotBeUsed() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    var request = request(enrollment, "REFUND_HOURS", "200.00");
    var rejected =
        asActor(
            APPROVER,
            () ->
                approvalCommands.decide(
                    request.id(), new DecideApproval(request.version(), "REJECTED", "不符合规则")));
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThatThrownBy(() -> refund(enrollment, "2.00", rejected.id()))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_NOT_APPROVED");
  }

  @Test
  void cashRefundIsGatedToo() {
    var enrollment = paidEnrollment(freshStudent());
    // 退课时在本用例不设门槛，只验证现金退款被审批拦截。
    policy("REFUND_HOURS", "1000");
    policy("CASH_REFUND", "0");
    refund(enrollment, "2.00", null);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.prepareCashRefund(
                            new PrepareCashRefund(
                                UUID.randomUUID(), enrollment.id(), "200.00", null))))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("APPROVAL_REQUIRED");
    var approved = approve(request(enrollment, "CASH_REFUND", "200.00"));
    var prepared =
        asActor(
            OPS,
            () ->
                funds.prepareCashRefund(
                    new PrepareCashRefund(
                        UUID.randomUUID(), enrollment.id(), "200.00", approved.id())));
    assertThat(prepared.status()).isEqualTo("PREPARED");
    assertThat(prepared.amount()).isEqualTo("200.00");
  }

  @Test
  void policyUpdateNeedsVersion() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "100");
    var current =
        asActor(MANAGER, () -> approvalQueries.policies(SCHOOL)).stream()
            .filter(item -> item.action().equals("REFUND_HOURS"))
            .findFirst()
            .orElseThrow();
    assertThat(current.freeBelow()).isEqualTo("100.00");
    assertThatThrownBy(
            () ->
                asActor(
                    MANAGER,
                    () ->
                        approvalCommands.upsertPolicy(
                            new UpsertPolicy(
                                SCHOOL, "REFUND_HOURS", "300", true, current.version() + 5))))
        .isInstanceOf(ApprovalConflict.class)
        .hasMessage("VERSION_CONFLICT");
    var updated =
        asActor(
            MANAGER,
            () ->
                approvalCommands.upsertPolicy(
                    new UpsertPolicy(SCHOOL, "REFUND_HOURS", "300", true, current.version())));
    assertThat(updated.freeBelow()).isEqualTo("300.00");
    assertThat(updated.version()).isEqualTo(current.version() + 1);
    assertThat(enrollment.id()).isNotNull();
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into approval.policy(tenant_id,school_id,action,free_below)"
                                + " values (?,?,'REFUND_HOURS',0)",
                            TENANT,
                            SCHOOL)))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }

  @Test
  void gatewayRejectsAmountMismatchBySmallDelta() {
    var enrollment = paidEnrollment(freshStudent());
    policy("REFUND_HOURS", "0");
    var approved = approve(request(enrollment, "REFUND_HOURS", "200.00"));
    // 金额必须完全一致：199.99 也不行。
    assertThatThrownBy(() -> refund(enrollment, "1.9999", approved.id()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(approved.amount()).isEqualTo("200.00");
    assertThat(new BigDecimal(approved.amount()).compareTo(new BigDecimal("200.00"))).isZero();
    var view = asActor(APPROVER, () -> approvalQueries.requests(SCHOOL, "APPROVED"));
    assertThat(view).extracting(ApprovalRequestView::status).contains("APPROVED");
  }
}
