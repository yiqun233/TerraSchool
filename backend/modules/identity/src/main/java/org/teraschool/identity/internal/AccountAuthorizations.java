package org.teraschool.identity.internal;

import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.AuthorizationManagement.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 授权集合整体替换；租户事务锁后重新检查操作者，防止排队时已撤权仍提交。 */
@Service
public class AccountAuthorizations {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final JsonMapper json = JsonMapper.builder().build();

  public AccountAuthorizations(CurrentActor actors, TenantTransactions tx, DSLContext sql) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
  }

  private CurrentPermissions current(UUID account) {
    var rows = sql.fetch(PermissionRules.CURRENT, account);
    if (rows.isEmpty()) throw new AccessDeniedException("账号或机构不可用");
    if (!Objects.equals(
        rows.getFirst().get("credential_version", Long.class),
        SessionPrincipal.current().version())) throw new AccessDeniedException("会话已撤销");
    return PermissionRules.calculate(
        account,
        rows.getFirst().get("tenant_id", UUID.class),
        true,
        rows.map(
            r ->
                new PermissionRules.Entry(
                    r.get("action", String.class),
                    r.get("school_id", UUID.class),
                    r.get("effect", String.class))),
        rows.getFirst().get("tenant_admin", Boolean.class));
  }

  <T> T managed(Function<CurrentPermissions, T> work) {
    var identity = actors.permissions();
    return tx.execute(
        identity.tenantId(),
        () -> {
          if (!"read committed"
              .equals(sql.fetchOne("show transaction_isolation").get(0, String.class)))
            throw new IllegalStateException("授权管理要求READ COMMITTED事务");
          // 所有账号授权变化共用机构锁；后续角色编辑/账号停用/学校开通必须加入同一协议。
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              "authorization:" + identity.tenantId());
          var actor = current(identity.accountId());
          // 租户管理员在还没有学校时也要能建第一所学校：学校范围为空不构成拒绝理由。
          if (!actor.canManageAuthorizations()
              || (actor.manageableSchoolIds().isEmpty() && !actor.tenantAdmin()))
            throw new AccessDeniedException("无授权管理范围");
          return work.apply(actor);
        });
  }

  private String search(String input) {
    if (input == null || input.length() > 100) throw new IllegalArgumentException("检索最长100字符");
    return "%" + input.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  private String slots(Set<UUID> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }

  public List<Option> schools(String search) {
    return managed(
        a -> {
          var args = new ArrayList<Object>(a.manageableSchoolIds());
          args.add(search(search));
          return sql.fetch(
                  "select id,name from organization.unit where kind='SCHOOL' and id in ("
                      + slots(a.manageableSchoolIds())
                      + ") and name ilike ? escape '!' order by name,id limit 20",
                  args.toArray())
              .map(r -> new Option(r.get("id", UUID.class), r.get("name", String.class)));
        });
  }

  public List<RoleOption> roles(String search) {
    return managed(
        a ->
            sql.fetch(
                    "select id,name,version from identity.permission_role where active and name ilike ? escape '!' order by name,id limit 20",
                    search(search))
                .map(
                    r ->
                        new RoleOption(
                            r.get("id", UUID.class),
                            r.get("name", String.class),
                            r.get("version", Long.class))));
  }

  public List<Option> accounts(String search) {
    return managed(
        a -> {
          var args = new ArrayList<Object>();
          args.add(search(search));
          args.addAll(a.manageableSchoolIds());
          args.addAll(a.manageableSchoolIds());
          String scope = slots(a.manageableSchoolIds());
          return sql.fetch(
                  """
        select a.id,a.login from identity.account a where a.login ilike ? escape '!'
          and not exists(select 1 from identity.account_permission p where p.account_id=a.id and p.school_id is not null and p.school_id not in (
        """
                      + scope
                      + """
        )) and not exists(select 1 from identity.account_role m where m.account_id=a.id and m.school_id not in (
        """
                      + scope
                      + ")) order by a.login,a.id limit 20",
                  args.toArray())
              .map(r -> new Option(r.get("id", UUID.class), r.get("login", String.class)));
        });
  }

  AccountAuthorization load(UUID id) {
    if (id == null) throw new IllegalArgumentException("缺少账号");
    var row =
        sql.fetchOne(
            "select id,tenant_id,login,active,authorization_version,tenant_admin from identity.account where id=?",
            id);
    if (row == null) throw new AccessDeniedException("账号不可管理");
    var rules =
        sql.fetch(
                "select action,school_id,effect from identity.account_permission where account_id=? order by action,school_id nulls first,effect",
                id)
            .map(
                r ->
                    new Rule(
                        r.get("action", String.class),
                        r.get("school_id", UUID.class),
                        r.get("effect", String.class)));
    var assignments =
        sql.fetch(
                "select m.role_id,m.school_id,r.version from identity.account_role m join identity.permission_role r on r.id=m.role_id and r.tenant_id=m.tenant_id where m.account_id=? order by m.role_id,m.school_id",
                id)
            .map(
                r ->
                    new Assignment(
                        r.get("role_id", UUID.class),
                        r.get("school_id", UUID.class),
                        r.get("version", Long.class)));
    var expanded =
        sql.fetch(
                "select p.action,p.school_id,p.effect from ("
                    + PermissionRules.EXPANDED
                    + ") p where p.account_id=?",
                id)
            .map(
                r ->
                    new PermissionRules.Entry(
                        r.get("action", String.class),
                        r.get("school_id", UUID.class),
                        r.get("effect", String.class)));
    var schoolIds = new HashSet<UUID>();
    rules.stream().map(Rule::schoolId).filter(Objects::nonNull).forEach(schoolIds::add);
    assignments.stream().map(Assignment::schoolId).forEach(schoolIds::add);
    var roleIds =
        assignments.stream().map(Assignment::roleId).collect(java.util.stream.Collectors.toSet());
    List<Option> schoolLabels =
        schoolIds.isEmpty()
            ? List.of()
            : sql.fetch(
                    "select id,name from organization.unit where id in (" + slots(schoolIds) + ")",
                    schoolIds.toArray())
                .map(r -> new Option(r.get("id", UUID.class), r.get("name", String.class)));
    List<RoleOption> roleLabels =
        roleIds.isEmpty()
            ? List.of()
            : sql.fetch(
                    "select id,version,case when active then name else name||'（停用）' end as name from identity.permission_role where id in ("
                        + slots(roleIds)
                        + ")",
                    roleIds.toArray())
                .map(
                    r ->
                        new RoleOption(
                            r.get("id", UUID.class),
                            r.get("name", String.class),
                            r.get("version", Long.class)));
    return new AccountAuthorization(
        id,
        row.get("login", String.class),
        row.get("active", Boolean.class),
        row.get("authorization_version", Long.class),
        rules,
        assignments,
        PermissionRules.calculate(
            id,
            row.get("tenant_id", UUID.class),
            row.get("active", Boolean.class),
            expanded,
            row.get("tenant_admin", Boolean.class)),
        schoolLabels,
        roleLabels);
  }

  void boundary(CurrentPermissions actor, AccountAuthorization target) {
    var scope = actor.manageableSchoolIds();
    var p = target.effective();
    // 同时检查原始配置和最终权限：即使DENY暂时遮住外校ALLOW，也不交给局部管理员改动。
    if (target.rules().stream().anyMatch(r -> r.schoolId() != null && !scope.contains(r.schoolId()))
        || target.assignments().stream().anyMatch(r -> !scope.contains(r.schoolId()))
        || !scope.containsAll(p.readableSchoolIds())
        || !scope.containsAll(p.maintainableSchoolIds())
        || !scope.containsAll(p.manageableSchoolIds())) throw new AccessDeniedException("超出委派管理范围");
  }

  public AccountAuthorization get(UUID id) {
    return managed(
        a -> {
          var result = load(id);
          boundary(a, result);
          return result;
        });
  }

  private void validate(Replacement input) {
    if (input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0
        || input.rules() == null
        || input.assignments() == null
        || input.rules().size() > 200
        || input.assignments().size() > 200) throw new IllegalArgumentException("需要版本及有界的完整授权集合");
    for (var r : input.rules())
      if (r == null
          || !PermissionRules.ACTIONS.contains(Objects.toString(r.action(), ""))
          || !Set.of("ALLOW", "DENY").contains(Objects.toString(r.effect(), "")))
        throw new IllegalArgumentException("无效权限规则");
    for (var m : input.assignments())
      if (m == null
          || m.roleId() == null
          || m.schoolId() == null
          || m.expectedRoleVersion() == null
          || m.expectedRoleVersion() < 0) throw new IllegalArgumentException("角色必须指定学校及模板版本");
    if (new HashSet<>(input.rules()).size() != input.rules().size()
        || input.assignments().stream()
                .map(m -> Map.entry(m.roleId(), m.schoolId()))
                .distinct()
                .count()
            != input.assignments().size()) throw new IllegalArgumentException("不接受重复规则");
  }

  JSONB auditRules(AccountAuthorization value) {
    return JSONB.valueOf(
        json.writeValueAsString(
            Map.of("rules", value.rules(), "assignments", value.assignments())));
  }

  boolean hasFullAdministrator() {
    // 用数据库集合运算检查所有学校仍被至少一位可用管理员覆盖，不逐账号循环查询。
    return sql.fetchOne(
            """
      with rules as (
      """
                + PermissionRules.EXPANDED
                + """
      ), effective as (
        select distinct p.account_id,p.school_id from rules p
        join identity.account a on a.id=p.account_id and a.active
        join identity.tenant t on t.id=a.tenant_id and t.active
        where p.action='AUTHORIZATION_MANAGE' and p.effect='ALLOW' and p.school_id is not null
        and not exists(select 1 from rules d where d.account_id=p.account_id and d.action=p.action and d.effect='DENY' and (d.school_id is null or d.school_id=p.school_id))
      ) select exists(select 1 from effective group by account_id
        having count(distinct school_id)=(select count(*) from organization.unit where kind='SCHOOL')) as present
      """)
        .get("present", Boolean.class);
  }

  public AccountAuthorization replace(UUID id, Replacement input) {
    return managed(
        actor -> {
          validate(input);
          var before = load(id);
          boundary(actor, before);
          if (before.version() != input.expectedVersion())
            throw new AuthorizationConflict("AUTHORIZATION_VERSION_CONFLICT");
          for (var rule : input.rules())
            if (rule.schoolId() != null && !actor.manageableSchoolIds().contains(rule.schoolId()))
              throw new AccessDeniedException("规则超出委派范围");
          for (var assignment : input.assignments()) {
            if (!actor.manageableSchoolIds().contains(assignment.schoolId()))
              throw new AccessDeniedException("角色分配超出委派范围");
          }
          var roleIds =
              input.assignments().stream()
                  .map(Assignment::roleId)
                  .collect(java.util.stream.Collectors.toSet());
          if (!roleIds.isEmpty()) {
            var versions =
                sql.fetch(
                        "select id,version from identity.permission_role where active and id in ("
                            + slots(roleIds)
                            + ")",
                        roleIds.toArray())
                    .intoMap(r -> r.get("id", UUID.class), r -> r.get("version", Long.class));
            if (versions.size() != roleIds.size()) throw new AccessDeniedException("角色不可分配");
            if (input.assignments().stream()
                .anyMatch(m -> !m.expectedRoleVersion().equals(versions.get(m.roleId()))))
              throw new AuthorizationConflict("ROLE_VERSION_CONFLICT");
          }
          sql.execute("delete from identity.account_permission where account_id=?", id);
          sql.execute("delete from identity.account_role where account_id=?", id);
          // 每条集合最多200；批量写入同一事务，不让多个请求形成意外并集。
          var ruleInsert =
              sql.batch(
                  "insert into identity.account_permission(tenant_id,account_id,action,school_id,effect) values (?,?,?,?,?)");
          for (var r : input.rules())
            ruleInsert.bind(actor.tenantId(), id, r.action(), r.schoolId(), r.effect());
          if (!input.rules().isEmpty()) ruleInsert.execute();
          var assignmentInsert =
              sql.batch(
                  "insert into identity.account_role(tenant_id,account_id,role_id,school_id) values (?,?,?,?)");
          for (var m : input.assignments())
            assignmentInsert.bind(actor.tenantId(), id, m.roleId(), m.schoolId());
          if (!input.assignments().isEmpty()) assignmentInsert.execute();
          if (sql.execute(
                  "update identity.account set authorization_version=authorization_version+1 where id=? and authorization_version=?",
                  id,
                  input.expectedVersion())
              != 1) throw new AuthorizationConflict("AUTHORIZATION_VERSION_CONFLICT");
          var after = load(id);
          boundary(actor, after);
          if (!hasFullAdministrator())
            throw new AuthorizationConflict("LAST_AUTHORIZATION_ADMINISTRATOR");
          var changedAssignments = new HashSet<>(before.assignments());
          for (var assignment : input.assignments())
            if (!changedAssignments.add(assignment)) changedAssignments.remove(assignment);
          var changedRoles =
              changedAssignments.stream()
                  .map(Assignment::roleId)
                  .collect(java.util.stream.Collectors.toSet());
          // 角色分配变化也使模板编辑页的影响人数快照过期。
          if (!changedRoles.isEmpty())
            sql.execute(
                "update identity.permission_role set version=version+1 where id in ("
                    + slots(changedRoles)
                    + ")",
                changedRoles.toArray());
          after = load(id);
          sql.execute(
              "insert into audit.authorization_event(tenant_id,actor_id,target_account_id,previous_version,next_version,before_rules,after_rules,trace_id) values (?,?,?,?,?,?::jsonb,?::jsonb,?)",
              actor.tenantId(),
              actor.accountId(),
              id,
              before.version(),
              after.version(),
              auditRules(before),
              auditRules(after),
              RequestTrace.id());
          return after;
        });
  }
}
