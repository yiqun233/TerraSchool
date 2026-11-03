package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.enrollment.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 退款与首个闭环验收：退课时 → 退到账户 → 现金退出两步，以及 SC01 S0–S7 逐阶段对账。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundAndAcceptanceTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      PRICE = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      APPLIER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired EnrollmentCommands enrollments;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired FundsCommands funds;
  @Autowired FundsQueries fundsQueries;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
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
        "退款机构甲",
        OTHER_TENANT,
        "退款机构乙");
    for (var id : new UUID[] {SCHOOL, OTHER_SCHOOL})
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
          id,
          TENANT,
          id,
          id.equals(SCHOOL) ? "退款学校甲" : "退款学校乙");
    EngineeringTest.seedExplicitApprovalPolicy(TENANT, SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'退款课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'退款产品',1)",
        PRODUCT,
        TENANT,
        SCHOOL,
        CURRICULUM);
    update(
        "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,"
            + " service_fee,settlement_mode,effective_from)"
            + " values (?,?,?,1,'PUBLISHED',1000.00,10.00,0,1,'2026-01-01')",
        PRICE,
        TENANT,
        PRODUCT);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry : Map.of(OPS, "refund-ops", APPLIER, "refund-applier").entrySet())
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
          "TEACHING_READ",
          "TEACHING_MAINTAIN",
          "ATTENDANCE_RECORD",
          "LESSON_CLOSE",
          "REFUND_READ",
          "REFUND_APPLY",
          "REFUND_PAY"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
    for (var action : new String[] {"REFUND_READ", "REFUND_APPLY"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          APPLIER,
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
          "S7" + id.toString().replace("-", "").substring(0, 6),
          "退款学员" + id.toString().substring(0, 4),
          "FEMALE",
          SCHOOL);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return id;
  }

  EnrollmentView activeEnrollment(UUID student) {
    var created =
        asActor(
            OPS,
            () ->
                enrollments.create(
                    new CreateEnrollment(UUID.randomUUID(), student, PRICE, "2026-02-10")));
    return asActor(
        OPS, () -> enrollments.approve(created.id(), new EnrollmentVersion(created.version())));
  }

  Receipt receipt(UUID student, String amount) {
    return asActor(
        OPS,
        () ->
            funds.createReceipt(
                new CreateReceipt(
                    UUID.randomUUID(), SCHOOL, student, amount, "CASH", "2026-02-10")));
  }

  void consumedLesson(EnrollmentView enrollment) {
    var classView =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "退款班" + UUID.randomUUID())));
    asActor(OPS, () -> teaching.addMember(classView.id(), new AddClassMember(enrollment.id())));
    var draft =
        asActor(
            OPS,
            () ->
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classView.id(),
                        "2026-03-02",
                        "09:00",
                        "10:00",
                        "教师甲",
                        "教室甲",
                        "第一课",
                        "1.00")));
    var published =
        asActor(OPS, () -> teaching.publishLesson(draft.id(), new LessonVersion(draft.version())));
    var confirmed =
        asActor(
            OPS,
            () ->
                teaching.confirmAttendance(
                    published.id(),
                    new ConfirmAttendance(
                        published.version(),
                        List.of(new AttendanceInput(enrollment.id(), "PRESENT")))));
    asActor(
        OPS, () -> teaching.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
  }

  RefundResult cancelHours(EnrollmentView enrollment, String hours, UUID actor) {
    return asActor(
        actor,
        () ->
            funds.cancelHoursAndReturnToAccount(
                enrollment.id(),
                new CancelHoursAndReturnCommand(UUID.randomUUID(), hours, "部分退课", null)));
  }

  CashRefundView prepare(EnrollmentView enrollment, String amount) {
    return asActor(
        OPS,
        () ->
            funds.prepareCashRefund(
                new PrepareCashRefund(UUID.randomUUID(), enrollment.id(), amount, null)));
  }

  CashRefundView pay(CashRefundView refund, String reference) {
    return asActor(
        OPS,
        () ->
            funds.confirmCashRefundPaid(
                refund.id(), new ConfirmCashRefund(refund.version(), reference)));
  }

  @Test
  void fullAcceptanceS0ToS7() {
    // S0：报名登记，冻结 1000.00 / 10.00 课时，原始应收 1000.00。
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    assertThat(enrollment.price()).isEqualTo("1000.00");
    assertThat(enrollment.hours()).isEqualTo("10.00");
    assertThat(enrollment.netAmount()).isEqualTo("1000.00");
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).total()).isEqualTo("0.00");
    assertThat(asActor(OPS, () -> fundsQueries.outstanding(enrollment.id()))).isEqualTo("1000.00");

    // S1：确认收款 1000.00，全部成为可用余额。
    var receipt = receipt(student, "1000");
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).total()).isEqualTo("1000.00");
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).available()).isEqualTo("1000.00");

    // S2：全额分配给报名，账户清零、未收为 0。
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000")));
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).total()).isEqualTo("0.00");
    assertThat(asActor(OPS, () -> fundsQueries.outstanding(enrollment.id()))).isEqualTo("0.00");

    // S3–S4：一节课关闭，固化 1.00 课时 / 100.00 元。
    consumedLesson(enrollment);
    var afterConsumption = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(afterConsumption.consumedHours()).isEqualTo("1.00");
    assertThat(afterConsumption.consumedAmount()).isEqualTo("100.00");
    assertThat(afterConsumption.remainingHours()).isEqualTo("9.00");

    // S5：退 2 课时 200.00 元，先回学员账户；净应收 800.00，分配调整到 800.00。
    var refund = cancelHours(enrollment, "2.00", OPS);
    assertThat(refund.hours()).isEqualTo("2.00");
    assertThat(refund.amount()).isEqualTo("200.00");
    assertThat(refund.netAmount()).isEqualTo("800.00");
    assertThat(refund.allocated()).isEqualTo("800.00");
    assertThat(refund.balanceTotal()).isEqualTo("200.00");
    assertThat(refund.balanceAvailable()).isEqualTo("200.00");
    assertThat(asActor(OPS, () -> fundsQueries.outstanding(enrollment.id()))).isEqualTo("0.00");
    var afterRefund = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(afterRefund.cancelledHours()).isEqualTo("2.00");
    assertThat(afterRefund.remainingHours()).isEqualTo("7.00");
    // 购买 10 = 已消耗 1 + 已退 2 + 剩余 7
    assertThat(afterRefund.consumedHours()).isEqualTo("1.00");

    // S6：准备现金退款 200.00，冻结后可用为 0，账户总额不变。
    var prepared = prepare(enrollment, "200.00");
    assertThat(prepared.status()).isEqualTo("PREPARED");
    var frozen = asActor(OPS, () -> fundsQueries.balance(student));
    assertThat(frozen.total()).isEqualTo("200.00");
    assertThat(frozen.reserved()).isEqualTo("200.00");
    assertThat(frozen.available()).isEqualTo("0.00");

    // S7：确认已实际支付，核销冻结、账户清零、记现金退出。
    var paid = pay(prepared, "CASH-2026-0001");
    assertThat(paid.status()).isEqualTo("PAID");
    assertThat(paid.reference()).isEqualTo("CASH-2026-0001");
    var settled = asActor(OPS, () -> fundsQueries.balance(student));
    assertThat(settled.total()).isEqualTo("0.00");
    assertThat(settled.reserved()).isEqualTo("0.00");
    assertThat(settled.available()).isEqualTo("0.00");
    assertThat(asActor(OPS, () -> fundsQueries.cashRefunds(enrollment.id()))).hasSize(1);
  }

  @Test
  void cancellationRejectsMoreThanRefundable() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    assertThatThrownBy(() -> cancelHours(enrollment, "11.00", OPS))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("HOURS_EXCEED_REFUNDABLE");
    // 消耗 1 课时后可退 9；再退 9.5 会被拒。
    consumedLesson(enrollment);
    assertThatThrownBy(() -> cancelHours(enrollment, "9.50", OPS))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("HOURS_EXCEED_REFUNDABLE");
    assertThat(cancelHours(enrollment, "9.00", OPS).netAmount()).isEqualTo("100.00");
  }

  @Test
  void partialPaymentOnlyReducesReceivable() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var receipt = receipt(student, "500");
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "500")));
    var refund = cancelHours(enrollment, "2.00", OPS);
    // 只收了 500：退课时只减少应收，不产生账户余额。
    assertThat(refund.netAmount()).isEqualTo("800.00");
    assertThat(refund.allocated()).isEqualTo("500.00");
    assertThat(refund.balanceTotal()).isEqualTo("0.00");
    assertThat(asActor(OPS, () -> fundsQueries.outstanding(enrollment.id()))).isEqualTo("300.00");
  }

  @Test
  void prepareRequiresAvailableBalance() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var receipt = receipt(student, "1000");
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000")));
    // 账户已全部用于报名，没有可用余额。
    assertThatThrownBy(() -> prepare(enrollment, "200.00"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("INSUFFICIENT_AVAILABLE");
    cancelHours(enrollment, "2.00", OPS);
    var prepared = prepare(enrollment, "200.00");
    assertThat(prepared.amount()).isEqualTo("200.00");
    // 冻结已占满可用余额，再准备一笔会被拒。
    assertThatThrownBy(() -> prepare(enrollment, "100.00"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("INSUFFICIENT_AVAILABLE");
  }

  @Test
  void cancellingRefundReleasesReservation() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var receipt = receipt(student, "1000");
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000")));
    cancelHours(enrollment, "2.00", OPS);
    var prepared = prepare(enrollment, "200.00");
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).available()).isEqualTo("0.00");
    var cancelled =
        asActor(
            OPS, () -> funds.cancelCashRefund(prepared.id(), new FundsVersion(prepared.version())));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(asActor(OPS, () -> fundsQueries.balance(student)).available()).isEqualTo("200.00");
    assertThatThrownBy(() -> pay(cancelled, "CASH-X"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("CASH_REFUND_NOT_PREPARED");
  }

  @Test
  void payingNeedsSeparatePermission() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var receipt = receipt(student, "1000");
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000")));
    cancelHours(enrollment, "2.00", OPS);
    var prepared = prepare(enrollment, "200.00");
    assertThatThrownBy(
            () ->
                asActor(
                    APPLIER,
                    () ->
                        funds.confirmCashRefundPaid(
                            prepared.id(), new ConfirmCashRefund(prepared.version(), "CASH-Y"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void cancellationRetryIsIdempotent() {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var requestId = UUID.randomUUID();
    var command = new CancelHoursAndReturnCommand(requestId, "2.00", "重试", null);
    var first = asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), command));
    var second = asActor(OPS, () -> funds.cancelHoursAndReturnToAccount(enrollment.id(), command));
    assertThat(second.balanceTotal()).isEqualTo(first.balanceTotal());
    var reconciliation = asActor(OPS, () -> teachingQueries.reconciliation(enrollment.id()));
    assertThat(reconciliation.cancelledHours()).isEqualTo("2.00");
    // 同请求号换参数被拒。
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        funds.cancelHoursAndReturnToAccount(
                            enrollment.id(),
                            new CancelHoursAndReturnCommand(requestId, "1.00", "重试", null))))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into funds.cash_refund(tenant_id,school_id,student_id,enrollment_id,"
                                + "reservation_id,amount) values (?,?,?,?,?,10.00)",
                            TENANT,
                            SCHOOL,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            UUID.randomUUID())))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient client, String path, String body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(
                "Content-Type",
                path.endsWith("login") ? "application/x-www-form-urlencoded" : "application/json");
    if (token != null) builder.header("X-CSRF-TOKEN", token);
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpSecondEntryRunsTheRefundSteps() throws Exception {
    var student = freshStudent();
    var enrollment = activeEnrollment(student);
    var receipt = receipt(student, "1000");
    asActor(OPS, () -> funds.allocate(new AllocateReceipt(receipt.id(), enrollment.id(), "1000")));
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    var csrf = json.readTree(get(client, "/api/session/csrf").body());
    assertThat(
            post(
                    client,
                    "/api/session/login",
                    "username=refund-ops&password=" + PASSWORD,
                    csrf.get("token").asText())
                .statusCode())
        .isEqualTo(204);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var cancelBody =
        json.writeValueAsString(
            new CancelHoursAndReturnCommand(UUID.randomUUID(), "2.00", "接口退课时", null));
    var cancelled =
        post(
            client,
            "/api/funds/enrollments/" + enrollment.id() + "/cancel-hours",
            cancelBody,
            token);
    assertThat(cancelled.statusCode()).isEqualTo(200);
    var refundResult = json.readTree(cancelled.body());
    assertThat(refundResult.get("amount").asText()).isEqualTo("200.00");
    assertThat(refundResult.get("balanceAvailable").asText()).isEqualTo("200.00");
    var prepareBody =
        json.writeValueAsString(
            new PrepareCashRefund(UUID.randomUUID(), enrollment.id(), "200.00", null));
    var prepared = post(client, "/api/funds/refunds", prepareBody, token);
    assertThat(prepared.statusCode()).isEqualTo(201);
    var refundId = json.readTree(prepared.body()).get("id").asText();
    var confirmBody = "{\"expectedVersion\":0,\"reference\":\"CASH-2026-0002\"}";
    var paid = post(client, "/api/funds/refunds/" + refundId + "/confirm", confirmBody, token);
    assertThat(paid.statusCode()).isEqualTo(200);
    assertThat(json.readTree(paid.body()).get("status").asText()).isEqualTo("PAID");
    var balance = json.readTree(get(client, "/api/funds/balance?studentId=" + student).body());
    assertThat(balance.get("total").asText()).isEqualTo("0.00");
    assertThat(balance.get("reserved").asText()).isEqualTo("0.00");
  }
}
