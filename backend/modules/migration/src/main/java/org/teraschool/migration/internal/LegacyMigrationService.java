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
 * 旧库暂存、预检及期初批量导入。
 *
 * <p>旧主体确认导入已停用，须完成领域契约及真实样本验证后才能开放；期初批次另行保存清单和事实。
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
  private final org.teraschool.finance.api.PeriodGate periods;
  private final LegacyPreflightService preflight;

  public LegacyMigrationService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      StudentAccess students,
      org.teraschool.funds.api.FundsCommands funds,
      org.teraschool.teaching.api.TeachingCommands teaching,
      org.teraschool.finance.api.PeriodGate periods,
      LegacyPreflightService preflight) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.students = students;
    this.funds = funds;
    this.teaching = teaching;
    this.periods = periods;
    this.preflight = preflight;
  }

  @Override
  public LegacyPreflight.PreflightReport previewLegacyLoad(
      UUID schoolId, LegacyPreflight.PreflightRequest request) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return preflight.preview(schoolId, request);
    }
  }

  @Override
  public MigrationReport stage(
      UUID schoolId, List<StageStudent> students, List<StageReceipt> receipts) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("MIGRATION_MANAGE");
            requireSchool(verified, schoolId);
            for (var row : students == null ? List.<StageStudent>of() : students) {
              var changed =
                  sql.execute(
                      "insert into migration.legacy_student(tenant_id,school_id,legacy_id,student_no,"
                          + "name,sex,birthday,cert_code,phone,contact_name,contact_phone)"
                          + " values (?,?,?,?,?,?,?,?,?,?,?)"
                          + " on conflict (tenant_id,legacy_id) do update set"
                          + " student_no=excluded.student_no,name=excluded.name,sex=excluded.sex,"
                          + " birthday=excluded.birthday,cert_code=excluded.cert_code,"
                          + " phone=excluded.phone,contact_name=excluded.contact_name,"
                          + " contact_phone=excluded.contact_phone"
                          + " where migration.legacy_student.school_id=excluded.school_id",
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
              if (changed != 1) throw new MigrationConflict("LEGACY_SOURCE_SCHOOL_CONFLICT");
            }
            for (var row : receipts == null ? List.<StageReceipt>of() : receipts) {
              var changed =
                  sql.execute(
                      "insert into migration.legacy_receipt(tenant_id,school_id,legacy_id,student_no,"
                          + "amount,method,occurred_on) values (?,?,?,?,?,?,?)"
                          + " on conflict (tenant_id,legacy_id) do update set"
                          + " student_no=excluded.student_no,amount=excluded.amount,"
                          + " method=excluded.method,occurred_on=excluded.occurred_on"
                          + " where migration.legacy_receipt.school_id=excluded.school_id",
                      actor.tenantId(),
                      schoolId,
                      text(row.legacyId(), 64, "旧主键"),
                      text(row.studentNo(), 20, "学员号"),
                      amount(row.amount()),
                      text(row.method(), 16, "收款方式"),
                      date(row.occurredOn()));
              if (changed != 1) throw new MigrationConflict("LEGACY_SOURCE_SCHOOL_CONFLICT");
            }
            return report(verified, schoolId, "DRY_RUN");
          });
    }
  }

  @Override
  public MigrationReport dryRun(UUID schoolId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            return report(
                actors.requireActionInTransaction("MIGRATION_MANAGE"), schoolId, "DRY_RUN");
          });
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
            requireSchool(actors.requireActionInTransaction("MIGRATION_MANAGE"), schoolId);
            // 原实现绕过领域写入和锁账校验；受控契约及真实样本验收前禁止提交。
            throw new MigrationConflict("LEGACY_COMMIT_DISABLED");
          });
    }
  }

  @Override
  public OpeningImport.Report importOpenings(UUID schoolId, OpeningImport request) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      if (schoolId == null || request == null || request.rows() == null || request.rows().isEmpty())
        throw new IllegalArgumentException("需要学校和期初导入行");
      if (request.rows().size() > 5000) throw new IllegalArgumentException("单批最多 5000 行");
      for (var row : request.rows()) {
        if (row != null
            && java.util.stream.Stream.of(
                    row.studentNo(), row.amount(), row.hours(), row.consumedAmount())
                .anyMatch(value -> value != null && value.length() > 200))
          throw new IllegalArgumentException("期初行字段过长");
      }
      var batch = text(request.batchId(), 64, "批次号");
      if (batch == null) throw new IllegalArgumentException("需要批次号");
      var occurredOn = date(request.occurredOn());
      if (occurredOn == null) throw new IllegalArgumentException("需要明确的期初日期，重试时须保持一致");
      var reason = text(request.reason(), 200, "期初原因");
      prepareOpeningBatch(
          actor, schoolId, batch, occurredOn, reason, request.rows(), request.dryRun());
      var results = new ArrayList<OpeningImport.RowResult>();
      var seen = new HashSet<String>();
      var ok = 0;
      var skipped = 0;
      var failed = 0;
      var totalAmount = BigDecimal.ZERO;
      var totalHours = BigDecimal.ZERO;
      int rowNo = 0;
      for (var row : request.rows()) {
        final int position = ++rowNo;
        String studentNo;
        try {
          studentNo = row == null ? null : text(row.studentNo(), 20, "学员号");
        } catch (IllegalArgumentException invalid) {
          results.add(new OpeningImport.RowResult("", "FAILED", invalid.getMessage()));
          if (!request.dryRun())
            recordOpeningFailure(actor, schoolId, batch, position, invalid.getMessage());
          failed++;
          continue;
        }
        if (studentNo == null || !seen.add(studentNo)) {
          results.add(
              new OpeningImport.RowResult(
                  studentNo == null ? "" : studentNo, "FAILED", "学员号为空或批次内重复"));
          if (!request.dryRun())
            recordOpeningFailure(actor, schoolId, batch, position, "学员号为空或批次内重复");
          failed++;
          continue;
        }
        try {
          var amount = openingNumber(row.amount(), true, "期初金额");
          var hours = openingNumber(row.hours(), false, "期初课时");
          var consumedAmount = openingNumber(row.consumedAmount(), false, "已消耗金额");
          if (hours.signum() == 0 && consumedAmount.signum() != 0)
            throw new IllegalArgumentException("无期初课时时不能导入消费金额");
          java.util.function.Supplier<String> work =
              () -> {
                var result =
                    openingRow(
                        actor,
                        schoolId,
                        batch,
                        studentNo,
                        amount,
                        hours,
                        consumedAmount,
                        occurredOn,
                        reason);
                if (!request.dryRun())
                  sql.execute(
                      "update migration.opening_batch_row set status='OK',detail=null where school_id=? and batch_id=? and row_no=?",
                      schoolId,
                      batch,
                      position);
                return result;
              };
          var outcome =
              request.dryRun()
                  ? tx.preview(actor.tenantId(), work)
                  : tx.execute(actor.tenantId(), work);
          var status = request.dryRun() ? "READY" : outcome;
          results.add(
              new OpeningImport.RowResult(
                  studentNo,
                  status,
                  "期初金额 " + amount.toPlainString() + "，课时 " + hours.toPlainString()));
          if ("SKIPPED".equals(status)) skipped++;
          else ok++;
          totalAmount = totalAmount.add(amount);
          totalHours = totalHours.add(hours);
        } catch (RuntimeException error) {
          var detail =
              error instanceof IllegalArgumentException
                  ? error.getMessage()
                  : "期初导入被拒绝，请核对权限、期间、已有记录和课时";
          results.add(new OpeningImport.RowResult(studentNo, "FAILED", detail));
          if (!request.dryRun()) recordOpeningFailure(actor, schoolId, batch, position, detail);
          failed++;
        }
      }
      return new OpeningImport.Report(
          batch,
          request.dryRun() ? "DRY_RUN" : "COMMIT",
          ok,
          skipped,
          failed,
          money(totalAmount),
          hoursView(totalHours),
          results);
    }
  }

  /** 清单及各行原始内容一次落库；同批次只允许重试完全相同的输入。 */
  private void prepareOpeningBatch(
      Actor actor,
      UUID schoolId,
      String batch,
      LocalDate date,
      String reason,
      List<OpeningImport.Row> rows,
      boolean preview) {
    java.util.function.Supplier<Void> work =
        () -> {
          schools.lockTenant(actor.tenantId());
          requireSchool(actors.requireActionInTransaction("MIGRATION_MANAGE"), schoolId);
          var existing =
              sql.fetchOne(
                  "select occurred_on,reason,row_count from migration.opening_batch where school_id=? and batch_id=?",
                  schoolId,
                  batch);
          if (existing == null) {
            if (sql.fetchExists(
                    sql.selectOne()
                        .from("migration.opening_fact")
                        .where("school_id=? and batch_id=?", schoolId, batch))
                || sql.fetchExists(
                    sql.selectOne()
                        .from("migration.opening_row")
                        .where("school_id=? and batch_id=?", schoolId, batch)))
              throw new MigrationConflict("OPENING_LEGACY_BATCH_UNVERIFIED");
            sql.execute(
                "insert into migration.opening_batch(tenant_id,school_id,batch_id,occurred_on,reason,row_count) values (?,?,?,?,?,?)",
                actor.tenantId(),
                schoolId,
                batch,
                date,
                reason,
                rows.size());
            for (int i = 0; i < rows.size(); i++) {
              var row = rows.get(i);
              sql.execute(
                  "insert into migration.opening_batch_row(tenant_id,school_id,batch_id,row_no,row_is_null,student_no,amount,hours,consumed_amount,status) values (?,?,?,?,?,?,?,?,?,'PENDING')",
                  actor.tenantId(),
                  schoolId,
                  batch,
                  i + 1,
                  row == null,
                  row == null ? null : row.studentNo(),
                  row == null ? null : row.amount(),
                  row == null ? null : row.hours(),
                  row == null ? null : row.consumedAmount());
            }
          } else {
            var stored =
                sql.fetch(
                    "select * from migration.opening_batch_row where school_id=? and batch_id=? order by row_no",
                    schoolId,
                    batch);
            if (!date.equals(existing.get("occurred_on", LocalDate.class))
                || !Objects.equals(reason, existing.get("reason", String.class))
                || existing.get("row_count", Integer.class) != rows.size()
                || stored.size() != rows.size())
              throw new MigrationConflict("OPENING_BATCH_MISMATCH");
            for (int i = 0; i < rows.size(); i++) {
              var row = rows.get(i);
              var saved = stored.get(i);
              if (saved.get("row_no", Integer.class) != i + 1
                  || saved.get("row_is_null", Boolean.class) != (row == null)
                  || !Objects.equals(
                      saved.get("student_no", String.class), row == null ? null : row.studentNo())
                  || !Objects.equals(
                      saved.get("amount", String.class), row == null ? null : row.amount())
                  || !Objects.equals(
                      saved.get("hours", String.class), row == null ? null : row.hours())
                  || !Objects.equals(
                      saved.get("consumed_amount", String.class),
                      row == null ? null : row.consumedAmount()))
                throw new MigrationConflict("OPENING_BATCH_MISMATCH");
            }
          }
          return null;
        };
    if (preview) tx.preview(actor.tenantId(), work);
    else tx.execute(actor.tenantId(), work);
  }

  /** 失败单独记账；并发重试已成功时不能覆盖成功状态。 */
  private void recordOpeningFailure(
      Actor actor, UUID schoolId, String batch, int rowNo, String detail) {
    tx.execute(
        actor.tenantId(),
        () -> {
          schools.lockTenant(actor.tenantId());
          requireSchool(actors.requireActionInTransaction("MIGRATION_MANAGE"), schoolId);
          sql.execute(
              "update migration.opening_batch_row set status='FAILED',detail=? where school_id=? and batch_id=? and row_no=? and status<>'OK'",
              detail,
              schoolId,
              batch,
              rowNo);
          return null;
        });
  }

  private BigDecimal openingNumber(String value, boolean negativeAllowed, String label) {
    try {
      var result = new BigDecimal(blankToZero(value)).setScale(2, RoundingMode.UNNECESSARY);
      if ((!negativeAllowed && result.signum() < 0) || result.precision() > 14)
        throw new IllegalArgumentException(label + "超出范围");
      return result;
    } catch (NumberFormatException | ArithmeticException invalid) {
      throw new IllegalArgumentException(label + "须为最多两位小数的数值");
    }
  }

  /** 每行一个外层事务；所有事实、来源和成功结果一起提交，失败整体回滚。 */
  private String openingRow(
      Actor actor,
      UUID schoolId,
      String batch,
      String studentNo,
      BigDecimal amount,
      BigDecimal hours,
      BigDecimal consumedAmount,
      LocalDate occurredOn,
      String reason) {
    schools.lockTenant(actor.tenantId());
    var verified = actors.requireActionInTransaction("MIGRATION_MANAGE");
    requireSchool(verified, schoolId);
    var studentId = studentIdOf(verified, schoolId, studentNo);
    if (studentId == null) throw new IllegalArgumentException("新库没有该学员");
    var old =
        sql.fetchOne(
            "select * from migration.opening_fact where school_id=? and batch_id=? and student_no=?",
            schoolId,
            batch,
            studentNo);
    if (old != null) {
      var enrollmentId = old.get("enrollment_id", UUID.class);
      if (amount.signum() != 0
          && !actors
              .requireResourceScopeInTransaction("FUNDS")
              .readableSchoolIds()
              .contains(schoolId)) throw new AccessDeniedException("无该学校的资金读取权限");
      if (hours.signum() > 0
          && !actors.requireActionInTransaction("TEACHING_MAINTAIN").schools().contains(schoolId))
        throw new AccessDeniedException("无该学校的期初课时权限");
      if (!studentId.equals(old.get("student_id", UUID.class))
          || !Objects.equals(enrollmentId, old.get("enrollment_id", UUID.class))
          || amount.compareTo(old.get("amount", BigDecimal.class)) != 0
          || hours.compareTo(old.get("hours", BigDecimal.class)) != 0
          || consumedAmount.compareTo(old.get("consumed_amount", BigDecimal.class)) != 0
          || !occurredOn.equals(old.get("occurred_on", LocalDate.class))
          || !Objects.equals(reason, old.get("reason", String.class)))
        throw new IllegalArgumentException("批次行与原请求不一致");
      var accountId = old.get("account_entry_id", UUID.class);
      var consumptionId = old.get("consumption_id", UUID.class);
      if (amount.signum() != 0
          && (accountId == null
              || !sql.fetchExists(
                  sql.selectOne()
                      .from("funds.account_entry")
                      .where(
                          "id=? and student_id=? and kind='OPENING' and amount=?",
                          accountId,
                          studentId,
                          amount)))) throw new IllegalArgumentException("原期初金额事实缺失或不一致");
      if (hours.signum() > 0
          && (consumptionId == null
              || !sql.fetchExists(
                  sql.selectOne()
                      .from("teaching.consumption")
                      .where(
                          "id=? and enrollment_id=? and source='OPENING' and hours=? and amount=? and opening_occurred_on=?",
                          consumptionId,
                          enrollmentId,
                          hours,
                          consumedAmount,
                          occurredOn)))) throw new IllegalArgumentException("原期初消费事实缺失或不一致");
      return "SKIPPED";
    }
    var enrollmentId =
        hours.signum() > 0 ? activeEnrollmentOf(verified, schoolId, studentId) : null;
    if (hours.signum() > 0 && enrollmentId == null) throw new IllegalArgumentException("没有在读报名");
    // 旧台账没有可靠事实映射：不推测其来源，阻止自动重放造成重复记账。
    if (sql.fetchExists(
        sql.selectOne()
            .from("migration.opening_row")
            .where("batch_id=? and student_no=?", batch, studentNo)))
      throw new IllegalArgumentException("历史批次行缺少事实映射，请人工核验");
    periods.requireOpen(schoolId, occurredOn);
    UUID accountEntryId = null;
    UUID consumptionId = null;
    if (amount.signum() != 0) {
      var requestId = rowRequestId(schoolId + "|" + batch, studentNo, "AMOUNT");
      funds.openBalance(
          new org.teraschool.funds.api.OpenBalance(
              requestId,
              schoolId,
              studentId,
              amount.toPlainString(),
              occurredOn.toString(),
              reason == null ? "旧库余额迁移" : reason));
      var entry =
          sql.fetchOne(
              "select resource_id from funds.creation_request where tenant_id=? and actor_id=? and request_id=? and kind='OPENING'",
              actor.tenantId(),
              actor.accountId(),
              requestId);
      if (entry == null) throw new IllegalStateException("期初分录来源不可核验");
      accountEntryId = entry.get("resource_id", UUID.class);
    }
    if (hours.signum() > 0) {
      teaching.openingConsumption(
          enrollmentId,
          new org.teraschool.teaching.api.OpeningConsumption(
              hours.toPlainString(),
              consumedAmount.toPlainString(),
              reason == null ? "旧库已消耗迁移" : reason,
              occurredOn.toString()));
      var consumption =
          sql.fetchOne(
              "select id from teaching.consumption where enrollment_id=? and source='OPENING'",
              enrollmentId);
      if (consumption == null) throw new IllegalStateException("期初消费来源不可核验");
      consumptionId = consumption.get("id", UUID.class);
    }
    sql.execute(
        "insert into migration.opening_fact(tenant_id,school_id,batch_id,student_no,student_id,enrollment_id,amount,hours,consumed_amount,occurred_on,reason,account_entry_id,consumption_id) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        schoolId,
        batch,
        studentNo,
        studentId,
        enrollmentId,
        amount,
        hours,
        consumedAmount,
        occurredOn,
        reason,
        accountEntryId,
        consumptionId);
    return "OK";
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
          var rows =
              sql.fetch(
                  "select id from enrollment.enrollment where school_id=? and student_id=?"
                      + " and status='ACTIVE' order by created_at desc limit 2",
                  schoolId,
                  studentId);
          if (rows.size() > 1) throw new IllegalArgumentException("存在多条在读报名，不能自动选择期初课时归属");
          return rows.isEmpty() ? null : rows.get(0).get("id", UUID.class);
        });
  }

  private void recordOpeningRow(
      Actor actor,
      UUID schoolId,
      String batchId,
      String studentNo,
      String expectedAmount,
      String expectedHours,
      String status,
      String detail) {
    tx.execute(
        actor.tenantId(),
        () -> {
          sql.execute(
              "insert into migration.opening_row(tenant_id,school_id,batch_id,student_no,"
                  + "expected_amount,expected_hours,status,detail) values (?,?,?,?,?,?,?,?)"
                  + " on conflict (tenant_id,batch_id,student_no) do update set"
                  + " expected_amount=excluded.expected_amount,"
                  + " expected_hours=excluded.expected_hours,status=excluded.status,"
                  + " detail=excluded.detail",
              actor.tenantId(),
              schoolId,
              batchId,
              studentNo,
              new BigDecimal(expectedAmount),
              new BigDecimal(expectedHours),
              status,
              detail == null || detail.isBlank() ? null : text(detail, 300, "说明"));
          return null;
        });
  }

  @Override
  public OpeningReconciliation reconcileOpenings(UUID schoolId, String batchId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var actor = actors.requireAction("MIGRATION_MANAGE");
      var batch = text(batchId, 64, "批次号");
      if (batch == null) throw new IllegalArgumentException("需要批次号");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("MIGRATION_MANAGE");
            requireSchool(verified, schoolId);
            if (!actors
                    .requireResourceScopeInTransaction("FUNDS")
                    .readableSchoolIds()
                    .contains(schoolId)
                || !actors
                    .requireActionInTransaction("TEACHING_MAINTAIN")
                    .schools()
                    .contains(schoolId)) throw new AccessDeniedException("对账需要该学校的资金读取和教务维护权限");
            var facts =
                sql.fetch(
                    "select * from migration.opening_fact where school_id=? and batch_id=? order by student_no",
                    schoolId,
                    batch);
            var manifest =
                sql.fetchOne(
                    "select row_count from migration.opening_batch where school_id=? and batch_id=?",
                    schoolId,
                    batch);
            var manifestRows =
                sql.fetch(
                    "select * from migration.opening_batch_row where school_id=? and batch_id=? order by row_no",
                    schoolId,
                    batch);
            var details = new ArrayList<OpeningReconciliation.Row>();
            var matched = 0;
            var mismatched = 0;
            var expectedAmount = BigDecimal.ZERO;
            var actualAmount = BigDecimal.ZERO;
            var expectedHours = BigDecimal.ZERO;
            var actualHours = BigDecimal.ZERO;
            for (var fact : facts) {
              var number = fact.get("student_no", String.class);
              var sourceRow =
                  manifestRows.stream()
                      .filter(
                          r ->
                              "OK".equals(r.get("status", String.class))
                                  && r.get("student_no", String.class) != null
                                  && number.equals(r.get("student_no", String.class).strip()))
                      .findFirst()
                      .orElse(null);
              var expectedA = fact.get("amount", BigDecimal.class);
              var expectedH = fact.get("hours", BigDecimal.class);
              var expectedC = fact.get("consumed_amount", BigDecimal.class);
              var studentId = fact.get("student_id", UUID.class);
              var enrollmentId = fact.get("enrollment_id", UUID.class);
              var entryId = fact.get("account_entry_id", UUID.class);
              var consumptionId = fact.get("consumption_id", UUID.class);
              var entry =
                  entryId == null
                      ? null
                      : sql.fetchOne(
                          "select student_id,kind,amount from funds.account_entry where id=?",
                          entryId);
              var consumption =
                  consumptionId == null
                      ? null
                      : sql.fetchOne(
                          "select enrollment_id,source,hours,amount,opening_occurred_on from teaching.consumption where id=?",
                          consumptionId);
              var actualA = entry == null ? BigDecimal.ZERO : entry.get("amount", BigDecimal.class);
              var actualH =
                  consumption == null
                      ? BigDecimal.ZERO
                      : consumption.get("hours", BigDecimal.class);
              var actualC =
                  consumption == null
                      ? BigDecimal.ZERO
                      : consumption.get("amount", BigDecimal.class);
              boolean correct =
                  (expectedA.signum() == 0
                          ? entryId == null
                          : entry != null
                              && studentId.equals(entry.get("student_id", UUID.class))
                              && "OPENING".equals(entry.get("kind", String.class)))
                      && (expectedH.signum() == 0
                          ? consumptionId == null
                          : consumption != null
                              && Objects.equals(
                                  enrollmentId, consumption.get("enrollment_id", UUID.class))
                              && "OPENING".equals(consumption.get("source", String.class))
                              && Objects.equals(
                                  fact.get("occurred_on", LocalDate.class),
                                  consumption.get("opening_occurred_on", LocalDate.class)))
                      && actualA.compareTo(expectedA) == 0
                      && actualH.compareTo(expectedH) == 0
                      && actualC.compareTo(expectedC) == 0;
              // 没有任何批次来源的额外期初事实单独标出，不能归入本批实际值。
              var unassigned =
                  sql.fetchExists(
                      sql.selectOne()
                          .from("funds.account_entry a")
                          .where(
                              "a.student_id=? and a.kind='OPENING' and not exists (select 1 from migration.opening_fact x where x.tenant_id=a.tenant_id and x.account_entry_id=a.id)",
                              studentId));
              unassigned =
                  unassigned
                      || sql.fetchExists(
                          sql.selectOne()
                              .from("teaching.consumption c")
                              .join("enrollment.enrollment e")
                              .on("e.id=c.enrollment_id and e.tenant_id=c.tenant_id")
                              .where(
                                  "e.student_id=? and c.source='OPENING' and not exists (select 1 from migration.opening_fact x where x.tenant_id=c.tenant_id and x.consumption_id=c.id)",
                                  studentId));
              var status = !correct ? "MISMATCHED" : unassigned ? "UNATTRIBUTED" : "MATCHED";
              if ("MATCHED".equals(status)) matched++;
              else mismatched++;
              expectedAmount = expectedAmount.add(expectedA);
              actualAmount = actualAmount.add(actualA);
              expectedHours = expectedHours.add(expectedH);
              actualHours = actualHours.add(actualH);
              details.add(
                  new OpeningReconciliation.Row(
                      number,
                      money(expectedA),
                      hoursView(expectedH),
                      money(actualA),
                      hoursView(actualH),
                      money(actualA.subtract(expectedA)),
                      hoursView(actualH.subtract(expectedH)),
                      enrollmentId == null ? null : enrollmentId.toString(),
                      money(expectedC),
                      money(actualC),
                      money(actualC.subtract(expectedC)),
                      status,
                      sourceRow == null ? null : sourceRow.get("row_no", Integer.class),
                      null));
            }
            for (var row : manifestRows) {
              var number = row.get("student_no", String.class);
              var state = row.get("status", String.class);
              boolean hasFact =
                  number != null
                      && facts.stream()
                          .anyMatch(f -> number.strip().equals(f.get("student_no", String.class)));
              if ("OK".equals(state) && hasFact) continue;
              mismatched++;
              details.add(
                  new OpeningReconciliation.Row(
                      number,
                      row.get("amount", String.class),
                      row.get("hours", String.class),
                      null,
                      null,
                      null,
                      null,
                      null,
                      row.get("consumed_amount", String.class),
                      null,
                      null,
                      "OK".equals(state) ? "MISMATCHED" : state,
                      row.get("row_no", Integer.class),
                      row.get("detail", String.class)));
            }
            // V32 历史行没有目标事实映射，保留并明确标识，绝不推测为已对平。
            for (var row :
                sql.fetch(
                    "select student_no,expected_amount,expected_hours from migration.opening_row where school_id=? and batch_id=? order by student_no",
                    schoolId,
                    batch)) {
              var number = row.get("student_no", String.class);
              if (facts.stream().anyMatch(f -> number.equals(f.get("student_no", String.class))))
                continue;
              var a = row.get("expected_amount", BigDecimal.class);
              var h = row.get("expected_hours", BigDecimal.class);
              expectedAmount = expectedAmount.add(a);
              expectedHours = expectedHours.add(h);
              mismatched++;
              details.add(
                  new OpeningReconciliation.Row(
                      number,
                      money(a),
                      hoursView(h),
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      "UNVERIFIED",
                      null,
                      "历史记录缺少可靠来源"));
            }
            if (details.isEmpty()) throw new IllegalArgumentException("该批次没有可核验的期初记录");
            var completeness =
                manifest == null
                        || manifest.get("row_count", Integer.class) != manifestRows.size()
                        || facts.size()
                            != manifestRows.stream()
                                .filter(r -> "OK".equals(r.get("status", String.class)))
                                .count()
                    ? "UNVERIFIED"
                    : manifestRows.stream()
                            .anyMatch(r -> !"OK".equals(r.get("status", String.class)))
                        ? "INCOMPLETE"
                        : "COMPLETE";
            return new OpeningReconciliation(
                batch,
                completeness,
                details.size(),
                matched,
                mismatched,
                "INCOMPLETE".equals(completeness) ? null : money(expectedAmount),
                money(actualAmount),
                "INCOMPLETE".equals(completeness) ? null : hoursView(expectedHours),
                hoursView(actualHours),
                details);
          });
    }
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
