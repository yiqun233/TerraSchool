package org.teraschool.approval.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.approval.api.*;
import org.teraschool.enrollment.api.DiscountApproval;
import org.teraschool.enrollment.api.EnrollmentAccess;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;

/**
 * 审批策略与审批单。
 *
 * <p>退费类动作在策略要求时必须持有已批准且金额匹配的审批单，审批单只能被消耗一次；申请人不能审批自己的单据。缺少或停用策略时拒绝执行，由学校明确配置规则。
 */
@Service
public class ApprovalService
    implements ApprovalQueries, ApprovalCommands, ApprovalGate, DiscountApproval {
  private static final Set<String> ACTIONS =
      Set.of(
          "REFUND_HOURS",
          "CASH_REFUND",
          "ENROLLMENT_DISCOUNT",
          "SERVICE_FEE_REFUND",
          "SERVICE_FEE_REVERSAL");
  private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");
  private static final java.util.regex.Pattern DECIMAL =
      java.util.regex.Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final EnrollmentAccess enrollments;

  public ApprovalService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      EnrollmentAccess enrollments) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.enrollments = enrollments;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public ApprovalCapabilities capabilities() {
    var scope = actors.requireResourceScope("APPROVAL");
    var manage = schoolsOf("APPROVAL_MANAGE");
    var decide = schoolsOf("APPROVAL_DECIDE");
    if (scope.readableSchoolIds().isEmpty())
      return new ApprovalCapabilities(true, false, false, List.of());
    return tx.execute(
        scope.tenantId(),
        () -> {
          var names = schools.names(scope.readableSchoolIds());
          var options =
              scope.readableSchoolIds().stream()
                  .filter(names::containsKey)
                  .sorted(
                      Comparator.comparing((UUID key) -> names.get(key)).thenComparing(key -> key))
                  .map(
                      key ->
                          new ApprovalCapabilities.ApprovalSchoolOption(
                              key, names.get(key), manage.contains(key), decide.contains(key)))
                  .toList();
          return new ApprovalCapabilities(
              true,
              options.stream().anyMatch(ApprovalCapabilities.ApprovalSchoolOption::canManage),
              options.stream().anyMatch(ApprovalCapabilities.ApprovalSchoolOption::canDecide),
              options);
        });
  }

  @Override
  public List<ApprovalPolicyView> policies(UUID schoolId) {
    var scope = actors.requireResourceScope("APPROVAL");
    requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () ->
            sql.fetch(
                    "select id,school_id,action,free_below,active,version from approval.policy"
                        + " where school_id=? order by action",
                    schoolId)
                .map(this::policyView));
  }

  @Override
  public List<ApprovalRequestView> requests(UUID schoolId, String status) {
    var scope = actors.requireResourceScope("APPROVAL");
    requireReadable(scope, schoolId);
    var filter = status == null || status.isBlank() ? "" : " and status=?";
    return tx.execute(
        scope.tenantId(),
        () -> {
          var args = new ArrayList<Object>();
          args.add(schoolId);
          if (!filter.isEmpty()) args.add(status);
          return sql.fetch(
                  "select id,school_id,action,enrollment_id,amount,reason,status,requested_by,"
                      + "decided_by,decision_note,used_at,version,created_at from approval.request"
                      + " where school_id=?"
                      + filter
                      + " order by created_at desc, id limit 200",
                  args.toArray())
              .map(this::requestView);
        });
  }

  // ---------------------------------------------------------------- 策略

  @Override
  public ApprovalPolicyView upsertPolicy(UpsertPolicy command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return upsertPolicyWithTrace(command);
    }
  }

  private ApprovalPolicyView upsertPolicyWithTrace(UpsertPolicy command) {
    if (command == null || command.schoolId() == null) throw new IllegalArgumentException("需要学校");
    var action = action(command.action());
    var freeBelow = amount(command.freeBelow(), "免批阈值", true);
    var manage = actors.requireAction("APPROVAL_MANAGE");
    return tx.execute(
        manage.tenantId(),
        () -> {
          schools.lockTenant(manage.tenantId());
          var verified = actors.requireActionInTransaction("APPROVAL_MANAGE");
          if (!verified.schools().contains(command.schoolId()))
            throw new AccessDeniedException("无该学校的审批策略维护权限");
          if (!schools.require(command.schoolId()).active())
            throw new AccessDeniedException("学校已停用，不能修改审批策略");
          var existing =
              sql.fetchOne(
                  "select id,version,free_below,active from approval.policy where school_id=? and action=? for update",
                  command.schoolId(),
                  action);
          var active = command.active() == null || command.active();
          if (existing == null) {
            if (command.expectedVersion() != null && command.expectedVersion() != 0)
              throw new ApprovalConflict("VERSION_CONFLICT");
            var id = UUID.randomUUID();
            sql.execute(
                "insert into approval.policy(id,tenant_id,school_id,action,free_below,active)"
                    + " values (?,?,?,?,?,?)",
                id,
                manage.tenantId(),
                command.schoolId(),
                action,
                freeBelow,
                active);
            audit(manage, "POLICY_UPDATED", id, command.schoolId(), freeBelow, "create");
            return policyView(requirePolicy(id));
          }
          if (command.expectedVersion() != null
              && !existing.get("version", Long.class).equals(command.expectedVersion()))
            throw new ApprovalConflict("VERSION_CONFLICT");
          var updated =
              sql.execute(
                  "update approval.policy set free_below=?,active=?,version=version+1,"
                      + "updated_at=CURRENT_TIMESTAMP where id=? and version=?",
                  freeBelow,
                  active,
                  existing.get("id", UUID.class),
                  command.expectedVersion() == null
                      ? existing.get("version", Long.class)
                      : command.expectedVersion());
          if (updated != 1) throw new ApprovalConflict("VERSION_CONFLICT");
          audit(
              manage,
              "POLICY_UPDATED",
              existing.get("id", UUID.class),
              command.schoolId(),
              freeBelow,
              "free_below,active");
          return policyView(requirePolicy(existing.get("id", UUID.class)));
        });
  }

  // ---------------------------------------------------------------- 审批单

  @Override
  public ApprovalRequestView request(CreateApprovalRequest command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return requestWithTrace(command);
    }
  }

  private ApprovalRequestView requestWithTrace(CreateApprovalRequest command) {
    if (command == null || command.enrollmentId() == null)
      throw new IllegalArgumentException("需要报名");
    var action = action(command.action());
    var amount = amount(command.amount(), "申请金额", false);
    var reason = text(command.reason(), 200, "申请原因");
    var permission = "ENROLLMENT_DISCOUNT".equals(action) ? "ENROLLMENT_DISCOUNT" : "REFUND_APPLY";
    var apply = actors.requireAction(permission);
    return tx.execute(
        apply.tenantId(),
        () -> {
          schools.lockTenant(apply.tenantId());
          var verified = actors.requireActionInTransaction(permission);
          var enrollment = enrollments.require(command.enrollmentId());
          var schoolId = enrollment.schoolId();
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的业务审批申请权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能申请审批");
          var pending =
              sql.fetchOne(
                  "select id from approval.request where enrollment_id=? and action=? and status='PENDING'",
                  command.enrollmentId(),
                  action);
          if (pending != null) throw new ApprovalConflict("APPROVAL_ALREADY_PENDING");
          var id = UUID.randomUUID();
          sql.execute(
              "insert into approval.request(id,tenant_id,school_id,action,enrollment_id,amount,reason,"
                  + "requested_by) values (?,?,?,?,?,?,?,?)",
              id,
              apply.tenantId(),
              schoolId,
              action,
              command.enrollmentId(),
              amount,
              reason,
              apply.accountId());
          audit(apply, "REQUESTED", id, schoolId, amount, action);
          return requestView(requireRequest(id));
        });
  }

  @Override
  public ApprovalRequestView decide(UUID id, DecideApproval command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return decideWithTrace(id, command);
    }
  }

  private ApprovalRequestView decideWithTrace(UUID id, DecideApproval command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要审批单和版本");
    var decision =
        command.decision() == null ? null : command.decision().strip().toUpperCase(Locale.ROOT);
    if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("审批决定无效");
    var note = text(command.note(), 200, "审批说明");
    var decide = actors.requireAction("APPROVAL_DECIDE");
    return tx.execute(
        decide.tenantId(),
        () -> {
          schools.lockTenant(decide.tenantId());
          var verified = actors.requireActionInTransaction("APPROVAL_DECIDE");
          var row = requireRequestForUpdate(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的审批权限");
          if (!"PENDING".equals(row.get("status", String.class)))
            throw new ApprovalConflict("APPROVAL_NOT_PENDING");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new ApprovalConflict("VERSION_CONFLICT");
          if (decide.accountId().equals(row.get("requested_by", UUID.class)))
            throw new ApprovalConflict("APPROVAL_SELF_DECISION");
          var updated =
              sql.execute(
                  "update approval.request set status=?,decided_by=?,decision_note=?,"
                      + "decided_at=CURRENT_TIMESTAMP,version=version+1"
                      + " where id=? and version=? and status='PENDING'",
                  decision,
                  decide.accountId(),
                  note,
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new ApprovalConflict("VERSION_CONFLICT");
          audit(
              decide,
              decision.equals("APPROVED") ? "APPROVED" : "REJECTED",
              id,
              schoolId,
              row.get("amount", BigDecimal.class),
              "status");
          return requestView(requireRequest(id));
        });
  }

  // ---------------------------------------------------------------- 闸门

  @Override
  public void consume(
      String action, UUID schoolId, UUID enrollmentId, BigDecimal amount, UUID approvalId) {
    if (action == null || schoolId == null || enrollmentId == null || amount == null)
      throw new IllegalArgumentException("审批校验缺少参数");
    var policy =
        sql.fetchOne(
            "select free_below,active from approval.policy where school_id=? and action=?",
            schoolId,
            action);
    // 不将漏配或停用误解释为免审批；显式配置阈值后才允许免批或审批执行。
    if (policy == null || !policy.get("active", Boolean.class))
      throw new ApprovalConflict("APPROVAL_POLICY_NOT_CONFIGURED");
    if (amount.compareTo(policy.get("free_below", BigDecimal.class)) < 0) return;
    if (approvalId == null) throw new ApprovalConflict("APPROVAL_REQUIRED");
    var row = requireRequestForUpdate(approvalId);
    if (!"APPROVED".equals(row.get("status", String.class)))
      throw new ApprovalConflict("APPROVAL_NOT_APPROVED");
    if (!action.equals(row.get("action", String.class))
        || !enrollmentId.equals(row.get("enrollment_id", UUID.class))
        || row.get("amount", BigDecimal.class).compareTo(amount) != 0)
      throw new ApprovalConflict("APPROVAL_MISMATCH");
    if (row.get("used_at", java.time.OffsetDateTime.class) != null)
      throw new ApprovalConflict("APPROVAL_ALREADY_USED");
    int updated =
        sql.execute(
            "update approval.request set used_at=CURRENT_TIMESTAMP where id=? and used_at is null",
            approvalId);
    if (updated != 1) throw new ApprovalConflict("APPROVAL_ALREADY_USED");
    // 消耗者是当前业务操作的执行人，不是申请人。
    audit(
        actors.requireIdentityInTransaction(),
        "CONSUMED",
        approvalId,
        schoolId,
        row.get("amount", BigDecimal.class),
        action);
  }

  @Override
  public void consume(UUID schoolId, UUID enrollmentId, BigDecimal amount, UUID approvalId) {
    consume("ENROLLMENT_DISCOUNT", schoolId, enrollmentId, amount, approvalId);
  }

  // ---------------------------------------------------------------- 内部

  private Set<UUID> schoolsOf(String action) {
    try {
      return actors.requireAction(action).schools();
    } catch (AccessDeniedException denied) {
      return Set.of();
    }
  }

  private void requireReadable(org.teraschool.identity.api.ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的审批读取权限");
  }

  private Record requirePolicy(UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,action,free_below,active,version from approval.policy where id=?",
            id);
    if (row == null) throw new AccessDeniedException("审批策略不存在或不可访问");
    return row;
  }

  private Record requireRequest(UUID id) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,school_id,action,enrollment_id,amount,reason,status,requested_by,"
                + "decided_by,decision_note,used_at,version,created_at from approval.request where id=?",
            id);
    if (row == null) throw new AccessDeniedException("审批单不存在或不可访问");
    return row;
  }

  private Record requireRequestForUpdate(UUID id) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,school_id,action,enrollment_id,amount,reason,status,requested_by,"
                + "decided_by,decision_note,used_at,version,created_at from approval.request"
                + " where id=? for update",
            id);
    if (row == null) throw new AccessDeniedException("审批单不存在或不可访问");
    return row;
  }

  private ApprovalPolicyView policyView(Record row) {
    return new ApprovalPolicyView(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("action", String.class),
        money(row.get("free_below", BigDecimal.class)),
        row.get("active", Boolean.class),
        row.get("version", Long.class));
  }

  private ApprovalRequestView requestView(Record row) {
    var usedAt = row.get("used_at", java.time.OffsetDateTime.class);
    return new ApprovalRequestView(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("action", String.class),
        row.get("enrollment_id", UUID.class),
        money(row.get("amount", BigDecimal.class)),
        row.get("reason", String.class),
        row.get("status", String.class),
        row.get("requested_by", UUID.class),
        row.get("decided_by", UUID.class),
        row.get("decision_note", String.class),
        usedAt == null ? null : usedAt.toInstant().toString(),
        row.get("version", Long.class),
        row.get("created_at", java.time.OffsetDateTime.class) == null
            ? null
            : row.get("created_at", java.time.OffsetDateTime.class).toInstant().toString());
  }

  private String action(String value) {
    var text = value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    if (!ACTIONS.contains(text)) throw new IllegalArgumentException("审批动作无效");
    return text;
  }

  private BigDecimal amount(String value, String label, boolean allowZero) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    var text = value.strip();
    if (!DECIMAL.matcher(text).matches())
      throw new IllegalArgumentException(label + "须为最多两位小数的十进制数");
    var amount = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    if (amount.signum() < 0 || (!allowZero && amount.signum() == 0))
      throw new IllegalArgumentException(label + "必须大于零");
    return amount;
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private void audit(
      Actor actor, String action, UUID resource, UUID school, BigDecimal amount, String fields) {
    sql.execute(
        "insert into audit.approval_event(tenant_id,actor_id,action,resource_id,school_id,amount,"
            + "changed_fields,trace_id) values (?,?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        amount,
        fields,
        RequestTrace.id());
  }
}
