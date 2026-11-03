package org.teraschool.admissions.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.admissions.api.CustomerConflict;
import org.teraschool.admissions.api.CustomerFollowupCommands.FollowupCreate;
import org.teraschool.admissions.api.CustomerFollowupQueries;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/** 跟进内容可能含敏感信息，只有本人且有完整电话权限时可访问。 */
@Service
class CustomerFollowupService implements CustomerFollowupQueries {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final CustomerService customers;

  CustomerFollowupService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      CustomerService customers) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.customers = customers;
  }

  private UUID authorize(ResourceScope scope, UUID customerId, boolean write) {
    var customer = customers.ownCustomer(scope, customerId);
    var school = customer.get("school_id", UUID.class);
    if (!actors.requireActionInTransaction("CUSTOMER_PHONE_READ").schools().contains(school)
        || (write
            && (!scope.maintainableSchoolIds().contains(school)
                || !schools.require(school).active())))
      throw new AccessDeniedException("无客户跟进权限或学校已停用");
    return school;
  }

  @Override
  public FollowupPage list(UUID customerId, int page, int size) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new CustomerConflict("CUSTOMER_FOLLOWUP_REQUIRES_INDEPENDENT_TRANSACTION");
    if (customerId == null || page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("客户或分页无效");
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          var school = authorize(verified, customerId, false);
          sql.execute(
              "insert into audit.customer_event(tenant_id,actor_id,school_id,customer_id,action,trace_id) values (?,?,?,?,'FOLLOWUP_VIEWED',?)",
              verified.tenantId(),
              verified.accountId(),
              school,
              customerId,
              RequestTrace.id());
          var rows =
              sql.fetch(
                  "select id,customer_id,content,occurred_on,next_contact_on,created_at from admissions.customer_followup where customer_id=? and author_id=? order by created_at desc,id desc limit ? offset ?",
                  customerId,
                  verified.accountId(),
                  size + 1,
                  (long) page * size);
          return new FollowupPage(
              rows.stream().limit(size).map(this::view).toList(), page, size, rows.size() > size);
        });
  }

  Followup create(UUID customerId, FollowupCreate command) {
    if (customerId == null || command == null || command.requestId() == null)
      throw new IllegalArgumentException("需要客户和请求身份");
    var content = command.content() == null ? "" : command.content().strip();
    if (content.isEmpty()
        || content.length() > 1000
        || content
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
      throw new IllegalArgumentException("跟进内容必填且不超过 1000 字");
    var occurred = date(command.occurredOn(), false);
    var next = date(command.nextContactOn(), true);
    var fingerprint =
        CustomerService.fingerprint(
            customerId.toString(), content, occurred.toString(), Objects.toString(next, ""));
    var scope = actors.requireResourceScope("CUSTOMER");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("CUSTOMER");
          var school = authorize(verified, customerId, true);
          var previous =
              sql.fetchOne(
                  "select customer_id,followup_id,fingerprint from admissions.customer_followup_request where actor_id=? and request_id=?",
                  verified.accountId(),
                  command.requestId());
          if (previous != null) {
            if (!customerId.equals(previous.get("customer_id", UUID.class))
                || !fingerprint.equals(previous.get("fingerprint", String.class)))
              throw new CustomerConflict("IDEMPOTENCY_MISMATCH");
            return view(
                sql.fetchOne(
                    "select id,customer_id,content,occurred_on,next_contact_on,created_at from admissions.customer_followup where id=? and author_id=?",
                    previous.get("followup_id", UUID.class),
                    verified.accountId()));
          }
          var id = UUID.randomUUID();
          var row =
              sql.fetchOne(
                  "insert into admissions.customer_followup(id,tenant_id,school_id,customer_id,author_id,content,occurred_on,next_contact_on) values (?,?,?,?,?,?,?,?) returning id,customer_id,content,occurred_on,next_contact_on,created_at",
                  id,
                  verified.tenantId(),
                  school,
                  customerId,
                  verified.accountId(),
                  content,
                  occurred,
                  next);
          sql.execute(
              "insert into admissions.customer_followup_request(tenant_id,actor_id,request_id,customer_id,followup_id,fingerprint) values (?,?,?,?,?,?)",
              verified.tenantId(),
              verified.accountId(),
              command.requestId(),
              customerId,
              id,
              fingerprint);
          sql.execute(
              "insert into audit.customer_event(tenant_id,actor_id,school_id,customer_id,request_id,action,trace_id) values (?,?,?,?,?,'FOLLOWUP_ADDED',?)",
              verified.tenantId(),
              verified.accountId(),
              school,
              customerId,
              command.requestId(),
              RequestTrace.id());
          return view(row);
        });
  }

  private static LocalDate date(String input, boolean optional) {
    if (optional && (input == null || input.isBlank())) return null;
    try {
      if (input == null || !input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException("日期格式无效");
      var value = LocalDate.parse(input);
      if (value.getYear() < 1) throw new IllegalArgumentException("日期格式无效");
      return value;
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("日期格式无效");
    }
  }

  private Followup view(Record row) {
    var next = row.get("next_contact_on", LocalDate.class);
    return new Followup(
        row.get("id", UUID.class),
        row.get("customer_id", UUID.class),
        row.get("content", String.class),
        row.get("occurred_on", LocalDate.class).toString(),
        next == null ? null : next.toString(),
        row.get("created_at").toString());
  }
}
