package org.teraschool.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentCsvExport;
import org.teraschool.archive.api.StudentQueries;
import org.teraschool.enrollment.api.EnrollmentQueries;
import org.teraschool.funds.api.FundsQueries;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.TeachingQueries;

/** 持久任务控制面。任务事实保存在租户隔离表中，调度表不承载业务状态或结果。 */
@Service
class StudentExportTaskService {
  private static final int MAX_AUTOMATIC_ATTEMPTS = 3;
  private static final int MAX_MANUAL_RETRIES = 3;
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final JdbcTemplate app;
  private final JdbcTemplate identity;
  private final StudentQueries students;
  private final EnrollmentQueries enrollments;
  private final FundsQueries funds;
  private final TeachingQueries teaching;
  private final int retentionHours;
  private final String pdfChromiumPath;

  StudentExportTaskService(
      CurrentActor actors,
      TenantTransactions tx,
      @Qualifier("appDataSource") DataSource appSource,
      @Qualifier("identityDataSource") DataSource identitySource,
      StudentQueries students,
      EnrollmentQueries enrollments,
      FundsQueries funds,
      TeachingQueries teaching,
      Environment environment) {
    this.actors = actors;
    this.tx = tx;
    this.app = new JdbcTemplate(appSource);
    this.identity = new JdbcTemplate(identitySource);
    this.students = students;
    this.enrollments = enrollments;
    this.funds = funds;
    this.teaching = teaching;
    pdfChromiumPath = environment.getProperty("teraschool.export.pdf.chromium-path");
    retentionHours =
        environment.getProperty("teraschool.export.retention-hours", Integer.class, 24);
    if (retentionHours < 1 || retentionHours > 168)
      throw new IllegalArgumentException("导出文件保留时长须在 1 到 168 小时之间");
  }

  StudentExportTask create(CreateStudentExport command) {
    if (command == null) throw new IllegalArgumentException("导出参数必填");
    return create(new CreateExport("STUDENT", command.schoolId(), command.requestId(), null, null));
  }

