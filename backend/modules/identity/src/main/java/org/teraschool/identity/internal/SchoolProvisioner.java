package org.teraschool.identity.internal;

import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.platform.api.RequestTrace;

@Service
public class SchoolProvisioner implements SchoolProvisioning {
  private final AccountAuthorizations authorization;
  private final DSLContext sql;

  public SchoolProvisioner(AccountAuthorizations authorization, DSLContext sql) {
    this.authorization = authorization;
    this.sql = sql;
  }

  private boolean full(CurrentPermissions actor) {
    var ids = actor.manageableSchoolIds();
    if (ids.isEmpty()) return false;
    String markers = String.join(",", Collections.nCopies(ids.size(), "?"));
    return sql.fetchOne(
                "select count(*) from organization.unit where kind='SCHOOL' and id not in ("
                    + markers
                    + ")",
                ids.toArray())
            .get(0, Integer.class)
        == 0;
  }

  public boolean canCreateSchool() {
    try {
      return authorization.managed(this::full);
    } catch (AccessDeniedException denied) {
      return false;
    }
  }

  public <T> T manage(Function<Actor, T> work) {
    return authorization.managed(
        actor -> {
          if (work == null || !full(actor)) throw new AccessDeniedException("需要覆盖全部学校的权限管理员");
          return work.apply(
              new Actor(actor.accountId(), actor.tenantId(), actor.manageableSchoolIds()));
        });
  }

  public Result provision(Function<Actor, Result> createSchool) {
    return authorization.managed(
        actor -> {
          if (createSchool == null || !full(actor))
            throw new AccessDeniedException("需要覆盖全部学校的权限管理员");
          var result =
              Objects.requireNonNull(
                  createSchool.apply(
                      new Actor(actor.accountId(), actor.tenantId(), actor.manageableSchoolIds())));
          if (!result.created()) {
            if (!actor.manageableSchoolIds().contains(result.id()))
              throw new AccessDeniedException("原学校不可访问");
            return result;
          }
          var before = authorization.load(actor.accountId());
          for (String action :
              List.of(
                  "AUTHORIZATION_MANAGE",
                  "ORGANIZATION_READ",
                  "ORGANIZATION_MAINTAIN",
                  "EMPLOYEE_READ",
                  "EMPLOYEE_MAINTAIN",
                  "TEACHER_READ",
                  "TEACHER_MAINTAIN",
                  "ROOM_READ",
                  "ROOM_MAINTAIN"))
            sql.execute(
                "insert into identity.account_permission(tenant_id,account_id,action,school_id,effect) values (?,?,?,?,'ALLOW') on conflict do nothing",
                actor.tenantId(),
                actor.accountId(),
                action,
                result.id());
          sql.execute(
              "update identity.account set authorization_version=authorization_version+1 where id=?",
              actor.accountId());
          if (!authorization.hasFullAdministrator())
            throw new AuthorizationConflict("LAST_AUTHORIZATION_ADMINISTRATOR");
          var after = authorization.load(actor.accountId());
          sql.execute(
              "insert into audit.authorization_event(tenant_id,actor_id,target_account_id,previous_version,next_version,before_rules,after_rules,trace_id) values (?,?,?,?,?,?::jsonb,?::jsonb,?)",
              actor.tenantId(),
              actor.accountId(),
              actor.accountId(),
              before.version(),
              after.version(),
              authorization.auditRules(before),
              authorization.auditRules(after),
              RequestTrace.id());
          return result;
        });
  }
}
