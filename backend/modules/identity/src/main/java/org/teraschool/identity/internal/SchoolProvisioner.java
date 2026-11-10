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
    // 租户管理员覆盖本租户全部学校：没有学校时也算“全覆盖”，
    // 否则新开通的租户永远建不出第一所学校。
    if (actor.tenantAdmin()) return true;
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
          // 按新学校铺开机构管理员动作：只授“能看组织”会让建校人一登录就撞 403，
          // 学员、报名、教务、财务全部不可用。集合见 PermissionCatalog.SCHOOL_PROVISION_ACTIONS。
          //
          // 但委派管理员不能借建校升权：不是租户管理员时只铺“他本来就有”的动作，
          // 避免顺手拿到自己没有的退款付款、锁月解锁等敏感动作。
          var own = before.effective();
          var provisioned =
              own.tenantAdmin()
                  ? PermissionCatalog.SCHOOL_PROVISION_ACTIONS
                  : PermissionCatalog.SCHOOL_PROVISION_ACTIONS.stream()
                      .filter(code -> own.grantedActions().contains(code))
                      .toList();
          for (String action : provisioned) {
            sql.execute(
                "insert into identity.account_permission(tenant_id,account_id,action,school_id,effect) values (?,?,?,?,'ALLOW') on conflict do nothing",
                actor.tenantId(),
                actor.accountId(),
                action,
                result.id());
          }
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
