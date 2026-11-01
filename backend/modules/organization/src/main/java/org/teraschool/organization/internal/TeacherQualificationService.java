package org.teraschool.organization.internal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.organization.api.TeacherQualifications.*;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TeacherQualificationService {
  private final CurrentActor actors;
  private final TenantTransactions transactions;
  private final DSLContext sql;
  private final JsonMapper json = JsonMapper.builder().build();
  private final SchoolWriteGuard schoolGuard;

  public TeacherQualificationService(
      CurrentActor actors,
      TenantTransactions transactions,
      DSLContext sql,
      SchoolWriteGuard schoolGuard) {
    this.actors = actors;
    this.transactions = transactions;
    this.sql = sql;
    this.schoolGuard = schoolGuard;
  }

  private DSLContext privateSql() {
    var settings = (org.jooq.conf.Settings) sql.settings().clone();
    return org.jooq.impl.DSL.using(sql.configuration().derive(settings.withExecuteLogging(false)));
  }

  public QualificationView get(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要教师身份");
    var actor = actors.requireResource("TEACHER", false);
    return transactions.execute(
        actor.tenantId(),
        () -> {
          var current = actors.requireResourceInTransaction("TEACHER", false);
          var row =
              privateSql()
                  .fetchOne(
                      "select school_id,qualification,version from organization.teacher where id=?",
                      id);
          if (row == null || !current.schools().contains(row.get("school_id", UUID.class)))
            throw new AccessDeniedException("教师不可访问");
          return new QualificationView(
              id,
              row.get("school_id", UUID.class),
              row.get("qualification", String.class),
              row.get("version", Long.class));
        });
  }

  public QualificationView update(UUID id, UpdateQualification input) {
    if (id == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0
        || input.qualification() == null
        || input.qualification().length() > 10000)
      throw new IllegalArgumentException("需要有效版本和不超过10000字的资质简介");
    var actor = actors.requireResource("TEACHER", true);
    return transactions.execute(
        actor.tenantId(),
        () -> {
          if (!"read committed"
              .equals(sql.fetchOne("show transaction_isolation").get(0, String.class)))
            throw new IllegalStateException("资源维护要求READ COMMITTED事务");
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              "authorization:" + actor.tenantId());
          var current = actors.requireResourceInTransaction("TEACHER", true);
          var row =
              privateSql()
                  .fetchOne(
                      "select school_id,qualification,version from organization.teacher where id=? for update",
                      id);
          if (row == null || !current.schools().contains(row.get("school_id", UUID.class)))
            throw new AccessDeniedException("教师不可维护");
          schoolGuard.requireActive(row.get("school_id", UUID.class));
          long previous = row.get("version", Long.class);
          if (previous != input.expectedVersion())
            throw new OrganizationConflict("VERSION_CONFLICT");
          privateSql()
              .execute(
                  "update organization.teacher set qualification=?,version=version+1 where id=? and version=?",
                  input.qualification(),
                  id,
                  previous);
          // 审计只记录字段变更与版本，不把自由文本复制到日志或审计。
          var before = json.writeValueAsString(java.util.Map.of("version", previous));
          var after =
              json.writeValueAsString(
                  java.util.Map.of("version", previous + 1, "field", "qualification"));
          sql.execute(
              "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'TEACHER','UPDATE',?::jsonb,?::jsonb,?)",
              current.tenantId(),
              current.accountId(),
              row.get("school_id", UUID.class),
              id,
              before,
              after,
              RequestTrace.id());
          return new QualificationView(
              id, row.get("school_id", UUID.class), input.qualification(), previous + 1);
        });
  }
}
