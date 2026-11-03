package org.teraschool.admissions.internal;

import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.admissions.api.*;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/** 渠道仅按学校维护；不推断公私海、人员归属或跨校共享规则。 */
@Service
class ChannelService implements ChannelCommands, ChannelQueries {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;

  ChannelService(CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolAccess schools) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
  }

  @Override
  public ChannelCapabilities capabilities() {
    var scope = actors.requireResourceScope("CHANNEL");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CHANNEL");
          var names = schools.names(verified.readableSchoolIds());
          var options =
              verified.readableSchoolIds().stream()
                  .filter(names::containsKey)
                  .sorted(Comparator.comparing((UUID id) -> names.get(id)).thenComparing(id -> id))
                  .map(
                      id ->
                          new ChannelSchoolOption(
                              id,
                              names.get(id),
                              verified.maintainableSchoolIds().contains(id)
                                  && schools.require(id).active()))
                  .toList();
          return new ChannelCapabilities(options);
        });
  }

  @Override
  public ChannelPage list(UUID schoolId, int page, int size) {
    if (schoolId == null || page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("学校或分页无效");
    var scope = actors.requireResourceScope("CHANNEL");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校渠道查看权限");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CHANNEL");
          if (!verified.readableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校渠道查看权限");
          var rows =
              sql.fetch(
                  "select id,school_id,name,active,version from admissions.channel where school_id=? order by name,id limit ? offset ?",
                  schoolId,
                  size + 1,
                  (long) page * size);
          return new ChannelPage(
              rows.stream().limit(size).map(this::view).toList(), page, size, rows.size() > size);
        });
  }

  @Override
  public Channel create(ChannelCreate command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (command == null || command.requestId() == null || command.schoolId() == null)
        throw new IllegalArgumentException("需要请求身份和学校");
      var name = name(command.name());
      var scope = actors.requireResourceScope("CHANNEL");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("CHANNEL");
            requireWrite(verified, command.schoolId());
            var prior =
                sql.fetchOne(
                    "select school_id,channel_id,created_name from admissions.channel_request where actor_id=? and request_id=?",
                    scope.accountId(),
                    command.requestId());
            if (prior != null) {
              if (!command.schoolId().equals(prior.get("school_id", UUID.class))
                  || !name.equals(prior.get("created_name", String.class)))
                throw new ChannelConflict("IDEMPOTENCY_MISMATCH");
              return new Channel(
                  prior.get("channel_id", UUID.class), command.schoolId(), name, true, 0);
            }
            var id = UUID.randomUUID();
            sql.execute(
                "insert into admissions.channel(id,tenant_id,school_id,name) values (?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                name);
            sql.execute(
                "insert into admissions.channel_request(tenant_id,actor_id,request_id,school_id,channel_id,created_name) values (?,?,?,?,?,?)",
                scope.tenantId(),
                scope.accountId(),
                command.requestId(),
                command.schoolId(),
                id,
                name);
            audit(verified, command.schoolId(), id, "CREATED", "name");
            return new Channel(id, command.schoolId(), name, true, 0);
          });
    }
  }

  @Override
  public Channel update(UUID id, ChannelUpdate command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null
          || command == null
          || command.active() == null
          || command.expectedVersion() == null) throw new IllegalArgumentException("需要渠道、状态和版本");
      var name = name(command.name());
      var scope = actors.requireResourceScope("CHANNEL");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("CHANNEL");
            var row =
                sql.fetchOne(
                    "select id,school_id,name,active,version from admissions.channel where id=?",
                    id);
            if (row == null) throw new AccessDeniedException("渠道不存在或不可访问");
            var schoolId = row.get("school_id", UUID.class);
            requireWrite(verified, schoolId);
            if (!row.get("version", Long.class).equals(command.expectedVersion()))
              throw new ChannelConflict("VERSION_CONFLICT");
            var changed = new ArrayList<String>();
            if (!name.equals(row.get("name", String.class))) changed.add("name");
            if (!command.active().equals(row.get("active", Boolean.class))) changed.add("active");
            if (changed.isEmpty()) return view(row);
            if (sql.execute(
                    "update admissions.channel set name=?,active=?,version=version+1 where id=? and version=?",
                    name,
                    command.active(),
                    id,
                    command.expectedVersion())
                != 1) throw new ChannelConflict("VERSION_CONFLICT");
            audit(verified, schoolId, id, "UPDATED", String.join(",", changed));
            return new Channel(id, schoolId, name, command.active(), command.expectedVersion() + 1);
          });
    }
  }

  @Override
  public ChannelAddress address(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要渠道");
    var scope = actors.requireResourceScope("CHANNEL");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CHANNEL");
          var row =
              sql.fetchOne(
                  "select id,school_id,address,version from admissions.channel where id=?", id);
          if (row == null
              || !verified.readableSchoolIds().contains(row.get("school_id", UUID.class)))
            throw new AccessDeniedException("渠道不存在或不可访问");
          return new ChannelAddress(
              id, row.get("address", String.class), row.get("version", Long.class));
        });
  }

  @Override
  public ChannelAddress updateAddress(UUID id, ChannelAddressUpdate command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null || command == null || command.expectedVersion() == null)
        throw new IllegalArgumentException("需要渠道和版本");
      var address = command.address() == null ? "" : command.address().strip();
      if (address.length() > 50 || address.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("渠道地址不得超过 50 字");
      var scope = actors.requireResourceScope("CHANNEL");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("CHANNEL");
            var row =
                sql.fetchOne(
                    "select school_id,address,version from admissions.channel where id=?", id);
            if (row == null) throw new AccessDeniedException("渠道不存在或不可访问");
            var school = row.get("school_id", UUID.class);
            requireWrite(verified, school);
            var version = row.get("version", Long.class);
            if (!version.equals(command.expectedVersion()))
              throw new ChannelConflict("VERSION_CONFLICT");
            if (address.equals(row.get("address", String.class)))
              return new ChannelAddress(id, address, version);
            if (sql.execute(
                    "update admissions.channel set address=?,version=version+1 where id=? and version=?",
                    address,
                    id,
                    version)
                != 1) throw new ChannelConflict("VERSION_CONFLICT");
            audit(verified, school, id, "UPDATED", "address");
            return new ChannelAddress(id, address, version + 1);
          });
    }
  }

  private void requireWrite(ResourceScope scope, UUID schoolId) {
    if (!scope.maintainableSchoolIds().contains(schoolId) || !schools.require(schoolId).active())
      throw new AccessDeniedException("无该学校渠道维护权限或学校已停用");
  }

  private String name(String value) {
    var result = value == null ? "" : value.strip();
    if (result.isEmpty()
        || result.length() > 50
        || result.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("渠道名称必填且不得超过 50 字");
    return result;
  }

  private Channel view(Record row) {
    return new Channel(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("name", String.class),
        row.get("active", Boolean.class),
        row.get("version", Long.class));
  }

  private void audit(ResourceScope actor, UUID school, UUID id, String action, String fields) {
    sql.execute(
        "insert into audit.channel_event(tenant_id,actor_id,school_id,channel_id,action,changed_fields,trace_id) values (?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        school,
        id,
        action,
        fields,
        RequestTrace.id());
  }
}
