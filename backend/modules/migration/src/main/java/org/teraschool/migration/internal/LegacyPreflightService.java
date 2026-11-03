package org.teraschool.migration.internal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.finance.api.PeriodGate;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.migration.api.LegacyPreflight;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.TenantTransactions;

/** 非持久化预检，检查有限事实；结果不能作为确认、提交或来源真实性证明。 */
@Service
class LegacyPreflightService {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final SchoolAccess schools;
  private final StudentAccess students;
  private final PeriodGate periods;

  LegacyPreflightService(
      CurrentActor actors,
      TenantTransactions tx,
      SchoolAccess schools,
      StudentAccess students,
      PeriodGate periods) {
    this.actors = actors;
    this.tx = tx;
    this.schools = schools;
    this.students = students;
    this.periods = periods;
  }

  LegacyPreflight.PreflightReport preview(UUID schoolId, LegacyPreflight.PreflightRequest request) {
    var actor = actors.requireAction("MIGRATION_MANAGE");
    if (schoolId == null
        || request == null
        || request.students() == null
        || request.receipts() == null) throw new IllegalArgumentException("需要学校、来源及学员和收款清单");
    if (request.students().size() + (long) request.receipts().size() > 5000)
      throw new IllegalArgumentException("预检需要 1 至 5000 行");
    // 固定调用方列表快照，确保摘要与随后检查的是同一份输入。
    var input =
        new LegacyPreflight.PreflightRequest(
            request.sourceId(),
            request.snapshotId(),
            request.mappingVersion(),
            Collections.unmodifiableList(new ArrayList<>(request.students())),
            Collections.unmodifiableList(new ArrayList<>(request.receipts())));
    if (input.students().size() + (long) input.receipts().size() > 5000
        || input.students().isEmpty() && input.receipts().isEmpty())
      throw new IllegalArgumentException("预检需要 1 至 5000 行");
    var source = identity(input.sourceId());
    var snapshot = identity(input.snapshotId());
    var mapping = identity(input.mappingVersion());
    checkFieldSizes(input);
    var digest = digest(actor.tenantId(), schoolId, input);
    return tx.execute(
        actor.tenantId(),
        () -> {
          var verified = actors.requireActionInTransaction("MIGRATION_MANAGE");
          if (!verified.schools().contains(schoolId) || !schools.require(schoolId).active())
            throw new AccessDeniedException("无该学校的迁移权限或学校已停用");
          if (!actors
              .requireResourceScopeInTransaction("STUDENT")
              .readableSchoolIds()
              .contains(schoolId)) throw new AccessDeniedException("预检需要该学校的学员档案读取权限");
          if (!input.receipts().isEmpty()
              && !actors
                  .requireResourceScopeInTransaction("PERIOD")
                  .readableSchoolIds()
                  .contains(schoolId)) throw new AccessDeniedException("收款预检需要该学校的财务期间读取权限");
          var issues = new ArrayList<LegacyPreflight.Issue>();
          var sourceKeys = new HashSet<String>();
          var numbers = new HashSet<String>();
          for (int i = 0; i < input.students().size(); i++) {
            var row = input.students().get(i);
            int n = i + 1;
            if (row == null) {
              issue(issues, "STUDENT", n, "EMPTY_ROW");
              continue;
            }
            var key = normalized(row.legacyId());
            var number = normalized(row.studentNo());
            if (!validText(key, 64)) issue(issues, "STUDENT", n, "SOURCE_KEY_INVALID");
            else if (!sourceKeys.add(key)) issue(issues, "STUDENT", n, "DUPLICATE_SOURCE_KEY");
            if (!validText(number, 20)) issue(issues, "STUDENT", n, "STUDENT_NUMBER_INVALID");
            else if (!numbers.add(number)) issue(issues, "STUDENT", n, "DUPLICATE_STUDENT_NUMBER");
            if (!validText(normalized(row.name()), 50)) issue(issues, "STUDENT", n, "NAME_INVALID");
            if (!Set.of("MALE", "FEMALE", "UNKNOWN").contains(row.sex() == null ? "" : row.sex()))
              issue(issues, "STUDENT", n, "SEX_INVALID");
            if (row.birthday() != null
                && !row.birthday().isBlank()
                && parseDate(row.birthday()) == null)
              issue(issues, "STUDENT", n, "BIRTHDAY_INVALID");
            if (row.certCode() != null && !row.certCode().isBlank())
              issue(issues, "STUDENT", n, "CERTIFICATE_TYPE_UNCONFIRMED");
          }
          var existing = students.existingNumbers(schoolId, numbers);
          for (int i = 0; i < input.students().size(); i++) {
            var row = input.students().get(i);
            if (row != null
                && normalized(row.studentNo()) != null
                && existing.contains(normalized(row.studentNo())))
              issue(issues, "STUDENT", i + 1, "TARGET_SAME_SCHOOL_STUDENT_NUMBER_EXISTS");
          }
          sourceKeys.clear();
          var months = new HashSet<java.time.YearMonth>();
          for (var row : input.receipts()) {
            var date = row == null ? null : parseDate(row.occurredOn());
            if (date != null) months.add(java.time.YearMonth.from(date));
          }
          var lockedMonths = periods.lockedMonths(schoolId, months);
          for (int i = 0; i < input.receipts().size(); i++) {
            var row = input.receipts().get(i);
            int n = i + 1;
            if (row == null) {
              issue(issues, "RECEIPT", n, "EMPTY_ROW");
              continue;
            }
            var key = normalized(row.legacyId());
            if (!validText(key, 64)) issue(issues, "RECEIPT", n, "SOURCE_KEY_INVALID");
            else if (!sourceKeys.add(key)) issue(issues, "RECEIPT", n, "DUPLICATE_SOURCE_KEY");
            if (!numbers.contains(normalized(row.studentNo())))
              issue(issues, "RECEIPT", n, "SOURCE_STUDENT_NOT_IN_INPUT");
            if (!validAmount(row.amount())) issue(issues, "RECEIPT", n, "AMOUNT_INVALID");
            if (!Set.of("CASH", "BANK", "OTHER").contains(row.method() == null ? "" : row.method()))
              issue(issues, "RECEIPT", n, "METHOD_INVALID");
            var date = parseDate(row.occurredOn());
            if (date == null) issue(issues, "RECEIPT", n, "DATE_INVALID");
            else if (lockedMonths.contains(java.time.YearMonth.from(date)))
              issue(issues, "RECEIPT", n, "PERIOD_LOCKED");
          }
          return new LegacyPreflight.PreflightReport(
              source,
              snapshot,
              mapping,
              digest,
              input.students().size(),
              input.receipts().size(),
              false,
              issues,
              List.of(
                  "SOURCE_AUTHENTICITY",
                  "SOURCE_COMPLETENESS",
                  "SOURCE_IDENTITY_HISTORY",
                  "CROSS_BATCH_DEDUPLICATION",
                  "BUSINESS_MAPPING",
                  "TARGET_OTHER_SCHOOLS_NUMBER_CONFLICT",
                  "TARGET_SNAPSHOT_CONSISTENCY",
                  "TARGET_CHANGES_AFTER_PREVIEW"));
        });
  }

