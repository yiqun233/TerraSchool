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
import org.teraschool.migration.api.*;

/** 旧库迁移：装载 → 干跑对账 → 确认导入（按旧主键幂等）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyMigrationTest {
  static final UUID TENANT = UUID.randomUUID(), SCHOOL = UUID.randomUUID(), OPS = UUID.randomUUID();

  @Autowired LegacyMigration migration;
  @Autowired org.jooq.DSLContext sql;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "迁移机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','迁移学校')",
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
        "migration-ops",
        encoder.encode(UUID.randomUUID().toString()));
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'MIGRATION_MANAGE','ALLOW')",
        TENANT,
        OPS,
        SCHOOL);
  }

  /** 每个用例从空的暂存区与台账开始，避免相互累计。 */
  @BeforeEach
  void reset() throws Exception {
    update("delete from migration.import_log where tenant_id=?", TENANT);
    update("delete from migration.legacy_receipt where tenant_id=?", TENANT);
    update("delete from migration.legacy_student where tenant_id=?", TENANT);
    // 先删关联关系再删学员，避免外键约束。
    update(
        "delete from archive.student_contact sc using archive.student s"
            + " where sc.student_id=s.id and s.school_id=? and s.student_no like 'L%'",
        SCHOOL);
    update(
        "delete from archive.contact c where c.tenant_id=? and not exists"
            + " (select 1 from archive.student_contact sc where sc.contact_id=c.id)",
        TENANT);
    update("delete from archive.student where school_id=? and student_no like 'L%'", SCHOOL);
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

  List<StageStudent> students() {
    return List.of(
        new StageStudent(
            "L-S-1",
            "L0001",
            "旧学员甲",
            "FEMALE",
            "2014-05-06",
            "110101201405061234",
            "13900001111",
            "甲妈妈",
            "13900002222"),
        new StageStudent(
            "L-S-2", "L0002", "旧学员乙", "MALE", "2013-08-09", null, "13900003333", null, null));
  }

  List<StageReceipt> receipts() {
    return List.of(
        new StageReceipt("L-R-1", "L0001", "1000.00", "CASH", "2026-01-10"),
        new StageReceipt("L-R-2", "L0001", "500.00", "CASH", "2026-02-10"),
        // 学员号在暂存里不存在：干跑要报出来，确认时跳过。
        new StageReceipt("L-R-3", "L9999", "300.00", "CASH", "2026-03-10"));
  }

  @Test
  void dryRunReportsCountsAndIssues() {
    asActor(OPS, () -> migration.stage(SCHOOL, students(), receipts()));
    var report = asActor(OPS, () -> migration.dryRun(SCHOOL));
    assertThat(report.mode()).isEqualTo("DRY_RUN");
    assertThat(report.studentsStaged()).isEqualTo(2);
    assertThat(report.studentsImported()).isZero();
    assertThat(report.stagedReceiptTotal()).isEqualTo("1800.00");
    assertThat(report.issues())
        .anySatisfy(
            issue -> {
              assertThat(issue.kind()).isEqualTo("RECEIPT");
              assertThat(issue.detail()).contains("L9999");
            });
  }

  @Test
  void commitImportsOnceAndIsIdempotent() {
    asActor(OPS, () -> migration.stage(SCHOOL, students(), receipts()));
    var first = asActor(OPS, () -> migration.commit(SCHOOL));
    assertThat(first.mode()).isEqualTo("COMMIT");
    assertThat(first.studentsImported()).isEqualTo(2);
    assertThat(first.importedReceiptTotal()).isEqualTo("1500.00");
    assertThat(asActor(OPS, () -> migration.dryRun(SCHOOL)).studentsStaged()).isEqualTo(2);

    var countBefore = studentCount();
    var again = asActor(OPS, () -> migration.commit(SCHOOL));
    assertThat(studentCount()).isEqualTo(countBefore);
    assertThat(again.studentsPending()).isZero();
  }

  @Test
  void importedStudentsCarryGuardianContact() throws Exception {
    asActor(OPS, () -> migration.stage(SCHOOL, students(), List.of()));
    asActor(OPS, () -> migration.commit(SCHOOL));
    // 直接查询要用管理员连接：应用连接受 RLS 约束，事务外没有 app.tenant_id 会看不到行。
    var contacts = new java.util.ArrayList<String>();
    try (var connection = EngineeringTest.admin();
        var statement =
            connection.prepareStatement(
                "select c.name from archive.student s"
                    + " join archive.student_contact sc on sc.student_id=s.id"
                    + " join archive.contact c on c.id=sc.contact_id"
                    + " where s.student_no='L0001'")) {
      var rows = statement.executeQuery();
      while (rows.next()) contacts.add(rows.getString(1));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    assertThat(contacts).containsExactly("甲妈妈");
  }

  @Test
  void duplicateStudentNumberIsReported() {
    asActor(
        OPS,
        () ->
            migration.stage(
                SCHOOL,
                List.of(
                    new StageStudent(
                        "L-D-1", "L0001", "重复甲", "FEMALE", null, null, null, null, null),
                    new StageStudent(
                        "L-D-2", "L0001", "重复乙", "MALE", null, null, null, null, null)),
                List.of()));
    var report = asActor(OPS, () -> migration.dryRun(SCHOOL));
    assertThat(report.issues())
        .anySatisfy(issue -> assertThat(issue.detail()).contains("同一学员号出现多次"));
  }

  @Test
  void stagingRequiresMigrationPermission() {
    var other = UUID.randomUUID();
    try {
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          other,
          TENANT,
          "migration-nobody",
          "x");
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    assertThatThrownBy(
            () ->
                asActor(
                    other,
                    () ->
                        migration.stage(
                            SCHOOL,
                            List.of(
                                new StageStudent(
                                    "X", "X1", "X", "MALE", null, null, null, null, null)),
                            List.of())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  private int studentCount() {
    try (var connection = EngineeringTest.admin();
        var statement =
            connection.prepareStatement("select count(*) from archive.student where school_id=?")) {
      statement.setObject(1, SCHOOL);
      var rows = statement.executeQuery();
      rows.next();
      return rows.getInt(1);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
