package org.teraschool.admissions.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/** 本批只允许创建人访问自己的客户；不实现公私海、分配、转移或管理员例外。 */
@Service
class CustomerService implements CustomerQueries, CustomerCommands {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;

  CustomerService(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolAccess schools) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
  }

  @Override
  public CustomerCapabilities capabilities() {
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          var names = schools.names(verified.readableSchoolIds());
          Set<UUID> phones;
          try {
            phones = actors.requireActionInTransaction("CUSTOMER_PHONE_READ").schools();
          } catch (AccessDeniedException denied) {
            phones = Set.of();
          }
          var phoneSchools = phones;
          return new CustomerCapabilities(
              verified.readableSchoolIds().stream()
                  .filter(names::containsKey)
                  .sorted(Comparator.comparing((UUID id) -> names.get(id)).thenComparing(id -> id))
                  .map(
                      id ->
                          new CustomerSchoolOption(
                              id,
                              names.get(id),
                              verified.maintainableSchoolIds().contains(id)
                                  && phoneSchools.contains(id)
                                  && schools.require(id).active(),
                              phoneSchools.contains(id)))
                  .toList());
        });
  }

  @Override
  public CustomerPage list(UUID schoolId, int page, int size) {
    if (schoolId == null || page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("学校或分页无效");
    var scope = actors.requireResourceScope("CUSTOMER");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校客户查看权限");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          if (!verified.readableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校客户查看权限");
          var showChannel = canReadChannel(schoolId);
          var rows =
              sql.fetch(
                  "select id,school_id,name,normalized_phone,channel_id from admissions.customer where school_id=? and creator_id=? order by created_at desc,id limit ? offset ?",
                  schoolId,
                  verified.accountId(),
                  size + 1,
                  (long) page * size);
          return new CustomerPage(
              rows.stream().limit(size).map(row -> view(row, showChannel)).toList(),
              page,
              size,
              rows.size() > size);
        });
  }

  @Override
  public CustomerCreateResult create(CustomerCreate command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (command == null || command.requestId() == null || command.schoolId() == null)
        throw new IllegalArgumentException("需要请求身份和学校");
      var name = name(command.name());
      var phone = phone(command.phone());
      var normalized = normalizedPhone(phone);
      var scope = actors.requireResourceScope("CUSTOMER");
      var fingerprint =
          fingerprint(
              command.schoolId().toString(),
              name,
              phone,
              Objects.toString(command.channelId(), ""));
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
            if (!verified.maintainableSchoolIds().contains(command.schoolId())
                || !schools.require(command.schoolId()).active())
              throw new AccessDeniedException("无该学校客户录入权限或学校已停用");
            var phoneSchools = actors.requireActionInTransaction("CUSTOMER_PHONE_READ").schools();
            if (!phoneSchools.contains(command.schoolId()))
              throw new AccessDeniedException("客户录入查重需要该学校的完整电话查看权限");
            var previous =
                sql.fetchOne(
                    "select school_id,customer_id,fingerprint,status from admissions.customer_request where actor_id=? and request_id=?",
                    scope.accountId(),
                    command.requestId());
            if (previous != null) {
              if (!command.schoolId().equals(previous.get("school_id", UUID.class))
                  || !fingerprint.equals(previous.get("fingerprint", String.class)))
                throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
              if ("CANCELLED".equals(previous.get("status", String.class)))
                return new CustomerCreateResult("CANCELLED", null, null);
              var existing = ownCustomer(verified, previous.get("customer_id", UUID.class));
              return new CustomerCreateResult(
                  "CREATED", view(existing, canReadChannel(command.schoolId())), null);
            }
            if (command.channelId() != null) {
              var channelScope = actors.requireResourceScopeInTransaction("CHANNEL");
              if (!channelScope.readableSchoolIds().contains(command.schoolId()))
                throw new AccessDeniedException("无该学校渠道查看权限");
              if (!sql.fetchExists(
                  sql.selectOne()
                      .from("admissions.channel")
                      .where(
                          "id=? and school_id=? and active",
                          command.channelId(),
                          command.schoolId()))) throw new CustomerConflict("CHANNEL_UNAVAILABLE");
            }
            var priorChallenge =
                sql.fetchOne(
                    "select id,fingerprint,(used_at is null and expires_at>clock_timestamp()) as valid from admissions.customer_duplicate_challenge where actor_id=? and request_id=? order by expires_at desc limit 1",
                    scope.accountId(),
                    command.requestId());
            if (priorChallenge != null
                && !fingerprint.equals(priorChallenge.get("fingerprint", String.class)))
              throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
            var duplicateId = findDuplicate(verified, phoneSchools, normalized);
            if (duplicateId != null && command.confirmationToken() == null) {
              if (priorChallenge != null) {
                if (Boolean.TRUE.equals(priorChallenge.get("valid", Boolean.class)))
                  return new CustomerCreateResult(
                      "DUPLICATE_CONFIRMATION_REQUIRED",
                      null,
                      priorChallenge.get("id", UUID.class));
              }
              var token = UUID.randomUUID();
              sql.execute(
                  "insert into admissions.customer_duplicate_challenge(id,tenant_id,actor_id,school_id,request_id,fingerprint) values (?,?,?,?,?,?)",
                  token,
                  scope.tenantId(),
                  scope.accountId(),
                  command.schoolId(),
                  command.requestId(),
                  fingerprint);
              audit(
                  verified,
                  command.schoolId(),
                  null,
                  command.requestId(),
                  "DUPLICATE_DETECTED",
                  false);
              return new CustomerCreateResult("DUPLICATE_CONFIRMATION_REQUIRED", null, token);
            }
            if (duplicateId != null) {
              int consumed =
                  sql.execute(
                      "update admissions.customer_duplicate_challenge set used_at=clock_timestamp() where id=? and actor_id=? and school_id=? and request_id=? and fingerprint=? and used_at is null and expires_at>clock_timestamp()",
                      command.confirmationToken(),
                      scope.accountId(),
                      command.schoolId(),
                      command.requestId(),
                      fingerprint);
              if (consumed != 1) throw new CustomerConflict("DUPLICATE_CONFIRMATION_INVALID");
            } else if (command.confirmationToken() != null) {
              throw new CustomerConflict("DUPLICATE_CONFIRMATION_INVALID");
            }
            var id = UUID.randomUUID();
            sql.execute(
                "insert into admissions.customer(id,tenant_id,school_id,creator_id,name,phone,normalized_phone,channel_id) values (?,?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                scope.accountId(),
                name,
                phone,
                normalized,
                command.channelId());
            sql.execute(
                "insert into admissions.customer_request(tenant_id,actor_id,request_id,school_id,customer_id,fingerprint,status) values (?,?,?,?,?,?,'CREATED')",
                scope.tenantId(),
                scope.accountId(),
                command.requestId(),
                command.schoolId(),
                id,
                fingerprint);
            audit(
                verified,
                command.schoolId(),
                id,
                command.requestId(),
                "CREATED",
                duplicateId != null);
            return new CustomerCreateResult(
                "CREATED",
                new Customer(id, command.schoolId(), name, masked(normalized), command.channelId()),
                null);
          });
    }
  }

  @Override
  public CustomerCreateResult cancel(CustomerCreate command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (command == null || command.requestId() == null || command.schoolId() == null)
        throw new IllegalArgumentException("需要请求身份和学校");
      var name = name(command.name());
      var phone = phone(command.phone());
      var fingerprint =
          fingerprint(
              command.schoolId().toString(),
              name,
              phone,
              Objects.toString(command.channelId(), ""));
      var scope = actors.requireResourceScope("CUSTOMER");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
            if (!verified.maintainableSchoolIds().contains(command.schoolId())
                || !actors
                    .requireActionInTransaction("CUSTOMER_PHONE_READ")
                    .schools()
                    .contains(command.schoolId())) throw new AccessDeniedException("无该学校客户操作权限");
            var previous =
                sql.fetchOne(
                    "select school_id,customer_id,fingerprint,status from admissions.customer_request where actor_id=? and request_id=?",
                    scope.accountId(),
                    command.requestId());
            if (previous != null) {
              if (!command.schoolId().equals(previous.get("school_id", UUID.class))
                  || !fingerprint.equals(previous.get("fingerprint", String.class)))
                throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
              if ("CREATED".equals(previous.get("status", String.class)))
                return new CustomerCreateResult(
                    "CREATED",
                    view(
                        ownCustomer(verified, previous.get("customer_id", UUID.class)),
                        canReadChannel(command.schoolId())),
                    null);
              return new CustomerCreateResult("CANCELLED", null, null);
            }
            var challenge =
                sql.fetchOne(
                    "select fingerprint from admissions.customer_duplicate_challenge where actor_id=? and request_id=? order by expires_at desc limit 1",
                    scope.accountId(),
                    command.requestId());
            if (challenge != null
                && !fingerprint.equals(challenge.get("fingerprint", String.class)))
              throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
            sql.execute(
                "insert into admissions.customer_request(tenant_id,actor_id,request_id,school_id,customer_id,fingerprint,status) values (?,?,?,?,null,?,'CANCELLED')",
                scope.tenantId(),
                scope.accountId(),
                command.requestId(),
                command.schoolId(),
                fingerprint);
            audit(verified, command.schoolId(), null, command.requestId(), "CANCELLED", false);
            return new CustomerCreateResult("CANCELLED", null, null);
          });
    }
  }

  @Override
  public CustomerPhone revealPhone(UUID id) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new CustomerConflict("CUSTOMER_PHONE_REQUIRES_INDEPENDENT_TRANSACTION");
      if (id == null) throw new IllegalArgumentException("需要客户");
      var scope = actors.requireResourceScope("CUSTOMER");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
            var row = ownCustomer(verified, id);
            var schoolId = row.get("school_id", UUID.class);
            if (!actors
                .requireActionInTransaction("CUSTOMER_PHONE_READ")
                .schools()
                .contains(schoolId)) throw new AccessDeniedException("无完整电话查看权限");
            audit(verified, schoolId, id, null, "PHONE_VIEWED", false);
            return new CustomerPhone(row.get("phone", String.class));
          });
    }
  }

  private Record ownCustomer(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,name,phone,normalized_phone,channel_id from admissions.customer where id=? and creator_id=?",
            id,
            scope.accountId());
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("客户不存在或不可访问");
    return row;
  }

  /** 只在本人目前有读取权限的学校中检查，不泄露其他人的客户。 */
  private UUID findDuplicate(ResourceScope scope, Set<UUID> phoneSchools, String normalized) {
    var ids = new ArrayList<>(scope.readableSchoolIds());
    ids.retainAll(phoneSchools);
    for (int offset = 0; offset < ids.size(); offset += 500) {
      var part = ids.subList(offset, Math.min(offset + 500, ids.size()));
      var marks = String.join(",", Collections.nCopies(part.size(), "?"));
      var args = new ArrayList<Object>();
      args.add(scope.accountId());
      args.add(normalized);
      args.addAll(part);
      var found =
          sql.fetch(
              "select id from admissions.customer where creator_id=? and normalized_phone=? and school_id in ("
                  + marks
                  + ") limit 1",
              args.toArray());
      if (!found.isEmpty()) return found.get(0).get("id", UUID.class);
    }
    return null;
  }

  private static String name(String input) {
    var value = input == null ? "" : input.strip();
    if (value.isEmpty()
        || value.length() > 50
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("客户姓名必填且不超过 50 字");
    return value;
  }

  private static String phone(String input) {
    var value = input == null ? "" : input.strip();
    if (!value.matches("[+0-9() -]{5,30}") || !normalizedPhone(value).matches("\\+?[0-9]{5,20}"))
      throw new IllegalArgumentException("联系电话格式无效");
    return value;
  }

  private static String normalizedPhone(String value) {
    return value.replace(" ", "").replace("-", "").replace("(", "").replace(")", "");
  }

  private static String masked(String value) {
    return "****" + value.substring(Math.max(0, value.length() - 4));
  }

  private Customer view(Record row, boolean showChannel) {
    return new Customer(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("name", String.class),
        masked(row.get("normalized_phone", String.class)),
        showChannel ? row.get("channel_id", UUID.class) : null);
  }

  private boolean canReadChannel(UUID schoolId) {
    try {
      return actors
          .requireResourceScopeInTransaction("CHANNEL")
          .readableSchoolIds()
          .contains(schoolId);
    } catch (AccessDeniedException denied) {
      return false;
    }
  }

  private static String fingerprint(String... values) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var value : values) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void audit(
      ResourceScope scope, UUID school, UUID id, UUID request, String action, boolean duplicate) {
    sql.execute(
        "insert into audit.customer_event(tenant_id,actor_id,school_id,customer_id,request_id,action,duplicate_confirmed,trace_id) values (?,?,?,?,?,?,?,?)",
        scope.tenantId(),
        scope.accountId(),
        school,
        id,
        request,
        action,
        duplicate,
        RequestTrace.id());
  }
}