  private static String identity(String value) {
    var text = normalized(value);
    if (!validText(text, 100)) throw new IllegalArgumentException("来源、快照和映射版本均须填写且不能含控制字符");
    return text;
  }

  private static String normalized(String value) {
    return value == null ? null : value.strip();
  }

  private static boolean validText(String value, int max) {
    return value != null
        && !value.isBlank()
        && value.length() <= max
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
    try {
      var date = LocalDate.parse(text);
      return date.getYear() < 1 ? null : date;
    } catch (DateTimeParseException error) {
      return null;
    }
  }

  private static boolean validAmount(String value) {
    try {
      if (value == null || !value.strip().matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) return false;
      var amount = new BigDecimal(value.strip());
      return amount.signum() > 0 && amount.scale() <= 2 && amount.setScale(2).precision() <= 14;
    } catch (NumberFormatException | ArithmeticException invalid) {
      return false;
    }
  }

  private static void issue(List<LegacyPreflight.Issue> issues, String kind, int row, String code) {
    issues.add(new LegacyPreflight.Issue(kind, row, code));
  }

  private static void checkFieldSizes(LegacyPreflight.PreflightRequest input) {
    for (var row : input.students())
      if (row != null)
        sizes(
            row.legacyId(),
            row.studentNo(),
            row.name(),
            row.sex(),
            row.birthday(),
            row.certCode(),
            row.phone(),
            row.contactName(),
            row.contactPhone());
    for (var row : input.receipts())
      if (row != null)
        sizes(row.legacyId(), row.studentNo(), row.amount(), row.method(), row.occurredOn());
  }

  private static void sizes(String... values) {
    for (var value : values)
      if (value != null && value.length() > 200) throw new IllegalArgumentException("输入字段过长");
  }

  private static String digest(UUID tenant, UUID school, LegacyPreflight.PreflightRequest input) {
    try {
      var hash = MessageDigest.getInstance("SHA-256");
      add(
          hash,
          "teraschool-legacy-preflight-v1",
          tenant.toString(),
          school.toString(),
          input.sourceId(),
          input.snapshotId(),
          input.mappingVersion(),
          Integer.toString(input.students().size()));
      for (var row : input.students()) {
        if (row == null) add(hash, (String) null);
        else
          add(
              hash,
              "row",
              row.legacyId(),
              row.studentNo(),
              row.name(),
              row.sex(),
              row.birthday(),
              row.certCode(),
              row.phone(),
              row.contactName(),
              row.contactPhone());
      }
      add(hash, Integer.toString(input.receipts().size()));
      for (var row : input.receipts()) {
        if (row == null) add(hash, (String) null);
        else
          add(
              hash,
              "row",
              row.legacyId(),
              row.studentNo(),
              row.amount(),
              row.method(),
              row.occurredOn());
      }
      return java.util.HexFormat.of().formatHex(hash.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void add(MessageDigest hash, String... values) {
    for (var value : values) {
      var bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
      hash.update(ByteBuffer.allocate(4).putInt(bytes == null ? -1 : bytes.length).array());
      if (bytes != null) hash.update(bytes);
    }
  }
}
