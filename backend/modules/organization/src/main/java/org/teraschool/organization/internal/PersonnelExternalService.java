package org.teraschool.organization.internal;

import java.util.*;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.organization.api.PersonnelExternalProfile.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PersonnelExternalService {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolWriteGuard guard;
  private final JsonMapper json = JsonMapper.builder().build();

  public PersonnelExternalService(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolWriteGuard guard) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.guard = guard;
  }

  private DSLContext privateSql() {
    var settings = (org.jooq.conf.Settings) sql.settings().clone();
    return org.jooq.impl.DSL.using(sql.configuration().derive(settings.withExecuteLogging(false)));
  }

  private void authorize(UUID school, Actor actor, boolean write) {
    if (!actor.schools().contains(school)
        || !actors.requireResourceInTransaction("EMPLOYEE", write).schools().contains(school))
      throw new AccessDeniedException("人员资料不可访问");
  }

  ExternalProfileView get(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要员工身份");
    var identity = actors.requireResource("PERSONNEL", false);
    return tx.execute(
        identity.tenantId(),
        () -> {
          var current = actors.requireResourceInTransaction("PERSONNEL", false);
          var row =
              privateSql()
                  .fetchOne(
                      "select e.school_id,e.version,p.profile from organization.employee e left join organization.personnel_external_profile p on p.employee_id=e.id where e.id=?",
                      id);
          if (row == null) throw new AccessDeniedException("人员资料不可访问");
          UUID school = row.get("school_id", UUID.class);
          authorize(school, current, false);
          var profile = row.get("profile", org.jooq.JSONB.class);
          return new ExternalProfileView(
              id,
              school,
              row.get("version", Long.class),
              profile == null ? null : json.readValue(profile.data(), ExternalIdentifiers.class));
        });
  }

  ExternalProfileView update(UUID id, UpdateExternalProfile input) {
    if (id == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0
        || input.data() == null) throw new IllegalArgumentException("需要员工身份、版本和通讯资料");
    json.valueToTree(input.data())
        .properties()
        .forEach(
            entry -> {
              if (!entry.getValue().isNull()
                  && (!entry.getValue().isTextual()
                      || entry.getValue().asText().length() > 200
                      || entry.getValue().asText().codePoints().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("通讯资料字段无效");
            });
    var identity = actors.requireResource("PERSONNEL", true);
    return tx.execute(
        identity.tenantId(),
        () -> {
          guard.lock(identity.tenantId());
          var current = actors.requireResourceInTransaction("PERSONNEL", true);
          var row =
              sql.fetchOne(
                  "select school_id,version from organization.employee where id=? for update", id);
          if (row == null) throw new AccessDeniedException("人员资料不可访问");
          UUID school = row.get("school_id", UUID.class);
          authorize(school, current, true);
          guard.requireActive(school);
          long version = row.get("version", Long.class);
          if (version != input.expectedVersion())
            throw new OrganizationConflict("VERSION_CONFLICT");
          privateSql()
              .execute(
                  "insert into organization.personnel_external_profile(tenant_id,school_id,employee_id,profile) values (?,?,?,?::jsonb) on conflict(employee_id) do update set profile=excluded.profile",
                  current.tenantId(),
                  school,
                  id,
                  json.writeValueAsString(input.data()));
          sql.execute(
              "update organization.employee set version=version+1 where id=? and version=?",
              id,
              version);
          sql.execute(
              "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'PERSONNEL_EXTERNAL','UPDATE',?::jsonb,?::jsonb,?)",
              current.tenantId(),
              current.accountId(),
              school,
              id,
              json.writeValueAsString(Map.of("version", version)),
              json.writeValueAsString(Map.of("version", version + 1)),
              RequestTrace.id());
          return new ExternalProfileView(id, school, version + 1, input.data());
        });
  }
}