  StudentExportTask create(CreateExport command) {
    if (command == null || command.schoolId() == null || command.requestId() == null)
      throw new IllegalArgumentException("学校与请求标识必填");
    String type = normalizeType(command.exportType());
    String format = normalizeFormat(command.format());
    var dates = normalizeDates(type, command.from(), command.to());
    var principal = SecurityContextHolder.getContext().getAuthentication();
    if (principal == null || !principal.isAuthenticated())
      throw new AccessDeniedException("需要有效身份");
    var scope = actors.requireResourceScope(resource(type));
    requireSchool(scope, command.schoolId());
    authorize(type, command.schoolId(), false);
    var requestHash =
        "STUDENT".equals(type) && "CSV".equals(format)
            ? hash("student-csv-v1\n" + command.schoolId())
            : "CSV".equals(format)
                ? hash(
                    type
                        + "-csv-v1\n"
                        + command.schoolId()
                        + "\n"
                        + dates.from()
                        + "\n"
                        + dates.to())
                : hash(
                    type
                        + "-"
                        + format.toLowerCase(Locale.ROOT)
                        + "-v1\n"
                        + command.schoolId()
                        + "\n"
                        + dates.from()
                        + "\n"
                        + dates.to());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var id = UUID.randomUUID();
          app.update(
              "insert into infra.export_task(id,tenant_id,owner_account_id,school_id,principal_name,request_id,request_hash,status,expires_at,export_type,date_from,date_to,export_format)"
                  + " values (?,?,?,?,?,?,?,'PENDING',now()+(? * interval '1 hour'),?,?,?,?) on conflict (tenant_id,owner_account_id,request_id) do nothing",
              id,
              scope.tenantId(),
              scope.accountId(),
              command.schoolId(),
              principal.getName(),
              command.requestId(),
              requestHash,
              retentionHours,
              type,
              dates.from(),
              dates.to(),
              format);
          var row =
              app.queryForMap(
                  "select id,school_id,export_type,export_format,status,attempt_count,row_count,error_code,created_at,started_at,finished_at,request_hash"
                      + " from infra.export_task where tenant_id=? and owner_account_id=? and request_id=? for update",
                  scope.tenantId(),
                  scope.accountId(),
                  command.requestId());
          if (!requestHash.equals(row.get("request_hash")))
            throw new IllegalStateException("请求标识已用于不同导出参数");
          return view(row);
        });
  }

  StudentExportTask get(UUID id) {
    var scope = findScope(id);
    return tx.execute(scope.tenantId(), () -> view(taskRow(scope, id, false)));
  }

  List<StudentExportTask> list(String exportType, UUID schoolId) {
    String type = normalizeType(exportType);
    var scope = actors.requireResourceScope(resource(type));
    requireSchool(scope, schoolId);
    authorize(type, schoolId, false);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction(resource(type));
          requireSchool(verified, schoolId);
          authorize(type, schoolId, true);
          return app.query(
              "select id,school_id,export_type,export_format,status,attempt_count,row_count,error_code,created_at,started_at,finished_at"
                  + " from infra.export_task where tenant_id=? and owner_account_id=? and school_id=? and export_type=?"
                  + " order by created_at desc limit 20",
              (row, index) ->
                  new StudentExportTask(
                      row.getObject("id", UUID.class),
                      row.getObject("school_id", UUID.class),
                      row.getString("export_type"),
                      row.getString("export_format"),
                      row.getString("status"),
                      row.getInt("attempt_count"),
                      row.getObject("row_count") == null
                          ? null
                          : ((Number) row.getObject("row_count")).intValue(),
                      row.getString("error_code"),
                      instant(row.getTimestamp("created_at")),
                      instant(row.getTimestamp("started_at")),
                      instant(row.getTimestamp("finished_at"))),
              scope.tenantId(),
              scope.accountId(),
              schoolId,
              type);
        });
  }

  byte[] download(UUID id) {
    var scope = findScope(id);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = taskRow(scope, id, false);
          if (!"COMPLETED".equals(row.get("status"))) throw new IllegalStateException("导出尚未完成或已过期");
          String type = (String) row.get("export_type");
          var dataScope = actors.requireResourceScopeInTransaction(resource(type));
          requireSchool(dataScope, (UUID) row.get("school_id"));
          authorize(type, (UUID) row.get("school_id"), true);
          var rawFields = row.get("exposed_fields");
          var exposed =
              rawFields == null || ((String) rawFields).isBlank()
                  ? Set.<String>of()
                  : Set.copyOf(Arrays.asList(((String) rawFields).split(",")));
          if ("STUDENT".equals(type))
            students.authorizeCsvDownload((UUID) row.get("school_id"), exposed);
          return (byte[]) row.get("content");
        });
  }

  StudentExportTask cancel(UUID id) {
    var scope = findScope(id);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = taskRow(scope, id, true);
          if ("PENDING".equals(row.get("status"))) {
            app.update(
                "update infra.export_task set status='CANCELLED',finished_at=now() where id=? and tenant_id=? and status='PENDING'",
                id,
                scope.tenantId());
          } else if (!"CANCELLED".equals(row.get("status"))) {
            throw new IllegalStateException("仅待执行的导出任务可取消");
          }
          return view(taskRow(scope, id, false));
        });
  }

  StudentExportTask retry(UUID id) {
    var scope = findScope(id);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = taskRow(scope, id, true);
          if (!"FAILED".equals(row.get("status"))) throw new IllegalStateException("仅失败任务可重试");
          String type = (String) row.get("export_type");
          var dataScope = actors.requireResourceScopeInTransaction(resource(type));
          requireSchool(dataScope, (UUID) row.get("school_id"));
          authorize(type, (UUID) row.get("school_id"), true);
          var retries = ((Number) row.get("manual_retry_count")).intValue();
          if (retries >= MAX_MANUAL_RETRIES) throw new IllegalStateException("手动重试次数已达上限");
          app.update(
              "update infra.export_task set status='PENDING',attempt_count=0,manual_retry_count=manual_retry_count+1,error_code=null,finished_at=null,run_token=null,lease_until=null,next_attempt_at=now()"
                  + " where id=? and tenant_id=? and status='FAILED'",
              id,
              scope.tenantId());
          return view(taskRow(scope, id, false));
        });
  }

  private Map<String, Object> taskRow(ResourceScope scope, UUID id, boolean lock) {
    var rows =
        app.queryForList(
            "select id,school_id,export_type,export_format,date_from,date_to,status,attempt_count,row_count,error_code,created_at,started_at,finished_at,exposed_fields,content,manual_retry_count"
                + " from infra.export_task where id=? and tenant_id=? and owner_account_id=?"
                + (lock ? " for update" : ""),
            id,
            scope.tenantId(),
            scope.accountId());
    if (rows.size() != 1) throw new AccessDeniedException("无该导出任务访问权限");
    var row = rows.getFirst();
    requireSchool(
        actors.requireResourceScopeInTransaction(resource((String) row.get("export_type"))),
        (UUID) row.get("school_id"));
    return row;
  }

  private static void requireSchool(ResourceScope scope, UUID schoolId) {
    if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的学员读取权限");
  }

  private static StudentExportTask view(Map<String, Object> row) {
    return new StudentExportTask(
        (UUID) row.get("id"),
        (UUID) row.get("school_id"),
        (String) row.get("export_type"),
        (String) row.get("export_format"),
        (String) row.get("status"),
        ((Number) row.get("attempt_count")).intValue(),
        row.get("row_count") == null ? null : ((Number) row.get("row_count")).intValue(),
        (String) row.get("error_code"),
        instant(row.get("created_at")),
        instant(row.get("started_at")),
        instant(row.get("finished_at")));
  }

  private static Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof Instant instant) return instant;
    return null;
  }

  void dispatchPending() {
    var tenants = identity.queryForList("select id from identity.tenant where active", UUID.class);
    for (var tenant : tenants) {
      for (int i = 0; i < 10; i++) {
        var task = tx.execute(tenant, () -> claim(tenant));
        if (task == null) break;
        execute(task);
      }
    }
  }

  private Claimed claim(UUID tenant) {
    app.update(
        "update infra.export_task set status='EXPIRED',content=null,exposed_fields='',row_count=null,error_code=null"
            + " where tenant_id=? and status='COMPLETED' and expires_at<=now()",
        tenant);
    app.update(
        "update infra.export_task set status='FAILED',error_code='EXPORT_RETRY_EXHAUSTED',finished_at=now(),run_token=null,lease_until=null"
            + " where tenant_id=? and status='RUNNING' and lease_until<now() and attempt_count>=?",
        tenant,
        MAX_AUTOMATIC_ATTEMPTS);
    var rows =
        app.query(
            "select id,owner_account_id,school_id,principal_name,attempt_count,export_type,export_format,date_from,date_to from infra.export_task"
                + " where tenant_id=? and ((status='PENDING' and next_attempt_at<=now()) or (status='RUNNING' and lease_until<now())) and attempt_count<?"
                + " order by created_at limit 1 for update skip locked",
            (r, n) ->
                new Claimed(
                    r.getObject(1, UUID.class),
                    tenant,
                    r.getObject(2, UUID.class),
                    r.getObject(3, UUID.class),
                    r.getString(4),
                    r.getInt(5) + 1,
                    r.getString(6),
                    r.getString(7),
                    r.getString(8),
                    r.getString(9),
                    UUID.randomUUID()),
            tenant,
            MAX_AUTOMATIC_ATTEMPTS);
    if (rows.isEmpty()) return null;
    var task = rows.getFirst();
    app.update(
        "update infra.export_task set status='RUNNING',attempt_count=?,run_token=?,lease_until=now()+interval '15 minutes',started_at=coalesce(started_at,now()),error_code=null"
            + " where id=? and tenant_id=?",
        task.attempt(),
        task.runToken(),
        task.id(),
        tenant);
    return task;
  }

  private void execute(Claimed task) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            task.principalName(),
            "",
            List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                    "AUTHENTICATED"))));
    SecurityContextHolder.setContext(context);
    try {
      String exposedFields;
      int rowCount;
      String csv;
      if ("STUDENT".equals(task.exportType())) {
        StudentCsvExport export = students.exportCsvArtifact(task.schoolId());
        csv = export.content();
        exposedFields = String.join(",", export.exposedFields());
      } else {
        csv =
            switch (task.exportType()) {
              case "ENROLLMENT" -> enrollments.exportCsv(task.schoolId());
              case "RECEIPTS" -> funds.exportReceiptsCsv(task.schoolId(), task.from(), task.to());
              case "LESSONS" -> teaching.exportLessonsCsv(task.schoolId(), task.from(), task.to());
              default -> throw new IllegalStateException("未知导出类型");
            };
        exposedFields = "";
      }
      rowCount = ExportWorkbook.dataRowCount(csv);
      byte[] content =
          switch (task.format()) {
            case "XLSX" -> ExportWorkbook.fromCsv(csv);
            case "PDF" -> ExportPdf.fromCsv(csv, pdfChromiumPath);
            default -> csv.getBytes(StandardCharsets.UTF_8);
          };
      tx.execute(
          task.tenantId(),
          () -> {
            app.update(
                "update infra.export_task set status='COMPLETED',content=?,exposed_fields=?,row_count=?,finished_at=now(),expires_at=now()+(? * interval '1 hour'),lease_until=null,next_attempt_at=now()"
                    + " where id=? and tenant_id=? and run_token=? and status='RUNNING'",
                content,
                exposedFields,
                rowCount,
                retentionHours,
                task.id(),
                task.tenantId(),
                task.runToken());
            return null;
          });
    } catch (Exception failure) {
      tx.execute(
          task.tenantId(),
          () -> {
            boolean terminal =
                failure instanceof AccessDeniedException
                    || failure instanceof org.teraschool.platform.api.ExportLimitExceeded
                    || failure instanceof IllegalArgumentException;
            boolean exhausted = task.attempt() >= MAX_AUTOMATIC_ATTEMPTS;
            String state = terminal || exhausted ? "FAILED" : "PENDING";
            int backoffSeconds = Math.min(60, 3 << Math.max(0, task.attempt() - 1));
            app.update(
                "update infra.export_task set status=?,error_code=?,finished_at=case when ?='FAILED' then now() else null end,lease_until=null,next_attempt_at=now()+(? * interval '1 second')"
                    + " where id=? and tenant_id=? and run_token=? and status='RUNNING'",
                state,
                state.equals("FAILED")
                    ? (failure instanceof AccessDeniedException
                        ? "EXPORT_ACCESS_REVOKED"
                        : failure instanceof org.teraschool.platform.api.ExportLimitExceeded
                            ? "EXPORT_LIMIT_EXCEEDED"
                            : "EXPORT_EXECUTION_FAILED")
                    : null,
                state,
                backoffSeconds,
                task.id(),
                task.tenantId(),
                task.runToken());
            return null;
          });
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private record Claimed(
      UUID id,
      UUID tenantId,
      UUID ownerId,
      UUID schoolId,
      String principalName,
      int attempt,
      String exportType,
      String format,
      String from,
      String to,
      UUID runToken) {}

  private record DateRange(String from, String to) {}

  private void authorize(String type, UUID schoolId, boolean inTransaction) {
    if ("STUDENT".equals(type)
        && !(inTransaction
                ? actors.requireActionInTransaction("STUDENT_EXPORT").schools()
                : actors.requireAction("STUDENT_EXPORT").schools())
            .contains(schoolId)) throw new AccessDeniedException("无该学校的导出权限");
  }

  private static String normalizeType(String type) {
    if (type == null) throw new IllegalArgumentException("导出类型必填");
    String value = type.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("STUDENT", "ENROLLMENT", "RECEIPTS", "LESSONS").contains(value))
      throw new IllegalArgumentException("不支持的导出类型");
    return value;
  }

  private static String normalizeFormat(String format) {
    if (format == null || format.isBlank()) return "CSV";
    String value = format.trim().toUpperCase(Locale.ROOT);
    if (!Set.of("CSV", "XLSX", "PDF").contains(value))
      throw new IllegalArgumentException("不支持的导出格式");
    return value;
  }

  private static DateRange normalizeDates(String type, String from, String to) {
    LocalDate today = LocalDate.now();
    if ("ENROLLMENT".equals(type)) {
      if ((from != null && !from.isBlank()) || (to != null && !to.isBlank()))
        throw new IllegalArgumentException("报名导出暂不支持日期筛选");
      return new DateRange(null, null);
    }
    LocalDate start =
        from == null || from.isBlank()
            ? ("RECEIPTS".equals(type) ? today.minusDays(30) : today)
            : LocalDate.parse(from);
    LocalDate end =
        to == null || to.isBlank()
            ? ("RECEIPTS".equals(type) ? today : start.plusDays(60))
            : LocalDate.parse(to);
    if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    return new DateRange(start.toString(), end.toString());
  }

  private static String resource(String type) {
    return switch (normalizeType(type)) {
      case "STUDENT" -> "STUDENT";
      case "ENROLLMENT" -> "ENROLLMENT";
      case "RECEIPTS" -> "FUNDS";
      case "LESSONS" -> "TEACHING";
      default -> throw new IllegalArgumentException("不支持的导出类型");
    };
  }

  private ResourceScope findScope(UUID id) {
    for (String kind : List.of("STUDENT", "ENROLLMENT", "FUNDS", "TEACHING")) {
      try {
        var scope = actors.requireResourceScope(kind);
        var exists =
            tx.execute(
                scope.tenantId(),
                () -> {
                  var rows =
                      app.queryForList(
                          "select export_type,school_id from infra.export_task where id=? and tenant_id=? and owner_account_id=?",
                          id,
                          scope.tenantId(),
                          scope.accountId());
                  return rows.size() == 1
                      && resource((String) rows.getFirst().get("export_type")).equals(kind)
                      && scope.readableSchoolIds().contains(rows.getFirst().get("school_id"));
                });
        if (exists) return scope;
      } catch (AccessDeniedException ignored) {
        // Continue trying other resource families; task ownership and its own resource scope decide
        // access.
      }
    }
    throw new AccessDeniedException("无该导出任务访问权限");
  }
}
