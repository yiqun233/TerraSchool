package org.teraschool.migration.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.migration.api.*;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/**
 * 旧库迁移：暂存 → 干跑 → 确认。
 *
 * <p>只做两类实体（学员与收款）：它们决定"人"和"钱"能不能对上，也是旧库最直接可核对的表。
 * 导入按旧主键记账（`migration.import_log`），因此重复确认是幂等的；学员已存在时按学员号跳过并记一条问题。
 *
 * <p>旧库只有结构、没有数据快照，本实现以暂存区为入口：拿到 FULL/OPENING 快照后灌进暂存区即可跑同一套对账。
 */
@Service
public class LegacyMigrationService implements LegacyMigration {
  private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Set<String> SEX = Set.of("MALE", "FEMALE", "UNKNOWN");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final StudentAccess students;
  private final org.teraschool.funds.api.FundsCommands funds;
  private final org.teraschool.teaching.api.TeachingCommands teaching;

  public LegacyMigrationService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      StudentAccess students,
      org.teraschool.funds.api.FundsCommands funds,
      org.teraschool.teaching.api.TeachingCommands teaching) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.students = students;
    this.funds = funds;
    this.teaching = teaching;
  }

  @Override
  public MigrationReport stage(
      UUID schoolId, List<StageStudent> students, List<StageReceipt> receipts) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      return tx.execute(
          actor.tenantId(),
          () -> {
            requireSchool(actor, schoolId);
            for (var row : students == null ? List.<StageStudent>of() : students) {
              sql.execute(
                  "insert into migration.legacy_student(tenant_id,school_id,legacy_id,student_no,"
                      + "name,sex,birthday,cert_code,phone,contact_name,contact_phone)"
                      + " values (?,?,?,?,?,?,?,?,?,?,?)"
                      + " on conflict (tenant_id,legacy_id) do update set"
                      + " student_no=excluded.student_no,name=excluded.name,sex=excluded.sex,"
                      + " birthday=excluded.birthday,cert_code=excluded.cert_code,"
                      + " phone=excluded.phone,contact_name=excluded.contact_name,"
                      + " contact_phone=excluded.contact_phone",
                  actor.tenantId(),
                  schoolId,
                  text(row.legacyId(), 64, "旧主键"),
                  text(row.studentNo(), 20, "学员号"),
                  text(row.name(), 50, "姓名"),
                  sex(row.sex()),
                  date(row.birthday()),
                  text(row.certCode(), 40, "证件号"),
                  text(row.phone(), 30, "电话"),
                  text(row.contactName(), 50, "联系人"),
                  text(row.contactPhone(), 30, "联系人电话"));
            }
            for (var row : receipts == null ? List.<StageReceipt>of() : receipts) {
              sql.execute(
                  "insert into migration.legacy_receipt(tenant_id,school_id,legacy_id,student_no,"
                      + "amount,method,occurred_on) values (?,?,?,?,?,?,?)"
                      + " on conflict (tenant_id,legacy_id) do update set"
                      + " student_no=excluded.student_no,amount=excluded.amount,"
                      + " method=excluded.method,occurred_on=excluded.occurred_on",
                  actor.tenantId(),
                  schoolId,
                  text(row.legacyId(), 64, "旧主键"),
                  text(row.studentNo(), 20, "学员号"),
                  amount(row.amount()),
                  text(row.method(), 16, "收款方式"),
                  date(row.occurredOn()));
            }
            return report(actor, schoolId, "DRY_RUN");
          });
    }
  }

  @Override
  public MigrationReport dryRun(UUID schoolId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      return tx.execute(actor.tenantId(), () -> report(actor, schoolId, "DRY_RUN"));
    }
  }

  @Override
  public MigrationReport commit(UUID schoolId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            requireSchool(actor, schoolId);
            var importedStudents = 0;
            var importedReceipts = 0;
            for (var row :
                sql.fetch(
                    "select legacy_id,student_no,name,sex,birthday,cert_code,phone,contact_name,"
                        + "contact_phone from migration.legacy_student where school_id=?"
                        + " order by legacy_id",
                    schoolId)) {
              var legacyId = row.get("legacy_id", String.class);
              if (already(actor.tenantId(), "STUDENT", legacyId)) continue;
              var studentNo = row.get("student_no", String.class);
              var existing =
                  sql.fetchOne(
                      "select id from archive.student where school_id=? and student_no=?",
                      schoolId,
                      studentNo);
              if (existing != null) {
                // 已存在的学员只记台账，不覆盖：档案以新系统为准。
                sql.execute(
                    "insert into migration.import_log(tenant_id,kind,legacy_id,target_id)"
                        + " values (?,'STUDENT',?,?)",
                    actor.tenantId(),
                    legacyId,
                    existing.get("id", UUID.class));
                continue;
              }
              var id = UUID.randomUUID();
              // 旧库证件号没有稳定的类型字段，统一按身份证导入；无证件号则为空。
              var certCode = row.get("cert_code", String.class);
              sql.execute(
                  "insert into archive.student(id,tenant_id,student_no,name,sex,birthday,cert_type,"
                      + "cert_code,phone,school_id) values (?,?,?,?,?,?,?,?,?,?)",
                  id,
                  actor.tenantId(),
                  studentNo,
                  row.get("name", String.class),
                  row.get("sex", String.class),
                  row.get("birthday", LocalDate.class),
                  certCode == null ? null : "ID_CARD",
                  certCode,
                  row.get("phone", String.class),
                  schoolId);
              var contactName = row.get("contact_name", String.class);
              var contactPhone = row.get("contact_phone", String.class);
              // 联系人至少要有一种联系方式（库约束）；只有姓名没有电话时跳过联系人，记入问题。
              if (contactName != null && contactPhone != null) {
                var contactId = UUID.randomUUID();
                sql.execute(
                    "insert into archive.contact(id,tenant_id,name,phone) values (?,?,?,?)",
                    contactId,
                    actor.tenantId(),
                    contactName,
                    contactPhone);
                sql.execute(
                    "insert into archive.student_contact(tenant_id,student_id,contact_id,relation,"
                        + "is_primary,position) values (?,?,?,'GUARDIAN',true,0)",
                    actor.tenantId(),
                    id,
                    contactId);
              }
              sql.execute(
                  "insert into migration.import_log(tenant_id,kind,legacy_id,target_id)"
                      + " values (?,'STUDENT',?,?)",
                  actor.tenantId(),
                  legacyId,
                  id);
              importedStudents++;
            }
            for (var row :
                sql.fetch(
                    "select legacy_id,student_no,amount,method,occurred_on"
                        + " from migration.legacy_receipt where school_id=? order by legacy_id",
                    schoolId)) {
              var legacyId = row.get("legacy_id", String.class);
              if (already(actor.tenantId(), "RECEIPT", legacyId)) continue;
              var student =
                  sql.fetchOne(
                      "select id from archive.student where school_id=? and student_no=?",
                      schoolId,
                      row.get("student_no", String.class));
              if (student == null) continue; // 学员缺失已在 dryRun 报出，确认时跳过
              var studentId = student.get("id", UUID.class);
              var id = UUID.randomUUID();
              sql.execute(
                  "insert into funds.receipt(id,tenant_id,school_id,student_id,amount,method,"
                      + "occurred_on) values (?,?,?,?,?,?,?)",
                  id,
                  actor.tenantId(),
                  schoolId,
                  studentId,
                  row.get("amount", BigDecimal.class),
                  row.get("method", String.class),
                  row.get("occurred_on", LocalDate.class));
              sql.execute(
                  "insert into funds.account_entry(tenant_id,student_id,kind,amount,source_id)"
                      + " values (?,?,'RECEIPT',?,?)",
                  actor.tenantId(),
                  studentId,
                  row.get("amount", BigDecimal.class),
                  id);
              sql.execute(
                  "insert into migration.import_log(tenant_id,kind,legacy_id,target_id)"
                      + " values (?,'RECEIPT',?,?)",
                  actor.tenantId(),
                  legacyId,
                  id);
              importedReceipts++;
            }
            var result = report(actor, schoolId, "COMMIT");
            sql.execute(
                "insert into migration.run(tenant_id,school_id,mode,actor_id,students_staged,"
                    + "students_imported,receipts_staged,receipts_imported)"
                    + " values (?,?,'COMMIT',?,?,?,?,?)",
                actor.tenantId(),
                schoolId,
                actor.accountId(),
                result.studentsStaged(),
                importedStudents,
                result.studentsStaged(),
                importedReceipts);
            return result;
          });
    }
  }

  @Override
  public OpeningImport.Report importOpenings(UUID schoolId, OpeningImport request) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      if (request == null || request.rows() == null || request.rows().isEmpty())
        throw new IllegalArgumentException("期初导入至少需要一行");
      if (request.batchId() == null || request.batchId().isBlank())
        throw new IllegalArgumentException("期初导入需要批次号");
      var occurredOn =
          request.occurredOn() == null || request.occurredOn().isBlank()
              ? LocalDate.now().toString()
              : date(request.occurredOn()).toString();
      var reason = text(request.reason(), 200, "期初原因");
      // 学校校验与学员查询都在租户上下文里：organization.unit 启用了 FORCE RLS。
      tx.execute(
          actor.tenantId(),
          () -> {
            requireSchool(actor, schoolId);
            return null;
          });
      var results = new ArrayList<OpeningImport.RowResult>();
      var ok = 0;
      var skipped = 0;
      var failed = 0;
      var totalAmount = BigDecimal.ZERO;
      var totalHours = BigDecimal.ZERO;
      for (var row : request.rows()) {
        if (row == null || row.studentNo() == null || row.studentNo().isBlank()) {
          results.add(new OpeningImport.RowResult("", "FAILED", "缺少学员号"));
          failed++;
          continue;
        }
        var studentNo = row.studentNo().strip();
        var studentId = studentIdOf(actor, schoolId, studentNo);
        if (studentId == null) {
          results.add(new OpeningImport.RowResult(studentNo, "FAILED", "新库没有该学员"));
          failed++;
          continue;
        }
        if (request.dryRun()) {
          results.add(
              new OpeningImport.RowResult(
                  studentNo,
                  "READY",
                  "可导入：金额 " + blankToZero(row.amount()) + "，课时 " + blankToZero(row.hours())));
          ok++;
          totalAmount = totalAmount.add(new BigDecimal(blankToZero(row.amount())));
          totalHours = totalHours.add(new BigDecimal(blankToZero(row.hours())));
          continue;
        }
        // 每行独立事务：单行失败不影响其他行，也不会把整批标记为回滚。
        var notes = new ArrayList<String>();
        try {
          var amount = new BigDecimal(blankToZero(row.amount()));
          if (amount.signum() != 0) {
            funds.openBalance(
                new org.teraschool.funds.api.OpenBalance(
                    rowRequestId(request.batchId(), studentNo, "AMOUNT"),
                    schoolId,
                    studentId,
                    amount.toPlainString(),
                    occurredOn,
                    reason == null ? "旧库余额迁移" : reason));
            notes.add("余额 " + amount.toPlainString());
            totalAmount = totalAmount.add(amount);
          }
          var hours = new BigDecimal(blankToZero(row.hours()));
          if (hours.signum() > 0) {
            var enrollmentId = activeEnrollmentOf(actor, schoolId, studentId);
            if (enrollmentId == null) {
              notes.add("没有在读报名，课时未带入");
            } else {
              try {
                teaching.openingConsumption(
                    enrollmentId,
                    new org.teraschool.teaching.api.OpeningConsumption(
                        hours.toPlainString(),
                        new BigDecimal(blankToZero(row.consumedAmount())).toPlainString(),
                        reason == null ? "旧库已消耗迁移" : reason));
                notes.add("已消耗课时 " + hours.toPlainString());
                totalHours = totalHours.add(hours);
              } catch (org.teraschool.teaching.api.TeachingConflict conflict) {
                if (!"OPENING_ALREADY_RECORDED".equals(conflict.getMessage())) throw conflict;
                notes.add("课时已存在，跳过");
              }
            }
          }
          results.add(
              new OpeningImport.RowResult(
                  studentNo, notes.isEmpty() ? "SKIPPED" : "OK", String.join("；", notes)));
          if (notes.isEmpty()) skipped++;
          else ok++;
        } catch (RuntimeException error) {
          results.add(
              new OpeningImport.RowResult(
                  studentNo, "FAILED", error.getMessage() == null ? "导入失败" : error.getMessage()));
          failed++;
        }
      }
      var report =
          new OpeningImport.Report(
              request.batchId(),
              request.dryRun() ? "DRY_RUN" : "COMMIT",
              ok,
              skipped,
              failed,
              money(totalAmount),
              hoursView(totalHours),
              results);
      // lambda 里只能用 effectively final 的值，先复制到局部变量。
      final var runOk = ok;
      final var runFailed = failed;
      final var runMode = request.dryRun() ? "DRY_RUN" : "COMMIT";
      tx.execute(
          actor.tenantId(),
          () -> {
            sql.execute(
                "insert into migration.run(tenant_id,school_id,mode,actor_id,students_staged,"
                    + "students_imported,receipts_staged,receipts_imported,issues)"
                    + " values (?,?,?,?,?,?,?,?,?::jsonb)",
                actor.tenantId(),
                schoolId,
                runMode,
                actor.accountId(),
                runOk,
                runFailed,
                0,
                0,
                "[]");
            return null;
          });
      return report;
    }
  }

  private UUID studentIdOf(Actor actor, UUID schoolId, String studentNo) {
    return tx.execute(
        actor.tenantId(),
        () -> {
          var row =
              sql.fetchOne(
                  "select id from archive.student where school_id=? and student_no=?",
                  schoolId,
                  studentNo);
          return row == null ? null : row.get("id", UUID.class);
        });
  }

  private UUID activeEnrollmentOf(Actor actor, UUID schoolId, UUID studentId) {
    return tx.execute(
        actor.tenantId(),
        () -> {
          var row =
              sql.fetchOne(
                  "select id from enrollment.enrollment where school_id=? and student_id=?"
                      + " and status='ACTIVE' order by created_at desc limit 1",
                  schoolId,
                  studentId);
          return row == null ? null : row.get("id", UUID.class);
        });
  }

  private String blankToZero(String value) {
    return value == null || value.isBlank() ? "0" : value.strip();
  }

  private String hoursView(BigDecimal value) {
    return value == null ? "0.00" : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private UUID rowRequestId(String batchId, String studentNo, String kind) {
    return UUID.nameUUIDFromBytes(
        (batchId + "|" + studentNo + "|" + kind).getBytes(StandardCharsets.UTF_8));
  }

  // ---------------------------------------------------------------- 对账

  private MigrationReport report(Actor actor, UUID schoolId, String mode) {
    requireSchool(actor, schoolId);
    var issues = new ArrayList<MigrationIssue>();
    var studentsStaged =
        count("select count(*) from migration.legacy_student where school_id=?", schoolId);
    var studentsImported =
        count(
            "select count(*) from migration.import_log l join migration.legacy_student s"
                + " on s.legacy_id=l.legacy_id and s.tenant_id=l.tenant_id"
                + " where s.school_id=? and l.kind='STUDENT'",
            schoolId);
    var receiptsStaged =
        count("select count(*) from migration.legacy_receipt where school_id=?", schoolId);
    var receiptsImported =
        count(
            "select count(*) from migration.import_log l join migration.legacy_receipt r"
                + " on r.legacy_id=l.legacy_id and r.tenant_id=l.tenant_id"
                + " where r.school_id=? and l.kind='RECEIPT'",
            schoolId);
    var stagedTotal =
        sum(
            "select coalesce(sum(amount),0) from migration.legacy_receipt where school_id=?",
            schoolId);
    var importedTotal =
        sum(
            "select coalesce(sum(r.amount),0) from migration.legacy_receipt r"
                + " join migration.import_log l on l.legacy_id=r.legacy_id"
                + " and l.tenant_id=r.tenant_id and l.kind='RECEIPT'"
                + " where r.school_id=?",
            schoolId);

    for (var row :
        sql.fetch(
            "select legacy_id,student_no from migration.legacy_student where school_id=?"
                + " order by legacy_id",
            schoolId)) {
      var studentNo = row.get("student_no", String.class);
      var exists =
          sql.fetchOne(
              "select 1 from archive.student where school_id=? and student_no=?",
              schoolId,
              studentNo);
      if (exists == null)
        issues.add(
            new MigrationIssue(
                "STUDENT", row.get("legacy_id", String.class), "新库还没有学员号 " + studentNo));
      var duplicated =
          sql.fetchOne(
              "select count(*) as total from migration.legacy_student where school_id=?"
                  + " and student_no=?",
              schoolId,
              studentNo);
      if (duplicated.get("total", Integer.class) > 1)
        issues.add(
            new MigrationIssue(
                "STUDENT", row.get("legacy_id", String.class), "旧库同一学员号出现多次：" + studentNo));
    }
    for (var row :
        sql.fetch(
            "select legacy_id,student_no,amount from migration.legacy_receipt where school_id=?"
                + " order by legacy_id",
            schoolId)) {
      var studentNo = row.get("student_no", String.class);
      var student =
          sql.fetchOne(
              "select id from archive.student where school_id=? and student_no=?",
              schoolId,
              studentNo);
      if (student == null) {
        issues.add(
            new MigrationIssue(
                "RECEIPT", row.get("legacy_id", String.class), "找不到学员号 " + studentNo + "，收款无法归属"));
        continue;
      }
      var existing =
          sql.fetchOne(
              "select amount from funds.receipt where student_id=? and occurred_on is not null"
                  + " and amount=?",
              student.get("id", UUID.class),
              row.get("amount", BigDecimal.class));
      if (existing == null)
        issues.add(
            new MigrationIssue(
                "RECEIPT",
                row.get("legacy_id", String.class),
                "新库没有金额 " + row.get("amount", BigDecimal.class) + " 的收款记录"));
    }
    return new MigrationReport(
        schoolId,
        mode,
        studentsStaged,
        studentsImported,
        Math.max(0, studentsStaged - studentsImported),
        money(stagedTotal),
        money(importedTotal),
        issues);
  }

  private boolean already(UUID tenantId, String kind, String legacyId) {
    return sql.fetchOne(
            "select 1 from migration.import_log where tenant_id=? and kind=? and legacy_id=?",
            tenantId,
            kind,
            legacyId)
        != null;
  }

  private int count(String query, Object... args) {
    var value = sql.fetchOne(query, args).get(0, Integer.class);
    return value == null ? 0 : value;
  }

  private BigDecimal sum(String query, Object... args) {
    var value = sql.fetchOne(query, args).get(0, BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private void requireSchool(Actor actor, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!actor.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的数据迁移权限");
    if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能迁移");
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private String sex(String value) {
    var text =
        value == null || value.isBlank() ? "UNKNOWN" : value.strip().toUpperCase(Locale.ROOT);
    if (!SEX.contains(text)) throw new IllegalArgumentException("性别取值无效");
    return text;
  }

  private BigDecimal amount(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("金额必填");
    try {
      var amount = new BigDecimal(value.strip());
      if (amount.signum() <= 0 || amount.scale() > 2) throw new IllegalArgumentException("金额无效");
      return amount.setScale(2, RoundingMode.UNNECESSARY);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("金额无效");
    }
  }

  private LocalDate date(String value) {
    if (value == null || value.isBlank()) return null;
    if (!DAY.matcher(value.strip()).matches())
      throw new IllegalArgumentException("日期须为 YYYY-MM-DD");
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("日期无效");
    }
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }
}
