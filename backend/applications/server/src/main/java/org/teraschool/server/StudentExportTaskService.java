package org.teraschool.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.platform.api.TenantTransactions;

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
  private final int retentionHours;

  StudentExportTaskService(
      CurrentActor actors,
      TenantTransactions tx,
      @Qualifier("appDataSource") DataSource appSource,
      @Qualifier("identityDataSource") DataSource identitySource,
      StudentQueries students,
      Environment environment) {
    this.actors = actors;
    this.tx = tx;
    this.app = new JdbcTemplate(appSource);
    this.identity = new JdbcTemplate(identitySource);
    this.students = students;
    retentionHours =
        environment.getProperty("teraschool.export.retention-hours", Integer.class, 24);
    if (retentionHours < 1 || retentionHours > 168)
      throw new IllegalArgumentException("导出文件保留时长须在 1 到 168 小时之间");
  }

  StudentExportTask create(CreateStudentExport command) {
    if (command == null || command.schoolId() == null || command.requestId() == null)
      throw new IllegalArgumentException("学校与请求标识必填");
    var principal = SecurityContextHolder.getContext().getAuthentication();
    if (principal == null || !principal.isAuthenticated())
      throw new AccessDeniedException("需要有效身份");
    var scope = actors.requireResourceScope("STUDENT");
    requireSchool(scope, command.schoolId());
    if (!actors.requireAction("STUDENT_EXPORT").schools().contains(command.schoolId()))
      throw new AccessDeniedException("无该学校的导出权限");
    var requestHash = hash("student-csv-v1\n" + command.schoolId());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var id = UUID.randomUUID();
          app.update(
              "insert into infra.student_export_task(id,tenant_id,owner_account_id,school_id,principal_name,request_id,request_hash,status,expires_at)"
                  + " values (?,?,?,?,?,?,?,'PENDING',now()+(? * interval '1 hour')) on conflict (tenant_id,owner_account_id,request_id) do nothing",
              id,
              scope.tenantId(),
              scope.accountId(),
              command.schoolId(),
              principal.getName(),
              command.requestId(),
              requestHash,
              retentionHours);
          var row =
              app.queryForMap(
                  "select id,school_id,status,attempt_count,row_count,error_code,created_at,started_at,finished_at,request_hash"
                      + " from infra.student_export_task where tenant_id=? and owner_account_id=? and request_id=? for update",
                  scope.tenantId(),
                  scope.accountId(),
                  command.requestId());
          if (!requestHash.equals(row.get("request_hash")))
            throw new IllegalStateException("请求标识已用于不同导出参数");
          return view(row);
        });
  }

  StudentExportTask get(UUID id) {
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(scope.tenantId(), () -> view(taskRow(scope, id, false)));
  }

  byte[] download(UUID id) {
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = taskRow(scope, id, false);
          if (!"COMPLETED".equals(row.get("status"))) throw new IllegalStateException("导出尚未完成或已过期");
          var rawFields = row.get("exposed_fields");
          var exposed =
              rawFields == null || ((String) rawFields).isBlank()
                  ? Set.<String>of()
                  : Set.copyOf(Arrays.asList(((String) rawFields).split(",")));
          students.authorizeCsvDownload((UUID) row.get("school_id"), exposed);
          return (byte[]) row.get("content");
        });
  }

  StudentExportTask cancel(UUID id) {
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = taskRow(scope, id, true);
          if ("PENDING".equals(row.get("status"))) {
            app.update(
                "update infra.student_export_task set status='CANCELLED',finished_at=now() where id=? and tenant_id=? and status='PENDING'",
                id,
                scope.tenantId());
          } else if (!"CANCELLED".equals(row.get("status"))) {
            throw new IllegalStateException("仅待执行的导出任务可取消");
          }
          return view(taskRow(scope, id, false));
        });
  }

  StudentExportTask retry(UUID id) {
    var scope = actors.requireResourceScope("STUDENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = taskRow(scope, id, true);
          if (!"FAILED".equals(row.get("status"))) throw new IllegalStateException("仅失败任务可重试");
          if (!actors
              .requireActionInTransaction("STUDENT_EXPORT")
              .schools()
              .contains(row.get("school_id"))) throw new AccessDeniedException("无该学校的导出权限");
          var retries = ((Number) row.get("manual_retry_count")).intValue();
          if (retries >= MAX_MANUAL_RETRIES) throw new IllegalStateException("手动重试次数已达上限");
          app.update(
              "update infra.student_export_task set status='PENDING',attempt_count=0,manual_retry_count=manual_retry_count+1,error_code=null,finished_at=null,run_token=null,lease_until=null,next_attempt_at=now()"
                  + " where id=? and tenant_id=? and status='FAILED'",
              id,
              scope.tenantId());
          return view(taskRow(scope, id, false));
        });
  }

  private Map<String, Object> taskRow(ResourceScope scope, UUID id, boolean lock) {
    var rows =
        app.queryForList(
            "select id,school_id,status,attempt_count,row_count,error_code,created_at,started_at,finished_at,exposed_fields,content,manual_retry_count"
                + " from infra.student_export_task where id=? and tenant_id=? and owner_account_id=?"
                + (lock ? " for update" : ""),
            id,
            scope.tenantId(),
            scope.accountId());
    if (rows.size() != 1) throw new AccessDeniedException("无该导出任务访问权限");
    var row = rows.getFirst();
    requireSchool(scope, (UUID) row.get("school_id"));
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
        "update infra.student_export_task set status='EXPIRED',content=null,exposed_fields='{}',row_count=null,error_code=null"
            + " where tenant_id=? and status='COMPLETED' and expires_at<=now()",
        tenant);
    app.update(
        "update infra.student_export_task set status='FAILED',error_code='EXPORT_RETRY_EXHAUSTED',finished_at=now(),run_token=null,lease_until=null"
            + " where tenant_id=? and status='RUNNING' and lease_until<now() and attempt_count>=?",
        tenant,
        MAX_AUTOMATIC_ATTEMPTS);
    var rows =
        app.query(
            "select id,owner_account_id,school_id,principal_name,attempt_count from infra.student_export_task"
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
                    UUID.randomUUID()),
            tenant,
            MAX_AUTOMATIC_ATTEMPTS);
    if (rows.isEmpty()) return null;
    var task = rows.getFirst();
    app.update(
        "update infra.student_export_task set status='RUNNING',attempt_count=?,run_token=?,lease_until=now()+interval '15 minutes',started_at=coalesce(started_at,now()),error_code=null"
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
      StudentCsvExport export = students.exportCsvArtifact(task.schoolId());
      tx.execute(
          task.tenantId(),
          () -> {
            app.update(
                "update infra.student_export_task set status='COMPLETED',content=?,exposed_fields=?,row_count=?,finished_at=now(),expires_at=now()+(? * interval '1 hour'),lease_until=null,next_attempt_at=now()"
                    + " where id=? and tenant_id=? and run_token=? and status='RUNNING'",
                export.content().getBytes(StandardCharsets.UTF_8),
                String.join(",", export.exposedFields()),
                Math.max(0, export.content().split("\\n", -1).length - 2),
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
                "update infra.student_export_task set status=?,error_code=?,finished_at=case when ?='FAILED' then now() else null end,lease_until=null,next_attempt_at=now()+(? * interval '1 second')"
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
      UUID runToken) {}
}
