package org.teraschool.organization.internal;

import static org.teraschool.organization.internal.generated.tables.Unit.UNIT;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.*;
import org.teraschool.organization.internal.generated.tables.records.UnitRecord;
import org.teraschool.platform.api.TenantTransactions;

/** 完整用例拥有事务和审计边界，HTTP 或第二入口均不能跳过授权。 */
@Service
public class OrganizationMaintenance {
  private static final Pattern COORDINATE = Pattern.compile("[+-]?\\d{1,3}(?:\\.\\d{1,8})?");
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final org.teraschool.platform.api.BusinessAudit audit;
  private final SchoolWriteGuard schoolGuard;

  public OrganizationMaintenance(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      org.teraschool.platform.api.BusinessAudit audit,
      SchoolWriteGuard schoolGuard) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.audit = audit;
    this.schoolGuard = schoolGuard;
  }

  public OrganizationUnit createChild(CreateOrganizationUnit command) {
    var actor = actors.requireOrganizationMaintainer();
    if (command == null
        || command.requestId() == null
        || command.schoolId() == null
        || !Set.of("CAMPUS", "DEPARTMENT").contains(Objects.toString(command.kind(), "")))
      throw new IllegalArgumentException("需要请求身份、学校和有效类型");
    var name = validName(command.name());
    requireSchool(actor, command.schoolId());
    return tx.execute(
        actor.tenantId(),
        () -> {
          schoolGuard.lock(actor.tenantId());
          requireSchool(actors.requireOrganizationMaintainerInTransaction(), command.schoolId());
          schoolGuard.requireActive(command.schoolId());
          // 数据库事务锁串行化同主体同请求；跨进程重试也只产生一次事实。
          var key = actor.tenantId() + ":" + actor.accountId() + ":" + command.requestId();
          sql.fetch("select pg_advisory_xact_lock(hashtextextended(?,0))", key);
          var previous =
              sql.fetchOne(
                  "select * from organization.creation_request where tenant_id=? and actor_id=? and request_id=?",
                  actor.tenantId(),
                  actor.accountId(),
                  command.requestId());
          if (previous != null) {
            if (!command.schoolId().equals(previous.get("school_id", UUID.class))
                || !command.kind().equals(previous.get("kind", String.class))
                || !name.equals(previous.get("name", String.class)))
              throw new OrganizationConflict("IDEMPOTENCY_MISMATCH");
            // 返回最初创建的结果，后续改名不会改写该次操作的历史结果。
            return new OrganizationUnit(
                previous.get("unit_id", UUID.class), command.schoolId(), command.kind(), name, 0);
          }
          var parent =
              sql.selectFrom(UNIT)
                  .where(UNIT.ID.eq(command.schoolId()).and(UNIT.KIND.eq("SCHOOL")))
                  .forUpdate()
                  .fetchOne();
          if (parent == null) throw new AccessDeniedException("学校不可访问");
          var row =
              sql.insertInto(UNIT)
                  .set(UNIT.TENANT_ID, actor.tenantId())
                  .set(UNIT.SCHOOL_ID, command.schoolId())
                  .set(UNIT.KIND, command.kind())
                  .set(UNIT.NAME, name)
                  .returning()
                  .fetchOne();
          sql.execute(
              "insert into organization.creation_request(tenant_id,actor_id,request_id,school_id,unit_id,kind,name) values (?,?,?,?,?,?,?)",
              actor.tenantId(),
              actor.accountId(),
              command.requestId(),
              command.schoolId(),
              row.getId(),
              command.kind(),
              name);
          audit(actor, "ORGANIZATION_CHILD_CREATED", row.getId(), command.schoolId(), null, name);
          return view(row);
        });
  }

  public OrganizationUnit rename(UUID id, RenameOrganizationUnit command) {
    var actor = actors.requireOrganizationMaintainer();
    if (id == null
        || command == null
        || command.expectedVersion() == null
        || command.expectedVersion() < 0) throw new IllegalArgumentException("需要有效版本");
    var name = validName(command.name());
    return tx.execute(
        actor.tenantId(),
        () -> {
          schoolGuard.lock(actor.tenantId());
          var verified = actors.requireOrganizationMaintainerInTransaction();
          var current =
              sql.selectFrom(UNIT)
                  .where(UNIT.ID.eq(id).and(UNIT.SCHOOL_ID.in(actor.schools())))
                  .forUpdate()
                  .fetchOne();
          if (current == null) throw new AccessDeniedException("组织不可访问");
          requireSchool(verified, current.getSchoolId());
          schoolGuard.requireActive(current.getSchoolId());
          if (!Set.of("CAMPUS", "DEPARTMENT").contains(current.getKind()))
            throw new OrganizationConflict("UNIT_KIND_NOT_EDITABLE");
          if (!current.getVersion().equals(command.expectedVersion()))
            throw new OrganizationConflict("VERSION_CONFLICT");
          boolean campusProfileSupplied =
              command.shortName() != null
                  || command.address() != null
                  || command.postalCode() != null
                  || command.busRoutes() != null
                  || command.managerName() != null
                  || command.longitude() != null
                  || command.latitude() != null
                  || command.schoolLicenseNumber() != null;
          if (campusProfileSupplied && !"CAMPUS".equals(current.getKind()))
            throw new OrganizationConflict("CAMPUS_PROFILE_NOT_EDITABLE");
          String shortName = profileValue(command.shortName(), current.getShortName(), 20, "简称");
          String address = profileValue(command.address(), current.getAddress(), 200, "地址");
          String postalCode = profileValue(command.postalCode(), current.getPostalCode(), 50, "邮编");
          String busRoutes = profileValue(command.busRoutes(), current.getBusRoutes(), 300, "公交线路");
          String managerName =
              profileValue(command.managerName(), current.getManagerName(), 100, "负责人");
          String license =
              profileValue(
                  command.schoolLicenseNumber(), current.getSchoolLicenseNumber(), 50, "办学许可证编号");
          var longitude = coordinate(command.longitude(), current.getLongitude(), 180, "经度");
          var latitude = coordinate(command.latitude(), current.getLatitude(), 90, "纬度");
          if ((longitude == null) != (latitude == null))
            throw new IllegalArgumentException("请同时填写或清空经度和纬度");
          var changed =
              sql.update(UNIT)
                  .set(UNIT.NAME, name)
                  .set(UNIT.SHORT_NAME, shortName)
                  .set(UNIT.ADDRESS, address)
                  .set(UNIT.POSTAL_CODE, postalCode)
                  .set(UNIT.BUS_ROUTES, busRoutes)
                  .set(UNIT.MANAGER_NAME, managerName)
                  .set(UNIT.LONGITUDE, longitude)
                  .set(UNIT.LATITUDE, latitude)
                  .set(UNIT.SCHOOL_LICENSE_NUMBER, license)
                  .set(UNIT.VERSION, UNIT.VERSION.plus(1L))
                  .where(UNIT.ID.eq(id).and(UNIT.VERSION.eq(command.expectedVersion())))
                  .returning()
                  .fetchOne();
          if (changed == null) throw new OrganizationConflict("VERSION_CONFLICT");
          if (campusProfileSupplied) {
            audit.append(
                actor.tenantId(),
                actor.accountId(),
                "ORGANIZATION_CAMPUS_PROFILE_UPDATED",
                id,
                current.getSchoolId(),
                current.getName(),
                name,
                campusProfileJson(current),
                campusProfileJson(changed));
          } else {
            audit(
                actor,
                "ORGANIZATION_UNIT_RENAMED",
                id,
                current.getSchoolId(),
                current.getName(),
                name);
          }
          return view(changed);
        });
  }

  private String validName(String name) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("名称须为1–100字符且不含控制字符");
    return name.strip();
  }

  private static String profileValue(String incoming, String current, int limit, String label) {
    if (incoming == null) return current;
    String value = incoming.strip();
    if (value.length() > limit || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "不得超过" + limit + "字符且不能包含控制字符");
    return value.isEmpty() ? null : value;
  }

  private static BigDecimal coordinate(
      String incoming, BigDecimal current, int limit, String label) {
    if (incoming == null) return current;
    String value = incoming.strip();
    if (value.isEmpty()) return null;
    if (!COORDINATE.matcher(value).matches())
      throw new IllegalArgumentException(label + "最多支持8位小数");
    var result = new BigDecimal(value);
    if (result.abs().compareTo(BigDecimal.valueOf(limit)) > 0)
      throw new IllegalArgumentException(label + "须在 -" + limit + " 到 " + limit + " 之间");
    return result;
  }

  private static String campusProfileJson(UnitRecord row) {
    return "{\"shortName\":"
        + json(row.getShortName())
        + ",\"address\":"
        + json(row.getAddress())
        + ",\"postalCode\":"
        + json(row.getPostalCode())
        + ",\"busRoutes\":"
        + json(row.getBusRoutes())
        + ",\"managerName\":"
        + json(row.getManagerName())
        + ",\"longitude\":"
        + json(decimalText(row.getLongitude()))
        + ",\"latitude\":"
        + json(decimalText(row.getLatitude()))
        + ",\"schoolLicenseNumber\":"
        + json(row.getSchoolLicenseNumber())
        + "}";
  }

  private static String json(String value) {
    if (value == null) return "null";
    var result = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '"') result.append('\\').append(c);
      else if (c == '\n') result.append("\\n");
      else if (c == '\r') result.append("\\r");
      else if (c == '\t') result.append("\\t");
      else result.append(c);
    }
    return result.append('"').toString();
  }

  private void requireSchool(Actor actor, UUID school) {
    if (!actor.schools().contains(school)) throw new AccessDeniedException("无学校维护权限");
  }

  private void audit(
      Actor actor, String action, UUID resource, UUID school, String before, String after) {
    audit.append(actor.tenantId(), actor.accountId(), action, resource, school, before, after);
  }

  static OrganizationUnit view(UnitRecord row) {
    return new OrganizationUnit(
        row.getId(),
        row.getSchoolId(),
        row.getKind(),
        row.getName(),
        row.getVersion(),
        row.getActive(),
        row.getShortName(),
        row.getAddress(),
        row.getPostalCode(),
        row.getBusRoutes(),
        row.getManagerName(),
        decimalText(row.getLongitude()),
        decimalText(row.getLatitude()),
        row.getSchoolLicenseNumber());
  }

  private static String decimalText(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
