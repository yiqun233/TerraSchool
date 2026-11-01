package org.teraschool.organization.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.*;
import org.teraschool.organization.api.OrganizationConflict;
import org.teraschool.organization.api.PersonnelAttachments.*;
import org.teraschool.platform.api.*;
import tools.jackson.databind.json.JsonMapper;

@Service
class PersonnelAttachmentService {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolWriteGuard guard;
  private final PrivateObjectStorage storage;
  private final JsonMapper json = JsonMapper.builder().build();

  PersonnelAttachmentService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolWriteGuard guard,
      PrivateObjectStorage storage) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.guard = guard;
    this.storage = storage;
  }

  private DSLContext privateSql() {
    var settings = (org.jooq.conf.Settings) sql.settings().clone();
    return org.jooq.impl.DSL.using(sql.configuration().derive(settings.withExecuteLogging(false)));
  }

  private UUID school(UUID employee, Actor actor, boolean write) {
    var row = sql.fetchOne("select school_id from organization.employee where id=?", employee);
    if (row == null) throw new AccessDeniedException("档案不可访问");
    UUID school = row.get("school_id", UUID.class);
    if (!actor.schools().contains(school)
        || !actors.requireResourceInTransaction("EMPLOYEE", write).schools().contains(school))
      throw new AccessDeniedException("档案不可访问");
    return school;
  }

  private String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private AttachmentView view(org.jooq.Record row) {
    return new AttachmentView(
        row.get("id", UUID.class),
        row.get("employee_id", UUID.class),
        row.get("original_name", String.class),
        row.get("content_size", Long.class),
        row.get("version", Long.class));
  }

  private void audit(Actor actor, UUID school, UUID attachment, String operation) {
    sql.execute(
        "insert into audit.resource_event(tenant_id,actor_id,school_id,resource_id,kind,action,before_json,after_json,trace_id) values (?,?,?,?,'PERSONNEL_FILE',?,null,?::jsonb,?)",
        actor.tenantId(),
        actor.accountId(),
        school,
        attachment,
        operation,
        json.writeValueAsString(Map.of("operation", operation)),
        RequestTrace.id());
  }

  java.util.List<AttachmentView> list(UUID employee) {
    if (employee == null) throw new IllegalArgumentException("需要员工身份");
    var actor = actors.requireResource("PERSONNEL", false);
    return tx.execute(
        actor.tenantId(),
        () -> {
          var current = actors.requireResourceInTransaction("PERSONNEL", false);
          school(employee, current, false);
          return privateSql()
              .fetch(
                  "select id,employee_id,original_name,content_size,version from organization.personnel_attachment where employee_id=? and active order by created_at,id limit 100",
                  employee)
              .map(this::view);
        });
  }

  AttachmentView create(UUID employee, UploadAttachment input) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) throw new IllegalStateException("附件上传不能嵌入外层事务");
    if (employee == null
        || input == null
        || input.requestId() == null
        || input.name() == null
        || input.name().isBlank()
        || input.name().length() > 200
        || input.name().contains("/")
        || input.name().contains("\\")
        || input.name().strip().equals(".")
        || input.name().strip().equals("..")
        || input.name().codePoints().anyMatch(Character::isISOControl)
        || input.content() == null
        || input.content().length == 0
        || input.content().length > 5242880)
      throw new IllegalArgumentException("附件需有有效名称、请求身份及不超过5MB的内容");
    var actor = actors.requireResource("PERSONNEL", true);
    if (!storage.configured()) throw new OrganizationConflict("STORAGE_NOT_CONFIGURED");
    var name = input.name().strip();
    var digest = digest(input.content());
    String key =
        "teraschool/personnel/"
            + actor.tenantId()
            + "/"
            + employee
            + "/"
            + actor.accountId()
            + "/"
            + input.requestId();
    // 预留必须先提交，崩溃或业务事务回滚后仍可定位对象进行重试/清理。
    AttachmentView completed =
        tx.execute(
            actor.tenantId(),
            () -> {
              guard.lock(actor.tenantId());
              var current = actors.requireResourceInTransaction("PERSONNEL", true);
              UUID school = school(employee, current, true);
              guard.requireActive(school);
              var completedRow = committed(actor, employee, input.requestId(), name, digest);
              if (completedRow != null) return completedRow;
              var pending =
                  privateSql()
                      .fetchOne(
                          "select employee_id,original_name,sha256 from organization.personnel_upload_pending where tenant_id=? and actor_id=? and request_id=?",
                          actor.tenantId(),
                          actor.accountId(),
                          input.requestId());
              if (pending != null
                  && (!employee.equals(pending.get("employee_id", UUID.class))
                      || !name.equals(pending.get("original_name", String.class))
                      || !digest.equals(pending.get("sha256", String.class))))
                throw new OrganizationConflict("IDEMPOTENCY_MISMATCH");
              if (pending == null)
                privateSql()
                    .execute(
                        "insert into organization.personnel_upload_pending(tenant_id,actor_id,request_id,employee_id,object_key,original_name,sha256) values (?,?,?,?,?,?,?)",
                        actor.tenantId(),
                        actor.accountId(),
                        input.requestId(),
                        employee,
                        key,
                        name,
                        digest);
              else
                sql.execute(
                    "update organization.personnel_upload_pending set touched_at=current_timestamp where tenant_id=? and actor_id=? and request_id=?",
                    actor.tenantId(),
                    actor.accountId(),
                    input.requestId());
              return null;
            });
    if (completed != null) return completed;
    return tx.execute(
        actor.tenantId(),
        () -> {
          guard.lock(actor.tenantId());
          var current = actors.requireResourceInTransaction("PERSONNEL", true);
          UUID school = school(employee, current, true);
          guard.requireActive(school);
          var existing = committed(actor, employee, input.requestId(), name, digest);
          if (existing != null) {
            sql.execute(
                "delete from organization.personnel_upload_pending where tenant_id=? and actor_id=? and request_id=?",
                actor.tenantId(),
                actor.accountId(),
                input.requestId());
            return existing;
          }
          if (sql.fetchOne(
                  "select 1 from organization.personnel_upload_pending where tenant_id=? and actor_id=? and request_id=?",
                  actor.tenantId(),
                  actor.accountId(),
                  input.requestId())
              == null) throw new OrganizationConflict("UPLOAD_RESERVATION_EXPIRED");
          cleanupPending(actor.tenantId());
          if (sql.fetchOne(
                      "select count(*) from organization.personnel_attachment where employee_id=? and active",
                      employee)
                  .get(0, Long.class)
              >= 100) throw new OrganizationConflict("ATTACHMENT_LIMIT_REACHED");
          storage.put(key, input.content());
          var row =
              privateSql()
                  .fetchOne(
                      "insert into organization.personnel_attachment(tenant_id,school_id,employee_id,actor_id,request_id,object_key,original_name,content_size,sha256) values (?,?,?,?,?,?,?,?,?) returning id,employee_id,original_name,content_size,version",
                      actor.tenantId(),
                      school,
                      employee,
                      actor.accountId(),
                      input.requestId(),
                      key,
                      name,
                      input.content().length,
                      digest);
          audit(actor, school, row.get("id", UUID.class), "CREATE");
          sql.execute(
              "delete from organization.personnel_upload_pending where tenant_id=? and actor_id=? and request_id=?",
              actor.tenantId(),
              actor.accountId(),
              input.requestId());
          return view(row);
        });
  }

  private AttachmentView committed(
      Actor actor, UUID employee, UUID request, String name, String digest) {
    var row =
        privateSql()
            .fetchOne(
                "select id,employee_id,original_name,content_size,sha256,version,active from organization.personnel_attachment where tenant_id=? and actor_id=? and request_id=?",
                actor.tenantId(),
                actor.accountId(),
                request);
    if (row == null) return null;
    if (!employee.equals(row.get("employee_id", UUID.class))
        || !name.equals(row.get("original_name", String.class))
        || !digest.equals(row.get("sha256", String.class)))
      throw new OrganizationConflict("IDEMPOTENCY_MISMATCH");
    if (!row.get("active", Boolean.class)) throw new OrganizationConflict("ATTACHMENT_REMOVED");
    return view(row);
  }

  /** 机构锁内仅清理超过一天、没有持久附件引用的预留；失败保留预留下次重试。 */
  private void cleanupPending(UUID tenant) {
    var stale =
        sql.fetch(
            "select actor_id,request_id,object_key from organization.personnel_upload_pending p where tenant_id=? and touched_at < current_timestamp - interval '24 hours' and not exists (select 1 from organization.personnel_attachment a where a.object_key=p.object_key) order by touched_at limit 3",
            tenant);
    for (var row : stale) {
      String key = row.get("object_key", String.class);
      if (!key.startsWith("teraschool/personnel/" + tenant + "/"))
        throw new IllegalStateException("附件对象路径无效");
      storage.delete(key);
      sql.execute(
          "delete from organization.personnel_upload_pending where tenant_id=? and actor_id=? and request_id=?",
          tenant,
          row.get("actor_id", UUID.class),
          row.get("request_id", UUID.class));
    }
  }

  AttachmentContent read(UUID employee, UUID attachment) {
    if (employee == null || attachment == null) throw new IllegalArgumentException("需要员工和附件身份");
    var actor = actors.requireResource("PERSONNEL", false);
    if (!storage.configured()) throw new OrganizationConflict("STORAGE_NOT_CONFIGURED");
    return tx.execute(
        actor.tenantId(),
        () -> {
          var current = actors.requireResourceInTransaction("PERSONNEL", false);
          UUID school = school(employee, current, false);
          var row =
              privateSql()
                  .fetchOne(
                      "select id,object_key,original_name,content_size,sha256 from organization.personnel_attachment where employee_id=? and id=? and active",
                      employee,
                      attachment);
          if (row == null) throw new AccessDeniedException("附件不可访问");
          byte[] bytes = storage.get(row.get("object_key", String.class));
          if (bytes.length != row.get("content_size", Long.class)
              || !digest(bytes).equals(row.get("sha256", String.class)))
            throw new IllegalStateException("附件内容校验失败");
          audit(current, school, attachment, "READ");
          return new AttachmentContent(row.get("original_name", String.class), bytes);
        });
  }

  void remove(UUID employee, UUID attachment, RemoveAttachment input) {
    if (employee == null
        || attachment == null
        || input == null
        || input.expectedVersion() == null
        || input.expectedVersion() < 0) throw new IllegalArgumentException("需要有效附件身份和版本");
    var actor = actors.requireResource("PERSONNEL", true);
    tx.execute(
        actor.tenantId(),
        () -> {
          guard.lock(actor.tenantId());
          var current = actors.requireResourceInTransaction("PERSONNEL", true);
          UUID school = school(employee, current, true);
          guard.requireActive(school);
          var row =
              sql.fetchOne(
                  "select version,active from organization.personnel_attachment where employee_id=? and id=? for update",
                  employee,
                  attachment);
          if (row == null || !row.get("active", Boolean.class))
            throw new AccessDeniedException("附件不可访问");
          if (row.get("version", Long.class).longValue() != input.expectedVersion())
            throw new OrganizationConflict("VERSION_CONFLICT");
          sql.execute(
              "update organization.personnel_attachment set active=false,version=version+1 where id=? and version=?",
              attachment,
              input.expectedVersion());
          audit(actor, school, attachment, "REMOVE");
          return null;
        });
  }
}
