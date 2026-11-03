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
import org.teraschool.funds.api.*;

/** 期初余额：迁移旧库余额时落 OPENING 分录，可正可负，幂等，受锁月约束。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpeningBalanceTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      UNLOCKER = UUID.randomUUID();

  @Autowired FundsCommands funds;
  @Autowired FundsQueries fundsQueries;
  @Autowired org.teraschool.finance.api.PeriodCommands periods;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "期初机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','期初学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        OPS,
        TENANT,
        "opening-ops",
        encoder.encode(UUID.randomUUID().toString()));
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        UNLOCKER,
        TENANT,
        "opening-unlocker",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action :
        new String[] {
          "FUNDS_READ",
          "FUNDS_RECEIPT",
          "FUNDS_ALLOCATE",
          "PERIOD_READ",
          "PERIOD_LOCK",
          "MIGRATION_MANAGE"
        })
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
    for (var action : new String[] {"PERIOD_READ", "PERIOD_UNLOCK"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          UNLOCKER,
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

  UUID student() {
    var id = UUID.randomUUID();
    try {
      update(
          "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
          id,
          TENANT,
          "O" + id.toString().replace("-", "").substring(0, 6),
          "期初学员",
          "FEMALE",
          SCHOOL);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return id;
  }

  OpenBalance opening(UUID student, String amount) {
    return new OpenBalance(UUID.randomUUID(), SCHOOL, student, amount, "2026-02-10", "旧库余额迁移");
  }

  @Test
  void positiveOpeningIncreasesBalance() {
    var student = student();
    var balance = asActor(OPS, () -> funds.openBalance(opening(student, "320.50")));
    assertThat(balance.total()).isEqualTo("320.50");
    assertThat(balance.available()).isEqualTo("320.50");
    assertThat(asActor(OPS, () -> fundsQueries.receipts(student))).isEmpty();
  }

  @Test
  void fundsReadAloneCannotPostOpening() throws Exception {
    var reader = UUID.randomUUID();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        reader,
        TENANT,
        "opening-reader",
        "unused");
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect) values (?,?,?,'FUNDS_READ','ALLOW')",
        TENANT,
        reader,
        SCHOOL);
    assertThatThrownBy(() -> asActor(reader, () -> funds.openBalance(opening(student(), "100.00"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void negativeOpeningRepresentsArrears() {
    var student = student();
    var balance = asActor(OPS, () -> funds.openBalance(opening(student, "-120.00")));
    assertThat(balance.total()).isEqualTo("-120.00");
    assertThat(balance.available()).isEqualTo("-120.00");
  }

  @Test
  void sameRequestIdIsIdempotent() {
    var student = student();
    var command = opening(student, "100.00");
    var first = asActor(OPS, () -> funds.openBalance(command));
    var again = asActor(OPS, () -> funds.openBalance(command));
    assertThat(again.total()).isEqualTo(first.total());
  }

  @Test
  void lockedPeriodRejectsOpening() {
    var student = student();
    asActor(
        OPS,
        () ->
            periods.lock(
                SCHOOL,
                new org.teraschool.finance.api.LockPeriod(SCHOOL, "2026-02", "月末结账", null)));
    assertThatThrownBy(() -> asActor(OPS, () -> funds.openBalance(opening(student, "100.00"))))
        .isInstanceOf(org.teraschool.finance.api.PeriodConflict.class)
        .hasMessage("PERIOD_LOCKED");
    asActor(
        UNLOCKER,
        () ->
            periods.unlock(
                SCHOOL, new org.teraschool.finance.api.LockPeriod(SCHOOL, "2026-02", "更正", 0L)));
    assertThat(asActor(OPS, () -> funds.openBalance(opening(student, "100.00"))).total())
        .isEqualTo("100.00");
  }

  @Test
  void zeroAmountIsRejected() {
    var student = student();
    assertThatThrownBy(() -> asActor(OPS, () -> funds.openBalance(opening(student, "0"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
