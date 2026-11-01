package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
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
import org.teraschool.enrollment.api.*;
import org.teraschool.funds.api.*;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

/** 报名与收款：价格快照、审核权限、收款与分配守恒、撤销、并发与第二入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollmentAndReceiptTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      PRICE = UUID.randomUUID(),
      CLERK = UUID.randomUUID(),
      APPROVER = UUID.randomUUID(),
      READER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired EnrollmentCommands enrollmentCommands;
  @Autowired EnrollmentQueries enrollmentQueries;
  @Autowired FundsCommands fundsCommands;
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
        "报名机构甲",
        OTHER_TENANT,
        "报名机构乙");
    for (var id : new UUID[] {SCHOOL, OTHER_SCHOOL})
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
          id,
          TENANT,
          id,
          id.equals(SCHOOL) ? "报名学校甲" : "报名学校乙");
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'报名课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'报名产品',1)",
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
    for (var entry :
        Map.of(
                CLERK,
                "enrollment-clerk",
                APPROVER,
                "enrollment-approver",
                READER,
                "enrollment-reader")
            .entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var action :
        new String[] {
          "ENROLLMENT_READ", "ENROLLMENT_MAINTAIN", "FUNDS_READ", "FUNDS_RECEIPT", "FUNDS_ALLOCATE"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          CLERK,
          SCHOOL,
          action);
    for (var action : new String[] {"ENROLLMENT_READ", "ENROLLMENT_APPROVE"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          APPROVER,
          SCHOOL,
          action);
    for (var action : new String[] {"ENROLLMENT_READ", "FUNDS_READ"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          READER,
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

  /** 每个用例使用独立学员，账户余额互不影响。 */
  UUID freshStudent() {
    var id = UUID.randomUUID();
    try {
      update(
          "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
          id,
          TENANT,
          "S9" + id.toString().replace("-", "").substring(0, 6),
          "报名学员" + id.toString().substring(0, 4),
          "FEMALE",
          SCHOOL);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return id;
  }

  EnrollmentView enroll(UUID studentId) {
    return asActor(
        CLERK,
        () ->
            enrollmentCommands.create(
                new CreateEnrollment(UUID.randomUUID(), studentId, PRICE, "2026-02-10")));
  }

  EnrollmentView approvedEnrollment(UUID studentId) {
    var created = enroll(studentId);
    return asActor(
        APPROVER,
        () -> enrollmentCommands.approve(created.id(), new EnrollmentVersion(created.version())));
  }

  Receipt receipt(UUID studentId, String amount) {
    return asActor(
        CLERK,
        () ->
            fundsCommands.createReceipt(
                new CreateReceipt(
                    UUID.randomUUID(), SCHOOL, studentId, amount, "CASH", "2026-02-10")));
  }

  Allocation allocate(UUID receiptId, UUID enrollmentId, String amount) {
    return asActor(
        CLERK, () -> fundsCommands.allocate(new AllocateReceipt(receiptId, enrollmentId, amount)));
  }

  long countAudit(String table, UUID resource) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement("select count(*) from " + table + " where resource_id=?")) {
      s.setObject(1, resource);
      var r = s.executeQuery();
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void enrollmentFreezesPriceAndCreatesReceivable() throws Exception {
    var student = freshStudent();
    var input = new CreateEnrollment(UUID.randomUUID(), student, PRICE, "2026-02-10");
    var created = asActor(CLERK, () -> enrollmentCommands.create(input));
    assertThat(created.price()).isEqualTo("1000.00");
    assertThat(created.hours()).isEqualTo("10.00");
    assertThat(created.netAmount()).isEqualTo("1000.00");
    assertThat(created.status()).isEqualTo("PENDING_AUDIT");
    assertThat(created.productName()).isEqualTo("报名产品");
    assertThat(asActor(CLERK, () -> enrollmentCommands.create(input)).id()).isEqualTo(created.id());
    assertThatThrownBy(
            () ->
                asActor(
                    CLERK,
                    () ->
                        enrollmentCommands.create(
                            new CreateEnrollment(input.requestId(), student, PRICE, "2026-03-11"))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(countAudit("audit.enrollment_event", created.id())).isEqualTo(1);
  }

  @Test
  void unsellablePriceAndWindowRejected() throws Exception {
    var student = freshStudent();
    var draftPrice = UUID.randomUUID();
    update(
        "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,"
            + " service_fee,settlement_mode,effective_from)"
            + " values (?,?,?,2,'DRAFT',1100.00,10.00,0,1,'2026-01-01')",
        draftPrice,
        TENANT,
        PRODUCT);
    assertThatThrownBy(
            () ->
                asActor(
                    CLERK,
                    () ->
                        enrollmentCommands.create(
                            new CreateEnrollment(
                                UUID.randomUUID(), student, draftPrice, "2026-02-10"))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("PRICE_VERSION_NOT_SELLABLE");
    assertThatThrownBy(
            () ->
                asActor(
                    CLERK,
                    () ->
                        enrollmentCommands.create(
                            new CreateEnrollment(UUID.randomUUID(), student, PRICE, "2025-12-31"))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("PRICE_VERSION_NOT_SELLABLE");
  }

  @Test
  void approveNeedsSeparatePermissionAndVersion() {
    var created = enroll(freshStudent());
    assertThatThrownBy(
            () ->
                asActor(
                    CLERK,
                    () ->
                        enrollmentCommands.approve(
                            created.id(), new EnrollmentVersion(created.version()))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    APPROVER,
                    () -> enrollmentCommands.approve(created.id(), new EnrollmentVersion(99L))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("VERSION_CONFLICT");
    var approved =
        asActor(
            APPROVER,
            () ->
                enrollmentCommands.approve(created.id(), new EnrollmentVersion(created.version())));
    assertThat(approved.status()).isEqualTo("ACTIVE");
    assertThatThrownBy(
            () ->
                asActor(
                    APPROVER,
                    () ->
                        enrollmentCommands.approve(
                            approved.id(), new EnrollmentVersion(approved.version()))))
        .isInstanceOf(EnrollmentConflict.class)
        .hasMessage("ENROLLMENT_NOT_PENDING");
  }

  @Test
  void receiptThenAllocationReachesStageS2() throws Exception {
    var student = freshStudent();
    var enrollment = approvedEnrollment(student);
    var receipt = receipt(student, "1000");
    assertThat(receipt.amount()).isEqualTo("1000.00");
    assertThat(receipt.availableAmount()).isEqualTo("1000.00");
    assertThat(asActor(READER, () -> fundsQueries.balance(student)).total()).isEqualTo("1000.00");
    var allocation = allocate(receipt.id(), enrollment.id(), "1000");
    assertThat(allocation.amount()).isEqualTo("1000.00");
    assertThat(asActor(READER, () -> fundsQueries.outstanding(enrollment.id()))).isEqualTo("0.00");
    assertThat(asActor(READER, () -> fundsQueries.balance(student)).total()).isEqualTo("0.00");
    assertThat(countAudit("audit.funds_event", receipt.id())).isEqualTo(1);
    assertThat(countAudit("audit.funds_event", allocation.id())).isEqualTo(1);
  }

  @Test
  void allocationCannotExceedReceiptOrOutstanding() {
    var student = freshStudent();
    var enrollment = approvedEnrollment(student);
    var small = receipt(student, "500");
    assertThatThrownBy(() -> allocate(small.id(), enrollment.id(), "600"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("RECEIPT_INSUFFICIENT");
    var big = receipt(student, "1500");
    assertThatThrownBy(() -> allocate(big.id(), enrollment.id(), "1200"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("ENROLLMENT_OVERPAID");
    assertThat(allocate(big.id(), enrollment.id(), "1000").amount()).isEqualTo("1000.00");
  }

  @Test
  void allocationRequiresApprovedEnrollment() {
    var student = freshStudent();
    var pending = enroll(student);
    var receipt = receipt(student, "1000");
    assertThatThrownBy(() -> allocate(receipt.id(), pending.id(), "100"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("ENROLLMENT_NOT_ACTIVE");
  }

  @Test
  void allocationRequiresSameStudent() {
    var enrollment = approvedEnrollment(freshStudent());
    var otherStudentReceipt = receipt(freshStudent(), "1000");
    assertThatThrownBy(() -> allocate(otherStudentReceipt.id(), enrollment.id(), "100"))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("STUDENT_MISMATCH");
  }

  @Test
  void releaseFreesReceiptAndEnrollment() {
    var student = freshStudent();
    var enrollment = approvedEnrollment(student);
    var receipt = receipt(student, "1000");
    var allocation = allocate(receipt.id(), enrollment.id(), "1000");
    assertThat(asActor(READER, () -> fundsQueries.outstanding(enrollment.id()))).isEqualTo("0.00");
    var released = asActor(CLERK, () -> fundsCommands.releaseAllocation(allocation.id()));
    assertThat(released.status()).isEqualTo("RELEASED");
    assertThat(asActor(READER, () -> fundsQueries.outstanding(enrollment.id())))
        .isEqualTo("1000.00");
    var refreshed = asActor(READER, () -> fundsQueries.receipts(student));
    assertThat(
            refreshed.stream()
                .filter(item -> item.id().equals(receipt.id()))
                .findFirst()
                .orElseThrow()
                .availableAmount())
        .isEqualTo("1000.00");
  }

  @Test
  void voidReceiptOnlyWhenNothingAllocated() {
    var student = freshStudent();
    var enrollment = approvedEnrollment(student);
    var receipt = receipt(student, "1000");
    var allocation = allocate(receipt.id(), enrollment.id(), "1000");
    assertThatThrownBy(
            () ->
                asActor(
                    CLERK,
                    () ->
                        fundsCommands.voidReceipt(
                            receipt.id(), new FundsVersion(receipt.version()))))
        .isInstanceOf(FundsConflict.class)
        .hasMessage("RECEIPT_HAS_ALLOCATIONS");
    asActor(CLERK, () -> fundsCommands.releaseAllocation(allocation.id()));
    var voided =
        asActor(
            CLERK,
            () -> fundsCommands.voidReceipt(receipt.id(), new FundsVersion(receipt.version())));
    assertThat(voided.status()).isEqualTo("VOIDED");
    assertThat(asActor(READER, () -> fundsQueries.balance(student)).total()).isEqualTo("0.00");
    assertThat(asActor(READER, () -> fundsQueries.outstanding(enrollment.id())))
        .isEqualTo("1000.00");
  }

  @Test
  void receiptRetryIsIdempotent() throws Exception {
    var input =
        new CreateReceipt(UUID.randomUUID(), SCHOOL, freshStudent(), "300", "BANK", "2026-02-11");
    var first = asActor(CLERK, () -> fundsCommands.createReceipt(input));
    assertThat(asActor(CLERK, () -> fundsCommands.createReceipt(input)).id()).isEqualTo(first.id());
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement("select count(*) from funds.account_entry where source_id=?")) {
      s.setObject(1, first.id());
      var r = s.executeQuery();
      r.next();
      assertThat(r.getLong(1)).isEqualTo(1);
    }
  }

  @Test
  void concurrentAllocationsNeverDoubleSpend() throws Exception {
    var student = freshStudent();
    var enrollment = approvedEnrollment(student);
    var receipt = receipt(student, "1000");
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<String> job =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              allocate(receipt.id(), enrollment.id(), "600");
              return "OK";
            } catch (FundsConflict conflict) {
              return conflict.code();
            }
          };
      var a = pool.submit(job);
      var b = pool.submit(job);
      var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
      // 两个请求都超不出不变量：恰好一个成功，另一个按先命中的规则被拒。
      assertThat(results).contains("OK");
      assertThat(results.stream().filter(value -> !value.equals("OK")).findFirst().orElseThrow())
          .isIn("RECEIPT_INSUFFICIENT", "ENROLLMENT_OVERPAID");
      assertThat(asActor(READER, () -> fundsQueries.balance(student)).total()).isEqualTo("400.00");
    }
  }

  @Test
  void readerCannotMoveMoney() {
    var student = freshStudent();
    var enrollment = approvedEnrollment(student);
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        fundsCommands.createReceipt(
                            new CreateReceipt(
                                UUID.randomUUID(), SCHOOL, student, "100", "CASH", "2026-02-10"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    var receipt = receipt(student, "1000");
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        fundsCommands.allocate(
                            new AllocateReceipt(receipt.id(), enrollment.id(), "100"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into funds.receipt(tenant_id,school_id,student_id,amount,method,occurred_on)"
                                + " values (?,?,?,10.00,'CASH','2026-02-10')",
                            TENANT,
                            SCHOOL,
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
  void httpSecondEntryKeepsAmountsAsStrings() throws Exception {
    var student = freshStudent();
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
                    "username=enrollment-clerk&password=" + PASSWORD,
                    csrf.get("token").asText())
                .statusCode())
        .isEqualTo(204);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var enrollmentBody =
        json.writeValueAsString(
            new CreateEnrollment(UUID.randomUUID(), student, PRICE, "2026-04-01"));
    var created = post(client, "/api/enrollment/enrollments", enrollmentBody, token);
    assertThat(created.statusCode()).isEqualTo(201);
    var view = json.readTree(created.body());
    assertThat(view.get("price").isTextual()).isTrue();
    assertThat(view.get("netAmount").asText()).isEqualTo("1000.00");
    var id = UUID.fromString(view.get("id").asText());
    // 只有登记权限的账号不能审核。
    assertThat(
            post(
                    client,
                    "/api/enrollment/enrollments/" + id + "/approve",
                    "{\"expectedVersion\":0}",
                    token)
                .statusCode())
        .isEqualTo(403);
    var receiptBody =
        json.writeValueAsString(
            new CreateReceipt(UUID.randomUUID(), SCHOOL, student, "1000", "CASH", "2026-04-01"));
    var receipt = post(client, "/api/funds/receipts", receiptBody, token);
    assertThat(receipt.statusCode()).isEqualTo(201);
    assertThat(json.readTree(receipt.body()).get("amount").asText()).isEqualTo("1000.00");
    var balance = get(client, "/api/funds/balance?studentId=" + student);
    assertThat(balance.statusCode()).isEqualTo(200);
    assertThat(json.readTree(balance.body()).get("total").isTextual()).isTrue();
  }
}
