package org.teraschool.organization.internal;

import static org.teraschool.organization.internal.generated.tables.Unit.UNIT;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.*;
import org.teraschool.platform.api.TenantTransactions;

/** 完整用例拥有事务和审计边界，HTTP 或第二入口均不能跳过授权。 */
@Service
public class OrganizationMaintenance implements OrganizationCommands {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final org.teraschool.platform.api.BusinessAudit audit;

  public OrganizationMaintenance(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      org.teraschool.platform.api.BusinessAudit audit) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.audit = audit;
  }

  @Override
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

  @Override
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
          var current =
              sql.selectFrom(UNIT)
                  .where(UNIT.ID.eq(id).and(UNIT.SCHOOL_ID.in(actor.schools())))
                  .forUpdate()
                  .fetchOne();
          if (current == null) throw new AccessDeniedException("组织不可访问");
          if (!Set.of("CAMPUS", "DEPARTMENT").contains(current.getKind()))
            throw new OrganizationConflict("UNIT_KIND_NOT_EDITABLE");
          if (!current.getVersion().equals(command.expectedVersion()))
            throw new OrganizationConflict("VERSION_CONFLICT");
          var changed =
              sql.update(UNIT)
                  .set(UNIT.NAME, name)
                  .set(UNIT.VERSION, UNIT.VERSION.plus(1L))
                  .where(UNIT.ID.eq(id).and(UNIT.VERSION.eq(command.expectedVersion())))
                  .returning()
                  .fetchOne();
          if (changed == null) throw new OrganizationConflict("VERSION_CONFLICT");
          audit(
              actor,
              "ORGANIZATION_UNIT_RENAMED",
              id,
              current.getSchoolId(),
              current.getName(),
              name);
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

  private void requireSchool(Actor actor, UUID school) {
    if (!actor.schools().contains(school)) throw new AccessDeniedException("无学校维护权限");
  }

  private void audit(
      Actor actor, String action, UUID resource, UUID school, String before, String after) {
    audit.append(actor.tenantId(), actor.accountId(), action, resource, school, before, after);
  }

  private OrganizationUnit view(
      org.teraschool.organization.internal.generated.tables.records.UnitRecord row) {
    return new OrganizationUnit(
        row.getId(), row.getSchoolId(), row.getKind(), row.getName(), row.getVersion());
  }
}
