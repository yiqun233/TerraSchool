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
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.OrganizationQueries;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/** 客户默认仅当前负责人可见；同校公海由独立动作授权访问。 */
@Service
class CustomerService implements CustomerQueries, CustomerCommands {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final OrganizationQueries organization;

  CustomerService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      OrganizationQueries organization) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.organization = organization;
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
          Set<UUID> poolReadable;
          Set<UUID> poolManageable;
          try {
            poolReadable = actors.requireActionInTransaction("CUSTOMER_POOL_READ").schools();
          } catch (AccessDeniedException denied) {
            poolReadable = Set.of();
          }
          Set<UUID> assignmentManageable;
          try {
            assignmentManageable = actors.requireActionInTransaction("CUSTOMER_ASSIGN").schools();
          } catch (AccessDeniedException denied) {
            assignmentManageable = Set.of();
          }
          try {
            poolManageable = actors.requireActionInTransaction("CUSTOMER_POOL_MANAGE").schools();
          } catch (AccessDeniedException denied) {
            poolManageable = Set.of();
          }
          var poolReadSchools = poolReadable;
          var poolManageSchools = poolManageable;
          var assignmentSchools = assignmentManageable;
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
                                  && schools.require(id).active(),
                              phoneSchools.contains(id),
                              poolReadSchools.contains(id),
                              poolManageSchools.contains(id),
                              assignmentSchools.contains(id)))
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
                  "select id,school_id,name,normalized_phone,channel_id,version from admissions.customer where school_id=? and owner_id=? and in_pool=false and merged_into_id is null order by created_at desc,id limit ? offset ?",
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
  public CustomerPage pool(UUID schoolId, int page, int size) {
    if (schoolId == null || page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("学校或分页无效");
    var pool = actors.requireAction("CUSTOMER_POOL_READ");
    if (!pool.schools().contains(schoolId)) throw new AccessDeniedException("无该学校公海客户查看权限");
    return tx.execute(
        pool.tenantId(),
        () -> {
          schools.lockTenant(pool.tenantId());
          var verified = actors.requireActionInTransaction("CUSTOMER_POOL_READ");
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校公海客户查看权限");
          var showChannel = canReadChannel(schoolId);
          var rows =
              sql.fetch(
                  "select id,school_id,name,normalized_phone,channel_id,version from admissions.customer where school_id=? and in_pool=true and merged_into_id is null order by created_at,id limit ? offset ?",
                  schoolId,
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
  public List<CustomerAssignee> assignableEmployees(UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    return organization.customerAssignmentOptions(schoolId).stream()
        .map(option -> new CustomerAssignee(option.accountId(), option.name()))
        .toList();
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
                "insert into admissions.customer(id,tenant_id,school_id,creator_id,owner_id,name,phone,normalized_phone,channel_id) values (?,?,?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                scope.accountId(),
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
                new Customer(
                    id, command.schoolId(), name, masked(normalized), command.channelId(), 0),
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

  @Override
  public Customer releaseToPool(UUID id, long expectedVersion) {
    if (id == null || expectedVersion < 0) throw new IllegalArgumentException("需要客户和版本");
    var manager = actors.requireAction("CUSTOMER_POOL_MANAGE");
    return tx.execute(
        manager.tenantId(),
        () -> {
          schools.lockTenant(manager.tenantId());
          var verified = actors.requireActionInTransaction("CUSTOMER_POOL_MANAGE");
          var row =
              sql.fetchOne(
                  "select id,school_id,name,phone,normalized_phone,channel_id,owner_id,in_pool,version from admissions.customer where id=? for update",
                  id);
          if (row == null || !verified.schools().contains(row.get("school_id", UUID.class)))
            throw new AccessDeniedException("客户不存在或无公海管理权限");
          var schoolId = row.get("school_id", UUID.class);
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用");
          if (Boolean.TRUE.equals(row.get("in_pool", Boolean.class)))
            throw new CustomerConflict("CUSTOMER_ALREADY_IN_POOL");
          var mergedChildren =
              sql.fetchOne(
                  "select exists(select 1 from admissions.customer where merged_into_id=?) as has_children",
                  id);
          if (Boolean.TRUE.equals(mergedChildren.get("has_children", Boolean.class)))
            throw new CustomerConflict("CUSTOMER_HAS_MERGED_RECORDS");
          if (!row.get("version", Long.class).equals(expectedVersion))
            throw new CustomerConflict("VERSION_CONFLICT");
          int changed =
              sql.execute(
                  "update admissions.customer set owner_id=null,in_pool=true,version=version+1 where id=? and version=? and in_pool=false",
                  id,
                  expectedVersion);
          if (changed != 1) throw new CustomerConflict("VERSION_CONFLICT");
          audit(verified, schoolId, id, null, "POOL_RELEASED", false);
          return view(
              sql.fetchOne(
                  "select id,school_id,name,phone,normalized_phone,channel_id,version from admissions.customer where id=?",
                  id),
              canReadChannel(schoolId));
        });
  }

  @Override
  public Customer assignOwner(UUID id, CustomerAssign command) {
    if (id == null
        || command == null
        || command.requestId() == null
        || command.ownerAccountId() == null
        || command.expectedVersion() < 0) throw new IllegalArgumentException("客户转派请求不完整");
    var reason = Objects.toString(command.reason(), "").strip();
    if (reason.isEmpty() || reason.length() > 300)
      throw new IllegalArgumentException("请填写不超过300字的转派原因");
    var actor = actors.requireAction("CUSTOMER_ASSIGN");
    var digest =
        fingerprint(
            id.toString(),
            command.ownerAccountId().toString(),
            Long.toString(command.expectedVersion()),
            reason);
    return tx.execute(
        actor.tenantId(),
        () -> {
          schools.lockTenant(actor.tenantId());
          var verified = actors.requireActionInTransaction("CUSTOMER_ASSIGN");
          sql.execute(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              actor.accountId() + ":" + command.requestId());
          var previous =
              sql.fetchOne(
                  "select customer_id,fingerprint from admissions.customer_assignment_request where tenant_id=? and actor_id=? and request_id=?",
                  actor.tenantId(),
                  actor.accountId(),
                  command.requestId());
          if (previous != null) {
            if (!id.equals(previous.get("customer_id", UUID.class))
                || !digest.equals(previous.get("fingerprint", String.class)))
              throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
            var replay =
                sql.fetchOne(
                    "select id,school_id,name,phone,normalized_phone,channel_id,version from admissions.customer where id=?",
                    id);
            if (replay == null || !verified.schools().contains(replay.get("school_id", UUID.class)))
              throw new CustomerConflict("CUSTOMER_UNAVAILABLE");
            return view(replay, canReadChannel(replay.get("school_id", UUID.class)));
          }
          var row =
              sql.fetchOne(
                  "select id,school_id,name,phone,normalized_phone,channel_id,owner_id,in_pool,version,merged_into_id from admissions.customer where id=? for update",
                  id);
          if (row == null) throw new CustomerConflict("CUSTOMER_UNAVAILABLE");
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该校客户转派权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用");
          if (Boolean.TRUE.equals(row.get("in_pool", Boolean.class))
              || row.get("merged_into_id", UUID.class) != null)
            throw new CustomerConflict("CUSTOMER_NOT_ASSIGNABLE");
          if (sql.fetchExists(
              sql.selectOne().from("admissions.customer").where("merged_into_id=?", id)))
            throw new CustomerConflict("CUSTOMER_HAS_MERGED_RECORDS");
          if (row.get("version", Long.class) != command.expectedVersion())
            throw new CustomerConflict("VERSION_CONFLICT");
          var eligible = organization.isActiveCustomerAssignee(schoolId, command.ownerAccountId());
          if (!eligible) throw new CustomerConflict("CUSTOMER_ASSIGNEE_UNAVAILABLE");
          if (command.ownerAccountId().equals(row.get("owner_id", UUID.class)))
            throw new CustomerConflict("CUSTOMER_ALREADY_ASSIGNED");
          int changed =
              sql.execute(
                  "update admissions.customer set owner_id=?,owner_assigned_at=CURRENT_TIMESTAMP,version=version+1 where id=? and version=? and in_pool=false and merged_into_id is null",
                  command.ownerAccountId(),
                  id,
                  command.expectedVersion());
          if (changed != 1) throw new CustomerConflict("VERSION_CONFLICT");
          sql.execute(
              "insert into admissions.customer_assignment_request(tenant_id,actor_id,request_id,customer_id,fingerprint,reason) values (?,?,?,?,?,?)",
              actor.tenantId(),
              actor.accountId(),
              command.requestId(),
              id,
              digest,
              reason);
          audit(
              actor.tenantId(),
              actor.accountId(),
              schoolId,
              id,
              command.requestId(),
              "OWNER_ASSIGNED",
              false);
          return view(
              sql.fetchOne(
                  "select id,school_id,name,phone,normalized_phone,channel_id,version from admissions.customer where id=?",
                  id),
              canReadChannel(schoolId));
        });
  }

  @Override
  public Customer claim(UUID id, long expectedVersion) {
    if (id == null || expectedVersion < 0) throw new IllegalArgumentException("需要客户和版本");
    var pool = actors.requireAction("CUSTOMER_POOL_READ");
    var customerScope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        pool.tenantId(),
        () -> {
          schools.lockTenant(pool.tenantId());
          var verifiedPool = actors.requireActionInTransaction("CUSTOMER_POOL_READ");
          var verifiedCustomer = actors.requireResourceScopeInTransaction("CUSTOMER");
          var row =
              sql.fetchOne(
                  "select id,school_id,name,phone,normalized_phone,channel_id,in_pool,version from admissions.customer where id=? for update",
                  id);
          if (row == null) throw new AccessDeniedException("公海客户不存在或不可访问");
          var schoolId = row.get("school_id", UUID.class);
          if (!verifiedPool.schools().contains(schoolId)
              || !verifiedCustomer.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校公海认领权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用");
          if (!Boolean.TRUE.equals(row.get("in_pool", Boolean.class)))
            throw new CustomerConflict("CUSTOMER_NOT_IN_POOL");
          if (!row.get("version", Long.class).equals(expectedVersion))
            throw new CustomerConflict("VERSION_CONFLICT");
          int changed =
              sql.execute(
                  "update admissions.customer set owner_id=?,in_pool=false,owner_assigned_at=CURRENT_TIMESTAMP,version=version+1 where id=? and version=? and in_pool=true",
                  verifiedCustomer.accountId(),
                  id,
                  expectedVersion);
          if (changed != 1) throw new CustomerConflict("VERSION_CONFLICT");
          audit(verifiedCustomer, schoolId, id, null, "POOL_CLAIMED", false);
          return view(
              sql.fetchOne(
                  "select id,school_id,name,phone,normalized_phone,channel_id,version from admissions.customer where id=?",
                  id),
              canReadChannel(schoolId));
        });
  }

  @Override
  public CustomerMergeResult merge(
      UUID sourceId, UUID primaryId, long expectedSourceVersion, long expectedPrimaryVersion) {
    if (sourceId == null || primaryId == null || sourceId.equals(primaryId))
      throw new IllegalArgumentException("请选择两个不同客户");
    if (expectedSourceVersion < 0 || expectedPrimaryVersion < 0)
      throw new IllegalArgumentException("客户版本无效");
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          var locked =
              sql.fetch(
                  "select id,school_id,owner_id,in_pool,version,merged_into_id from admissions.customer"
                      + " where id in (?,?) order by id for update",
                  sourceId,
                  primaryId);
          if (locked.size() != 2) throw new AccessDeniedException("客户不存在或不可访问");
          var source =
              locked.stream()
                  .filter(row -> sourceId.equals(row.get("id", UUID.class)))
                  .findFirst()
                  .orElseThrow();
          var primary =
              locked.stream()
                  .filter(row -> primaryId.equals(row.get("id", UUID.class)))
                  .findFirst()
                  .orElseThrow();
          var schoolId = source.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId)
              || !schoolId.equals(primary.get("school_id", UUID.class))
              || !verified.accountId().equals(source.get("owner_id", UUID.class))
              || !verified.accountId().equals(primary.get("owner_id", UUID.class)))
            throw new AccessDeniedException("仅可合并本人负责的同校客户");
          if (source.get("version", Long.class) != expectedSourceVersion
              || primary.get("version", Long.class) != expectedPrimaryVersion)
            throw new CustomerConflict("VERSION_CONFLICT");
          if (Boolean.TRUE.equals(source.get("in_pool", Boolean.class))
              || Boolean.TRUE.equals(primary.get("in_pool", Boolean.class))
              || source.get("merged_into_id", UUID.class) != null
              || primary.get("merged_into_id", UUID.class) != null)
            throw new CustomerConflict("CUSTOMER_NOT_MERGEABLE");
          var children =
              sql.fetchOne(
                  "select exists(select 1 from admissions.customer where merged_into_id=?) as has_children",
                  sourceId);
          if (Boolean.TRUE.equals(children.get("has_children", Boolean.class)))
            throw new CustomerConflict("CUSTOMER_HAS_MERGED_RECORDS");
          int changed =
              sql.execute(
                  "update admissions.customer set merged_into_id=?,merged_at=clock_timestamp(),version=version+1"
                      + " where id=? and version=? and merged_into_id is null and in_pool=false",
                  primaryId,
                  sourceId,
                  expectedSourceVersion);
          if (changed != 1) throw new CustomerConflict("VERSION_CONFLICT");
          int primaryChanged =
              sql.execute(
                  "update admissions.customer set version=version+1"
                      + " where id=? and version=? and merged_into_id is null and in_pool=false",
                  primaryId,
                  expectedPrimaryVersion);
          if (primaryChanged != 1) throw new CustomerConflict("VERSION_CONFLICT");
          audit(verified, schoolId, sourceId, null, "MERGED", false);
          return new CustomerMergeResult(
              sourceId, primaryId, expectedSourceVersion + 1, expectedPrimaryVersion + 1);
        });
  }

  @Override
  public CustomerMergeResult reverseMerge(UUID sourceId, long expectedSourceVersion) {
    if (sourceId == null || expectedSourceVersion < 0)
      throw new IllegalArgumentException("需要客户和版本");
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          var sourceLink =
              sql.fetchOne(
                  "select school_id,owner_id,version,merged_into_id from admissions.customer where id=?",
                  sourceId);
          if (sourceLink == null
              || !verified.readableSchoolIds().contains(sourceLink.get("school_id", UUID.class))
              || !verified.accountId().equals(sourceLink.get("owner_id", UUID.class)))
            throw new AccessDeniedException("客户不存在或不可访问");
          var primaryId = sourceLink.get("merged_into_id", UUID.class);
          if (primaryId == null) throw new CustomerConflict("CUSTOMER_NOT_MERGED");
          if (!verified.maintainableSchoolIds().contains(sourceLink.get("school_id", UUID.class)))
            throw new AccessDeniedException("无该学校客户维护权限");
          var locked =
              sql.fetch(
                  "select id,school_id,owner_id,version,merged_into_id,in_pool from admissions.customer"
                      + " where id in (?,?) order by id for update",
                  sourceId,
                  primaryId);
          if (locked.size() != 2) throw new CustomerConflict("CUSTOMER_PRIMARY_NOT_AVAILABLE");
          var source =
              locked.stream()
                  .filter(row -> sourceId.equals(row.get("id", UUID.class)))
                  .findFirst()
                  .orElseThrow();
          var primary =
              locked.stream()
                  .filter(row -> primaryId.equals(row.get("id", UUID.class)))
                  .findFirst()
                  .orElseThrow();
          if (!primaryId.equals(source.get("merged_into_id", UUID.class)))
            throw new CustomerConflict("VERSION_CONFLICT");
          if (source.get("version", Long.class) != expectedSourceVersion)
            throw new CustomerConflict("VERSION_CONFLICT");
          if (primary == null
              || !verified.accountId().equals(primary.get("owner_id", UUID.class))
              || !source.get("school_id", UUID.class).equals(primary.get("school_id", UUID.class)))
            throw new AccessDeniedException("主档客户不存在或不可访问");
          if (primary.get("merged_into_id", UUID.class) != null
              || Boolean.TRUE.equals(primary.get("in_pool", Boolean.class)))
            throw new CustomerConflict("CUSTOMER_PRIMARY_NOT_AVAILABLE");
          int changed =
              sql.execute(
                  "update admissions.customer set merged_into_id=null,merged_at=null,version=version+1"
                      + " where id=? and version=? and merged_into_id=?",
                  sourceId,
                  expectedSourceVersion,
                  primaryId);
          if (changed != 1) throw new CustomerConflict("VERSION_CONFLICT");
          int primaryChanged =
              sql.execute(
                  "update admissions.customer set version=version+1 where id=? and version=?"
                      + " and owner_id=? and merged_into_id is null and in_pool=false",
                  primaryId,
                  primary.get("version", Long.class),
                  verified.accountId());
          if (primaryChanged != 1) throw new CustomerConflict("VERSION_CONFLICT");
          audit(
              verified,
              source.get("school_id", UUID.class),
              sourceId,
              null,
              "MERGE_REVERSED",
              false);
          return new CustomerMergeResult(
              sourceId,
              primaryId,
              expectedSourceVersion + 1,
              primary.get("version", Long.class) + 1);
        });
  }

  @Override
  public List<CustomerQueries.MergedCustomer> mergedInto(UUID primaryId) {
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          var primary = ownCustomer(verified, primaryId);
          if (!verified.maintainableSchoolIds().contains(primary.get("school_id", UUID.class)))
            throw new AccessDeniedException("无该学校客户维护权限");
          return sql.fetch(
                  "select id,name,version,merged_at from admissions.customer where merged_into_id=?"
                      + " and owner_id=? order by merged_at,id",
                  primaryId,
                  verified.accountId())
              .map(
                  row ->
                      new CustomerQueries.MergedCustomer(
                          row.get("id", UUID.class),
                          row.get("name", String.class),
                          row.get("version", Long.class),
                          row.get("merged_at", java.time.OffsetDateTime.class).toString()));
        });
  }

  Record ownCustomer(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,name,phone,normalized_phone,channel_id,version from admissions.customer where id=? and owner_id=? and in_pool=false and merged_into_id is null",
            id,
            scope.accountId());
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("客户不存在或不可访问");
    return row;
  }

  /** 只在本人获完整电话权限的学校内跨负责人查重，只返回是否重复，不泄露客户身份。 */
  private UUID findDuplicate(ResourceScope scope, Set<UUID> phoneSchools, String normalized) {
    var ids = new ArrayList<>(scope.readableSchoolIds());
    ids.retainAll(phoneSchools);
    for (int offset = 0; offset < ids.size(); offset += 500) {
      var part = ids.subList(offset, Math.min(offset + 500, ids.size()));
      var marks = String.join(",", Collections.nCopies(part.size(), "?"));
      var args = new ArrayList<Object>();
      args.add(normalized);
      args.addAll(part);
      var found =
          sql.fetch(
              "select id from admissions.customer where normalized_phone=? and school_id in ("
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
        showChannel ? row.get("channel_id", UUID.class) : null,
        row.get("version", Long.class));
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

  static String fingerprint(String... values) {
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
    audit(scope.tenantId(), scope.accountId(), school, id, request, action, duplicate);
  }

  private void audit(
      Actor actor, UUID school, UUID id, UUID request, String action, boolean duplicate) {
    audit(actor.tenantId(), actor.accountId(), school, id, request, action, duplicate);
  }

  private void audit(
      UUID tenantId,
      UUID actorId,
      UUID school,
      UUID id,
      UUID request,
      String action,
      boolean duplicate) {
    sql.execute(
        "insert into audit.customer_event(tenant_id,actor_id,school_id,customer_id,request_id,action,duplicate_confirmed,trace_id) values (?,?,?,?,?,?,?,?)",
        tenantId,
        actorId,
        school,
        id,
        request,
        action,
        duplicate,
        RequestTrace.id());
  }
}
