package org.teraschool.organization.internal;

import java.util.*;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.*;
import org.teraschool.organization.api.ResourceManagement.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 稳定类型白名单驱动三种资源；规则和关联在应用服务与数据库双重检查。 */
@Service
public class Resources {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final JsonMapper json = JsonMapper.builder().build();
  private final SchoolWriteGuard schoolGuard;

  public Resources(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolWriteGuard schoolGuard) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schoolGuard = schoolGuard;
  }

  private String table(Kind kind) {
    if (kind == null) throw new IllegalArgumentException("需要资源类型");
    return switch (kind) {
      case EMPLOYEE -> "organization.employee";
      case TEACHER -> "organization.teacher";
      case ROOM -> "organization.room";
    };
  }

  private String columns(Kind kind) {
    return switch (kind) {
          case EMPLOYEE ->
              "id,school_id,name,campus_id,department_id,null::uuid employee_id,null::integer capacity,active,version";
          case TEACHER ->
              "id,school_id,name,null::uuid campus_id,null::uuid department_id,employee_id,null::integer capacity,active,version";
          case ROOM ->
              "id,school_id,name,campus_id,null::uuid department_id,null::uuid employee_id,capacity,active,version";
        }
        + ",(select u.name from organization.unit u where u.id=r.school_id) as school_name";
  }

  private ResourceView view(Kind kind, org.jooq.Record row) {
    return new ResourceView(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("school_name", String.class),
        kind,
        row.get("name", String.class),
        row.get("campus_id", UUID.class),
        row.get("department_id", UUID.class),
        row.get("employee_id", UUID.class),
        row.get("capacity", Integer.class),
        row.get("active", Boolean.class),
        row.get("version", Long.class));
  }

  private String markers(Set<UUID> ids) {
    return String.join(",", Collections.nCopies(ids.size(), "?"));
  }

  public ResourceCapabilities capabilities(Kind kind) {
    table(kind);
    var read = actors.requireResource(kind.name(), false);
    var write = actors.requireResource(kind.name(), true);
    if (write.schools().isEmpty()) return new ResourceCapabilities(read.schools(), Set.of());
    var active =
        tx.execute(
            write.tenantId(),
            () ->
                new HashSet<>(
                    sql.fetch(
                            "select id from organization.unit where kind='SCHOOL' and active and id in ("
                                + markers(write.schools())
                                + ")",
                            write.schools().toArray())
                        .map(r -> r.get("id", UUID.class))));
    return new ResourceCapabilities(read.schools(), active);
  }

  public ResourcePage list(Kind kind, int page, int size) {
    String target = table(kind);
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页超出范围");
    var actor = actors.requireResource(kind.name(), false);
    if (actor.schools().isEmpty()) return new ResourcePage(List.of(), page, size, 0);
    return tx.execute(
        actor.tenantId(),
        () -> {
          var args = new ArrayList<Object>(actor.schools());
          String where = " where school_id in (" + markers(actor.schools()) + ")";
          long total =
              sql.fetchOne("select count(*) from " + target + where, args.toArray())
                  .get(0, Long.class);
          args.add(size);
          args.add(page * size);
          var rows =
              sql.fetch(
                      "select "
                          + columns(kind)
                          + " from "
                          + target
                          + " r"
                          + where
                          + " order by r.id limit ? offset ?",
                      args.toArray())
                  .map(r -> view(kind, r));
          return new ResourcePage(rows, page, size, total);
        });
  }

  public List<ResourceOption> options(Kind kind, String type, UUID school, String search) {
    table(kind);
    if (search == null
        || search.length() > 100
        || !Set.of("SCHOOL", "CAMPUS", "DEPARTMENT", "EMPLOYEE")
            .contains(Objects.toString(type, ""))) throw new IllegalArgumentException("无效选项条件");
    var actor = actors.requireResource(kind.name(), true);
    if (actor.schools().isEmpty()) return List.of();
    if (!type.equals("SCHOOL") && (school == null || !actor.schools().contains(school)))
      throw new AccessDeniedException("学校不可访问");
    if (type.equals("EMPLOYEE")
        && !actors.requireResource("EMPLOYEE", false).schools().contains(school))
      throw new AccessDeniedException("员工不可读取");
    return tx.execute(
        actor.tenantId(),
        () -> {
          String name =
              "%" + search.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
          if (type.equals("SCHOOL")) {
            var args = new ArrayList<Object>(actor.schools());
            args.add(name);
            return sql.fetch(
                    "select id,name from organization.unit where kind='SCHOOL' and id in ("
                        + markers(actor.schools())
                        + ") and active and name ilike ? escape '!' order by name,id limit 20",
                    args.toArray())
                .map(r -> new ResourceOption(r.get("id", UUID.class), r.get("name", String.class)));
          }
          if (type.equals("EMPLOYEE"))
            return sql.fetch(
                    "select id,name from organization.employee where school_id=? and active and name ilike ? escape '!' order by name,id limit 20",
                    school,
                    name)
                .map(r -> new ResourceOption(r.get("id", UUID.class), r.get("name", String.class)));
          return sql.fetch(
                  "select id,name from organization.unit where school_id=? and kind=? and name ilike ? escape '!' order by name,id limit 20",
                  school,
                  type,
                  name)
              .map(r -> new ResourceOption(r.get("id", UUID.class), r.get("name", String.class)));
        });
  }

  private String validate(
      Kind kind, String name, UUID campus, UUID department, UUID employee, Integer capacity) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("名称须为1到100字符且不含控制字符");
    if (kind == Kind.EMPLOYEE && (employee != null || capacity != null)
        || kind == Kind.TEACHER && (campus != null || department != null || capacity != null)
        || kind == Kind.ROOM && (department != null || employee != null))
      throw new IllegalArgumentException("资源字段不适用于此类型");
    if (capacity != null && (capacity < 0 || capacity > 100000))
      throw new IllegalArgumentException("容量须为0到100000");
    return name.strip();
  }

  private void references(UUID school, UUID campus, UUID department, UUID employee) {
    if (campus != null
        && sql.fetchOne(
                "select id from organization.unit where id=? and school_id=? and kind='CAMPUS'",
                campus,
                school)
            == null) throw new AccessDeniedException("校区不可使用");
    if (department != null
        && sql.fetchOne(
                "select id from organization.unit where id=? and school_id=? and kind='DEPARTMENT'",
                department,
                school)
            == null) throw new AccessDeniedException("部门不可使用");
    if (employee != null
        && (!actors.requireResourceInTransaction("EMPLOYEE", false).schools().contains(school)
            || sql.fetchOne(
                    "select id from organization.employee where id=? and school_id=?",
                    employee,
                    school)
                == null)) throw new AccessDeniedException("员工不可关联");
  }

  private void audit(Actor actor, String action, ResourceView before, ResourceView after) {
    sql.execute(
        "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
        actor.tenantId(),
        actor.accountId(),
        after.schoolId(),
        after.id(),
        after.kind().name(),
        action,
        before == null ? null : json.writeValueAsString(before),
        json.writeValueAsString(after),
        RequestTrace.id());
  }

  private void lockAndRecheck(Actor actor, Kind kind) {
    if (!"read committed".equals(sql.fetchOne("show transaction_isolation").get(0, String.class)))
      throw new IllegalStateException("资源维护要求READ COMMITTED事务");
    sql.fetch(
        "select pg_advisory_xact_lock(hashtextextended(?,0))", "authorization:" + actor.tenantId());
    var current = actors.requireResourceInTransaction(kind.name(), true);
    if (!current.accountId().equals(actor.accountId())
        || !current.tenantId().equals(actor.tenantId())
        || !current.schools().containsAll(actor.schools()))
      throw new AccessDeniedException("资源维护权限已变化");
  }

  public ResourceView create(Kind kind, CreateResource input) {
    table(kind);
    if (input == null || input.requestId() == null || input.schoolId() == null)
      throw new IllegalArgumentException("需要请求身份与学校");
    var actor = actors.requireResource(kind.name(), true);
    if (!actor.schools().contains(input.schoolId())) throw new AccessDeniedException("无学校维护权限");
    String name =
        validate(
            kind,
            input.name(),
            input.campusId(),
            input.departmentId(),
            input.employeeId(),
            input.capacity());
    try {
      return tx.execute(
          actor.tenantId(),
          () -> {
            lockAndRecheck(actor, kind);
            schoolGuard.requireActive(input.schoolId());
            sql.fetch(
                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                actor.tenantId() + ":" + actor.accountId() + ":resource:" + input.requestId());
            String canonical =
                json.writeValueAsString(
                    Map.of(
                        "kind",
                        kind,
                        "school",
                        input.schoolId(),
                        "name",
                        name,
                        "campus",
                        Objects.toString(input.campusId(), ""),
                        "department",
                        Objects.toString(input.departmentId(), ""),
                        "employee",
                        Objects.toString(input.employeeId(), ""),
                        "capacity",
                        input.capacity() == null ? "" : input.capacity().toString()));
            var previous =
                sql.fetchOne(
                    "select input_json,created_json from organization.resource_creation_request where tenant_id=? and actor_id=? and request_id=?",
                    actor.tenantId(),
                    actor.accountId(),
                    input.requestId());
            if (previous != null) {
              if (!json.readTree(canonical)
                  .equals(json.readTree(previous.get("input_json").toString())))
                throw new OrganizationConflict("IDEMPOTENCY_MISMATCH");
              return json.readValue(previous.get("created_json").toString(), ResourceView.class);
            }
            var schoolRow =
                sql.fetchOne(
                    "select name from organization.unit where id=? and kind='SCHOOL'",
                    input.schoolId());
            if (schoolRow == null) throw new AccessDeniedException("学校不可使用");
            references(
                input.schoolId(), input.campusId(), input.departmentId(), input.employeeId());
            UUID id =
                switch (kind) {
                  case EMPLOYEE ->
                      sql.fetchOne(
                              "insert into organization.employee(tenant_id,school_id,name,campus_id,department_id) values (?,?,?,?,?) returning id",
                              actor.tenantId(),
                              input.schoolId(),
                              name,
                              input.campusId(),
                              input.departmentId())
                          .get(0, UUID.class);
                  case TEACHER ->
                      sql.fetchOne(
                              "insert into organization.teacher(tenant_id,school_id,name,employee_id) values (?,?,?,?) returning id",
                              actor.tenantId(),
                              input.schoolId(),
                              name,
                              input.employeeId())
                          .get(0, UUID.class);
                  case ROOM ->
                      sql.fetchOne(
                              "insert into organization.room(tenant_id,school_id,name,campus_id,capacity) values (?,?,?,?,?) returning id",
                              actor.tenantId(),
                              input.schoolId(),
                              name,
                              input.campusId(),
                              input.capacity())
                          .get(0, UUID.class);
                };
            var result =
                new ResourceView(
                    id,
                    input.schoolId(),
                    schoolRow.get("name", String.class),
                    kind,
                    name,
                    input.campusId(),
                    input.departmentId(),
                    input.employeeId(),
                    input.capacity(),
                    true,
                    0);
            sql.execute(
                "insert into organization.resource_creation_request(tenant_id,actor_id,request_id,school_id,kind,resource_id,input_json,created_json) values (?,?,?,?,?,?,?::jsonb,?::jsonb)",
                actor.tenantId(),
                actor.accountId(),
                input.requestId(),
                input.schoolId(),
                kind.name(),
                id,
                canonical,
                json.writeValueAsString(result));
            audit(actor, "CREATE", null, result);
            return result;
          });
    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
      if (kind == Kind.TEACHER) throw new OrganizationConflict("EMPLOYEE_ALREADY_TEACHER");
      throw duplicate;
    }
  }

  public ResourceView update(Kind kind, UUID id, UpdateResource input) {
    String target = table(kind);
    if (id == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0
        || input.active() == null) throw new IllegalArgumentException("需要资源版本和状态");
    var actor = actors.requireResource(kind.name(), true);
    if (actor.schools().isEmpty()) throw new AccessDeniedException("无资源维护范围");
    String name =
        validate(
            kind,
            input.name(),
            input.campusId(),
            input.departmentId(),
            input.employeeId(),
            input.capacity());
    try {
      return tx.execute(
          actor.tenantId(),
          () -> {
            lockAndRecheck(actor, kind);
            var args = new ArrayList<Object>();
            args.add(id);
            args.addAll(actor.schools());
            var row =
                sql.fetchOne(
                    "select "
                        + columns(kind)
                        + " from "
                        + target
                        + " r where id=? and school_id in ("
                        + markers(actor.schools())
                        + ") for update",
                    args.toArray());
            if (row == null) throw new AccessDeniedException("资源不可访问");
            var before = view(kind, row);
            schoolGuard.requireActive(before.schoolId());
            if (before.version() != input.expectedVersion())
              throw new OrganizationConflict("VERSION_CONFLICT");
            references(
                before.schoolId(), input.campusId(), input.departmentId(), input.employeeId());
            int updated =
                switch (kind) {
                  case EMPLOYEE ->
                      sql.execute(
                          "update organization.employee set name=?,campus_id=?,department_id=?,active=?,version=version+1 where id=? and version=?",
                          name,
                          input.campusId(),
                          input.departmentId(),
                          input.active(),
                          id,
                          input.expectedVersion());
                  case TEACHER ->
                      sql.execute(
                          "update organization.teacher set name=?,employee_id=?,active=?,version=version+1 where id=? and version=?",
                          name,
                          input.employeeId(),
                          input.active(),
                          id,
                          input.expectedVersion());
                  case ROOM ->
                      sql.execute(
                          "update organization.room set name=?,campus_id=?,capacity=?,active=?,version=version+1 where id=? and version=?",
                          name,
                          input.campusId(),
                          input.capacity(),
                          input.active(),
                          id,
                          input.expectedVersion());
                };
            if (updated != 1) throw new OrganizationConflict("VERSION_CONFLICT");
            var after =
                new ResourceView(
                    id,
                    before.schoolId(),
                    before.schoolName(),
                    kind,
                    name,
                    input.campusId(),
                    input.departmentId(),
                    input.employeeId(),
                    input.capacity(),
                    input.active(),
                    before.version() + 1);
            audit(actor, "UPDATE", before, after);
            return after;
          });
    } catch (org.springframework.dao.DuplicateKeyException duplicate) {
      if (kind == Kind.TEACHER) throw new OrganizationConflict("EMPLOYEE_ALREADY_TEACHER");
      throw duplicate;
    }
  }
}
