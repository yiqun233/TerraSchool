package org.teraschool.identity.internal;

import java.util.*;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.identity.api.RoleTemplateManagement.*;
import org.teraschool.platform.api.RequestTrace;
import tools.jackson.databind.json.JsonMapper;

/** 共享角色影响的账号以SQL集合检查，不逐账号加载，停用/启用也走相同保护。 */
@Service
public class RoleTemplates {
  private final AccountAuthorizations authorization;
  private final DSLContext sql;
  private final JsonMapper json = JsonMapper.builder().build();

  public RoleTemplates(AccountAuthorizations authorization, DSLContext sql) {
    this.authorization = authorization;
    this.sql = sql;
  }

  private String slots(Set<UUID> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }

  private String search(String value) {
    if (value == null || value.length() > 100) throw new IllegalArgumentException("检索最长100字符");
    return "%" + value.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  // 检查所有使用该模板的账号完整原始学校配置。该集合包含所有有效学校的上界，
  // 因而同样约束修改前后有效范围，即使旧DENY暂时遮住外校ALLOW也不能越权维护。
  private String outside(String role, Set<UUID> scope) {
    String in = slots(scope);
    return "exists(select 1 from identity.account_role affected where affected.role_id="
        + role
        + " and ("
        + "exists(select 1 from identity.account_permission p where p.account_id=affected.account_id and p.school_id is not null and p.school_id not in ("
        + in
        + ")) or "
        + "exists(select 1 from identity.account_role m where m.account_id=affected.account_id and m.school_id not in ("
        + in
        + "))))";
  }

  private void boundary(UUID role, CurrentPermissions actor) {
    var args = new ArrayList<Object>();
    args.add(role);
    args.addAll(actor.manageableSchoolIds());
    args.addAll(actor.manageableSchoolIds());
    if (sql.fetchOne(
            "select " + outside("?", actor.manageableSchoolIds()) + " as forbidden", args.toArray())
        .get("forbidden", Boolean.class)) throw new AccessDeniedException("角色影响账号超出委派范围");
  }

  public List<RoleSummary> listRoles(String search, boolean includeInactive) {
    return authorization.managed(
        actor -> {
          var args = new ArrayList<Object>();
          args.add(includeInactive);
          args.add(search(search));
          args.add(search(search));
          args.addAll(actor.manageableSchoolIds());
          args.addAll(actor.manageableSchoolIds());
          return sql.fetch(
                  "select r.id,r.code,r.name,r.active,r.version from identity.permission_role r where (? or r.active) and (r.name ilike ? escape '!' or r.code ilike ? escape '!') and not "
                      + outside("r.id", actor.manageableSchoolIds())
                      + " order by r.name,r.id limit 20",
                  args.toArray())
              .map(
                  r ->
                      new RoleSummary(
                          r.get("id", UUID.class),
                          r.get("code", String.class),
                          r.get("name", String.class),
                          r.get("active", Boolean.class),
                          r.get("version", Long.class)));
        });
  }

  private RoleTemplate load(UUID id) {
    if (id == null) throw new IllegalArgumentException("缺少角色");
    var role =
        sql.fetchOne(
            "select id,code,name,active,version from identity.permission_role where id=?", id);
    if (role == null) throw new AccessDeniedException("角色不可管理");
    var actions =
        sql.fetch(
                "select action,effect from identity.role_action where role_id=? order by action,effect",
                id)
            .map(r -> new RoleAction(r.get("action", String.class), r.get("effect", String.class)));
    int accounts =
        sql.fetchOne(
                "select count(distinct account_id)::integer as total from identity.account_role where role_id=?",
                id)
            .get("total", Integer.class);
    return new RoleTemplate(
        id,
        role.get("code", String.class),
        role.get("name", String.class),
        role.get("active", Boolean.class),
        role.get("version", Long.class),
        actions,
        accounts);
  }

  public RoleTemplate getRole(UUID id) {
    return authorization.managed(
        actor -> {
          boundary(id, actor);
          return load(id);
        });
  }

  private String name(String value) {
    if (value == null) throw new IllegalArgumentException("需要角色名称");
    String result = value.strip();
    if (result.isEmpty()
        || result.length() > 100
        || result.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("名称需为1到100字符且不含控制字符");
    return result;
  }

  private List<RoleAction> actions(List<RoleAction> values) {
    if (values == null || values.size() > PermissionRules.ACTIONS.size() * 2)
      throw new IllegalArgumentException("需要完整动作集合，动作数量超出目录范围");
    for (var r : values)
      if (r == null
          || !PermissionRules.ACTIONS.contains(Objects.toString(r.action(), ""))
          || !Set.of("ALLOW", "DENY").contains(Objects.toString(r.effect(), "")))
        throw new IllegalArgumentException("无效角色动作");
    if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("重复角色动作");
    return values.stream()
        .sorted(Comparator.comparing(RoleAction::action).thenComparing(RoleAction::effect))
        .toList();
  }

  private void writeActions(UUID tenant, UUID role, List<RoleAction> actions) {
    sql.execute("delete from identity.role_action where role_id=?", role);
    var batch =
        sql.batch(
            "insert into identity.role_action(tenant_id,role_id,action,effect) values (?,?,?,?)");
    for (var r : actions) batch.bind(tenant, role, r.action(), r.effect());
    if (!actions.isEmpty()) batch.execute();
  }

  private void audit(
      CurrentPermissions actor,
      String action,
      RoleTemplate before,
      RoleTemplate after,
      int affected) {
    sql.execute(
        "insert into audit.role_template_event(tenant_id,actor_id,role_id,action,before_template,after_template,affected_accounts,trace_id) values (?,?,?,?,?::jsonb,?::jsonb,?,?)",
        actor.tenantId(),
        actor.accountId(),
        after.id(),
        action,
        before == null ? null : json.writeValueAsString(before),
        json.writeValueAsString(after),
        affected,
        RequestTrace.id());
  }

  public RoleTemplate createRole(CreateRole input) {
    return authorization.managed(
        actor -> {
          if (input == null || input.requestId() == null || input.code() == null)
            throw new IllegalArgumentException("需要创建身份与代码");
          String code = input.code().strip().toUpperCase(Locale.ROOT), name = name(input.name());
          if (!code.matches("[A-Z][A-Z0-9_]{2,79}"))
            throw new IllegalArgumentException("代码需为3到80位大写字母/数字/下划线，字母开头");
          var actions = actions(input.actions());
          String canonical =
              json.writeValueAsString(Map.of("code", code, "name", name, "actions", actions));
          var previous =
              sql.fetchOne(
                  "select role_id,input_json,created_role from identity.role_creation_request where tenant_id=? and actor_id=? and request_id=?",
                  actor.tenantId(),
                  actor.accountId(),
                  input.requestId());
          if (previous != null) {
            boundary(previous.get("role_id", UUID.class), actor);
            if (!json.readTree(canonical)
                .equals(json.readTree(previous.get("input_json").toString())))
              throw new AuthorizationConflict("ROLE_REQUEST_MISMATCH");
            return json.readValue(previous.get("created_role").toString(), RoleTemplate.class);
          }
          if (sql.fetchOne("select id from identity.permission_role where code=?", code) != null)
            throw new AuthorizationConflict("ROLE_CODE_EXISTS");
          UUID id =
              sql.fetchOne(
                      "insert into identity.permission_role(tenant_id,code,name) values (?,?,?) returning id",
                      actor.tenantId(),
                      code,
                      name)
                  .get("id", UUID.class);
          writeActions(actor.tenantId(), id, actions);
          var result = load(id);
          sql.execute(
              "insert into identity.role_creation_request(tenant_id,actor_id,request_id,role_id,input_json,created_role) values (?,?,?,?,?::jsonb,?::jsonb)",
              actor.tenantId(),
              actor.accountId(),
              input.requestId(),
              id,
              canonical,
              json.writeValueAsString(result));
          audit(actor, "ROLE_TEMPLATE_CREATE", null, result, 0);
          return result;
        });
  }

  public RoleTemplate replaceRole(UUID id, RoleChange input) {
    return authorization.managed(
        actor -> {
          if (input == null
              || input.expectedVersion() == null
              || input.expectedVersion() < 0
              || input.active() == null) throw new IllegalArgumentException("需要版本和可用状态");
          String name = name(input.name());
          var actions = actions(input.actions());
          boundary(id, actor);
          var before = load(id);
          if (before.version() != input.expectedVersion())
            throw new AuthorizationConflict("ROLE_VERSION_CONFLICT");
          if (sql.execute(
                  "update identity.permission_role set name=?,active=?,version=version+1 where id=? and version=?",
                  name,
                  input.active(),
                  id,
                  input.expectedVersion())
              != 1) throw new AuthorizationConflict("ROLE_VERSION_CONFLICT");
          writeActions(actor.tenantId(), id, actions);
          boundary(id, actor);
          if (!authorization.hasFullAdministrator())
            throw new AuthorizationConflict("LAST_AUTHORIZATION_ADMINISTRATOR");
          // 模板变化影响所有分配账号，包括停用账号；使其旧编辑快照全部过期。
          int affected =
              sql.execute(
                  "update identity.account a set authorization_version=authorization_version+1 where exists(select 1 from identity.account_role m where m.account_id=a.id and m.role_id=?)",
                  id);
          var result = load(id);
          audit(actor, "ROLE_TEMPLATE_REPLACE", before, result, affected);
          return result;
        });
  }
}
