package org.teraschool.organization.internal;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.SchoolProvisioning;
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.BusinessAudit;

/** 学校和初始化授权同一个事务；重放仍检查当前全机构权限。 */
@Service
public class SchoolMaintenance {
  private final SchoolProvisioning provisioner;
  private final DSLContext sql;
  private final BusinessAudit audit;

  public SchoolMaintenance(SchoolProvisioning provisioner, DSLContext sql, BusinessAudit audit) {
    this.provisioner = provisioner;
    this.sql = sql;
    this.audit = audit;
  }

  public boolean canCreate() {
    return provisioner.canCreateSchool();
  }

  public SchoolManagement.SchoolView getSchool(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要学校身份");
    return provisioner.manage(
        actor -> {
          var row =
              sql.fetchOne(
                  "select id,name,active,version from organization.unit where id=? and kind='SCHOOL'",
                  id);
          if (row == null || !actor.schools().contains(id))
            throw new org.springframework.security.access.AccessDeniedException("学校不可访问");
          return new SchoolManagement.SchoolView(
              id,
              row.get("name", String.class),
              row.get("active", Boolean.class),
              row.get("version", Long.class));
        });
  }

  public SchoolManagement.SchoolView updateSchool(UUID id, SchoolManagement.UpdateSchool input) {
    if (id == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0
        || input.active() == null
        || input.name() == null
        || input.name().isBlank()
        || input.name().length() > 100
        || input.name().codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("需要有效学校名称、版本和状态");
    String name = input.name().strip();
    return provisioner.manage(
        actor -> {
          var row =
              sql.fetchOne(
                  "select name,active,version from organization.unit where id=? and kind='SCHOOL' for update",
                  id);
          if (row == null || !actor.schools().contains(id))
            throw new org.springframework.security.access.AccessDeniedException("学校不可访问");
          long version = row.get("version", Long.class);
          if (version != input.expectedVersion())
            throw new OrganizationConflict("VERSION_CONFLICT");
          sql.execute(
              "update organization.unit set name=?,active=?,version=version+1 where id=? and version=?",
              name,
              input.active(),
              id,
              version);
          audit.append(
              actor.tenantId(),
              actor.accountId(),
              "SCHOOL_UPDATED",
              id,
              id,
              row.get("name", String.class),
              name);
          var json = tools.jackson.databind.json.JsonMapper.builder().build();
          sql.execute(
              "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'SCHOOL','UPDATE',?::jsonb,?::jsonb,?)",
              actor.tenantId(),
              actor.accountId(),
              id,
              id,
              json.writeValueAsString(
                  java.util.Map.of(
                      "name",
                      row.get("name", String.class),
                      "active",
                      row.get("active", Boolean.class),
                      "version",
                      version)),
              json.writeValueAsString(
                  java.util.Map.of("name", name, "active", input.active(), "version", version + 1)),
              org.teraschool.platform.api.RequestTrace.id());
          return new SchoolManagement.SchoolView(id, name, input.active(), version + 1);
        });
  }

  public OrganizationUnit createSchool(SchoolManagement.CreateSchool input) {
    if (input == null
        || input.requestId() == null
        || input.name() == null
        || input.name().isBlank()
        || input.name().length() > 100
        || input.name().codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("需要请求身份和有效学校名称");
    String name = input.name().strip();
    var result =
        provisioner.provision(
            actor -> {
              var previous =
                  sql.fetchOne(
                      "select school_id,name from organization.school_creation_request where tenant_id=? and actor_id=? and request_id=?",
                      actor.tenantId(),
                      actor.accountId(),
                      input.requestId());
              if (previous != null) {
                if (!name.equals(previous.get("name", String.class)))
                  throw new OrganizationConflict("IDEMPOTENCY_MISMATCH");
                return new SchoolProvisioning.Result(
                    previous.get("school_id", UUID.class), name, 0, false);
              }
              UUID id = sql.fetchOne("select uuidv7()", new Object[] {}).get(0, UUID.class);
              sql.execute(
                  "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
                  id,
                  actor.tenantId(),
                  id,
                  name);
              sql.execute(
                  "insert into organization.school_creation_request(tenant_id,actor_id,request_id,school_id,name) values (?,?,?,?,?)",
                  actor.tenantId(),
                  actor.accountId(),
                  input.requestId(),
                  id,
                  name);
              audit.append(
                  actor.tenantId(), actor.accountId(), "SCHOOL_CREATED", id, id, null, name);
              return new SchoolProvisioning.Result(id, name, 0, true);
            });
    return new OrganizationUnit(
        result.id(), result.id(), "SCHOOL", result.name(), result.version());
  }
}
