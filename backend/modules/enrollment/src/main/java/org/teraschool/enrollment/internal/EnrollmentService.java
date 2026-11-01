package org.teraschool.enrollment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.enrollment.api.*;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.product.api.PriceAccess;
import org.teraschool.product.api.PriceSnapshot;

/**
 * 报名履约的完整用例。
 *
 * <p>报名成立时从价格快照冻结商品名称、单价、课时与结算模式，并同时生成原始应收；之后改价、下架都不会改写 这条报名。审核是独立动作与独立权限，不能靠修改接口任意设置状态。
 */
@Service
public class EnrollmentService implements EnrollmentQueries, EnrollmentCommands, EnrollmentAccess {
  private static final java.util.regex.Pattern DECIMAL =
      java.util.regex.Pattern.compile("\\d{1,12}(\\.\\d{1,2})?");
  private static final Set<String> STATUS = Set.of("PENDING_AUDIT", "ACTIVE");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final StudentAccess students;
  private final PriceAccess prices;

  public EnrollmentService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      StudentAccess students,
      PriceAccess prices) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.students = students;
    this.prices = prices;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public EnrollmentCapabilities capabilities() {
    var scope = actors.requireResourceScope("ENROLLMENT");
    boolean canApprove = hasAction("ENROLLMENT_APPROVE");
    if (scope.readableSchoolIds().isEmpty())
      return new EnrollmentCapabilities(true, false, canApprove, List.of());
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
                          new EnrollmentCapabilities.SchoolOption(
                              key, names.get(key), scope.maintainableSchoolIds().contains(key)))
                  .toList();
          return new EnrollmentCapabilities(
              true,
              options.stream().anyMatch(EnrollmentCapabilities.SchoolOption::maintainable),
              canApprove,
              options);
        });
  }

  @Override
  public EnrollmentPage list(UUID schoolId, String status, int page, int size) {
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页超出范围");
    if (status != null && !status.isBlank() && !STATUS.contains(status))
      throw new IllegalArgumentException("状态无效");
    var scope = actors.requireResourceScope("ENROLLMENT");
    requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var filter = status == null || status.isBlank() ? "" : " and e.status=?";
          var args = new ArrayList<Object>();
          args.add(schoolId);
          if (!filter.isEmpty()) args.add(status);
          var countArgs = new ArrayList<>(args);
          long total =
              sql.fetchOne(
                      "select count(*) from enrollment.enrollment e where e.school_id=?" + filter,
                      countArgs.toArray())
                  .get(0, Long.class);
          args.add(size);
          args.add(page * size);
          var rows =
              sql.fetch(
                  "select e.id,e.school_id,e.student_id,e.product_id,e.price_version_id,"
                      + "e.curriculum_name,e.product_name,e.price,e.hours,e.service_fee,"
                      + "e.settlement_mode,e.status,e.version,e.created_at,"
                      + "(select coalesce(sum(c.amount),0) from enrollment.charge c where c.enrollment_id=e.id)"
                      + " as net_amount"
                      + " from enrollment.enrollment e where e.school_id=?"
                      + filter
                      + " order by e.created_at desc, e.id limit ? offset ?",
                  args.toArray());
          var names = students.names(rows.map(row -> row.get("student_id", UUID.class)));
          return new EnrollmentPage(
              rows.map(row -> view(row, names.get(row.get("student_id", UUID.class)))),
              page,
              size,
              total);
        });
  }

  @Override
  public EnrollmentView detail(UUID id) {
    if (id == null) throw new IllegalArgumentException("需要报名");
    var scope = actors.requireResourceScope("ENROLLMENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = requireEnrollment(scope, id);
          var student = students.require(row.get("student_id", UUID.class));
          return view(row, student.name());
        });
  }

  @Override
  public EnrollmentRef require(UUID enrollmentId) {
    if (enrollmentId == null) throw new IllegalArgumentException("需要报名");
    var row =
        sql.fetchOne(
            "select id,school_id,student_id,status,price,hours,settlement_mode"
                + " from enrollment.enrollment where id=?",
            enrollmentId);
    if (row == null) throw new AccessDeniedException("报名不存在或不可访问");
    return new EnrollmentRef(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("student_id", UUID.class),
        row.get("status", String.class),
        netAmount(enrollmentId),
        row.get("price", java.math.BigDecimal.class),
        row.get("hours", java.math.BigDecimal.class),
        row.get("settlement_mode", Integer.class),
        cancelledHours(enrollmentId));
  }

  // ---------------------------------------------------------------- 写入

  @Override
  public EnrollmentView create(CreateEnrollment command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createWithTrace(command);
    }
  }

  private EnrollmentView createWithTrace(CreateEnrollment command) {
    if (command == null
        || command.requestId() == null
        || command.studentId() == null
        || command.priceVersionId() == null) throw new IllegalArgumentException("需要请求身份、学员与价格版本");
    var on = businessDate(command.enrolledOn());
    var scope = actors.requireResourceScope("ENROLLMENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("ENROLLMENT");
          var snapshot = prices.require(command.priceVersionId());
          var student = students.require(command.studentId());
          var schoolId = snapshot.schoolId();
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校的报名登记权限");
          if (!schoolId.equals(student.schoolId())) throw new AccessDeniedException("学员档案不属于该校");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能登记报名");
          requireSellable(snapshot, on);
          var fingerprint =
              fingerprint(
                  command.studentId().toString(),
                  command.priceVersionId().toString(),
                  on.toString());
          var previous = previousRequest(scope, command.requestId(), fingerprint);
          if (previous != null) return view(requireEnrollment(verified, previous), student.name());
          var id = UUID.randomUUID();
          sql.execute(
              "insert into enrollment.enrollment(id,tenant_id,school_id,student_id,product_id,"
                  + "price_version_id,curriculum_name,product_name,price,hours,service_fee,settlement_mode)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              scope.tenantId(),
              schoolId,
              command.studentId(),
              snapshot.productId(),
              snapshot.priceVersionId(),
              snapshot.curriculumName(),
              snapshot.productName(),
              new BigDecimal(snapshot.price()),
              new BigDecimal(snapshot.hours()),
              new BigDecimal(snapshot.serviceFee()),
              snapshot.settlementMode());
          sql.execute(
              "insert into enrollment.charge(tenant_id,enrollment_id,kind,amount,reason)"
                  + " values (?,?,'ORIGINAL',?,'报名原始约定')",
              scope.tenantId(),
              id,
              new BigDecimal(snapshot.price()));
          rememberRequest(scope, command.requestId(), id, fingerprint);
          audit(scope, "ENROLLMENT_CREATED", id, schoolId, "");
          return view(requireEnrollment(verified, id), student.name());
        });
  }

  @Override
  public EnrollmentView approve(UUID id, EnrollmentVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return approveWithTrace(id, command);
    }
  }

  private EnrollmentView approveWithTrace(UUID id, EnrollmentVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要报名和版本");
    var approve = actors.requireAction("ENROLLMENT_APPROVE");
    return tx.execute(
        approve.tenantId(),
        () -> {
          schools.lockTenant(approve.tenantId());
          var verified = actors.requireActionInTransaction("ENROLLMENT_APPROVE");
          var row = requireEnrollmentByTenant(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的报名审核权限");
          if (!"PENDING_AUDIT".equals(row.get("status", String.class)))
            throw new EnrollmentConflict("ENROLLMENT_NOT_PENDING");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new EnrollmentConflict("VERSION_CONFLICT");
          int updated =
              sql.execute(
                  "update enrollment.enrollment set status='ACTIVE',approved_at=CURRENT_TIMESTAMP,"
                      + "approved_by=?,version=version+1 where id=? and version=? and status='PENDING_AUDIT'",
                  approve.accountId(),
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new EnrollmentConflict("VERSION_CONFLICT");
          audit(approve, "ENROLLMENT_APPROVED", id, schoolId, "status");
          var student = students.require(row.get("student_id", UUID.class));
          return view(requireEnrollmentByTenant(id), student.name());
        });
  }

  // ---------------------------------------------------------------- 退课时

  @Override
  public CancellationView cancelHours(UUID id, CancelHours command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return cancelHoursWithTrace(id, command);
    }
  }

  /**
   * 记录本次退课时并追加应收减项。
   *
   * <p>金额按报名冻结快照计算：单价 = 价格 ÷ 购买课时（6 位），本次减项 = 单价 × 本次退课时（2 位）。 这里只校验不超过「购买课时 −
   * 已退课时」；已消耗课时由编排用例先行校验。
   */
  private CancellationView cancelHoursWithTrace(UUID id, CancelHours command) {
    if (id == null || command == null) throw new IllegalArgumentException("需要报名和退课时");
    var hours = positiveHours(command.hours());
    var reason = text(command.reason(), 200, "退课时原因");
    var refund = actors.requireAction("REFUND_APPLY");
    return tx.execute(
        refund.tenantId(),
        () -> {
          schools.lockTenant(refund.tenantId());
          var verified = actors.requireActionInTransaction("REFUND_APPLY");
          var row = requireEnrollmentForUpdate(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的退课时权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能退课时");
          if (!"ACTIVE".equals(row.get("status", String.class)))
            throw new EnrollmentConflict("ENROLLMENT_NOT_ACTIVE");
          var price = row.get("price", BigDecimal.class);
          var purchased = row.get("hours", BigDecimal.class);
          var cancelled = cancelledHours(id);
          if (hours.compareTo(purchased.subtract(cancelled)) > 0)
            throw new EnrollmentConflict("HOURS_EXCEED_REMAINING");
          var unit = price.divide(purchased, 6, RoundingMode.HALF_UP);
          var amount = unit.multiply(hours).setScale(2, RoundingMode.HALF_UP);
          var cancellationId = UUID.randomUUID();
          sql.execute(
              "insert into enrollment.hours_cancellation(id,tenant_id,enrollment_id,hours,amount,reason)"
                  + " values (?,?,?,?,?,?)",
              cancellationId,
              refund.tenantId(),
              id,
              hours,
              amount,
              reason);
          sql.execute(
              "insert into enrollment.charge(tenant_id,enrollment_id,kind,amount,reason)"
                  + " values (?,?,'ADJUSTMENT',?,'退课时减项')",
              refund.tenantId(),
              id,
              amount.negate());
          audit(
              refund,
              "HOURS_CANCELLED",
              id,
              schoolId,
              hours.toPlainString() + "课时/" + amount.toPlainString());
          return new CancellationView(
              cancellationId,
              id,
              hours(hours),
              money(amount),
              money(netAmount(id)),
              hours(cancelled.add(hours)));
        });
  }

  private Record requireEnrollmentForUpdate(UUID id) {
    var row =
        sql.fetchOne(
            "select id,school_id,student_id,product_id,price_version_id,curriculum_name,product_name,"
                + "price,hours,service_fee,settlement_mode,status,version,created_at from enrollment.enrollment"
                + " where id=? for update",
            id);
    if (row == null) throw new AccessDeniedException("报名不存在或不可访问");
    return row;
  }

  private BigDecimal cancelledHours(UUID enrollmentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(hours),0) as total from enrollment.hours_cancellation where enrollment_id=?",
                enrollmentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal positiveHours(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("退课时必填");
    var text = value.strip();
    if (!DECIMAL.matcher(text).matches()) throw new IllegalArgumentException("退课时须为最多两位小数的十进制数");
    var hours = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    if (hours.signum() <= 0) throw new IllegalArgumentException("退课时必须大于零");
    return hours;
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private String hours(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  // ---------------------------------------------------------------- 内部

  private boolean hasAction(String action) {
    try {
      return !actors.requireAction(action).schools().isEmpty();
    } catch (AccessDeniedException denied) {
      return false;
    }
  }

  private void requireSellable(PriceSnapshot snapshot, LocalDate on) {
    if (!"PUBLISHED".equals(snapshot.status()))
      throw new EnrollmentConflict("PRICE_VERSION_NOT_SELLABLE");
    var from = LocalDate.parse(snapshot.effectiveFrom());
    if (on.isBefore(from)) throw new EnrollmentConflict("PRICE_VERSION_NOT_SELLABLE");
    if (snapshot.effectiveTo() != null && on.isAfter(LocalDate.parse(snapshot.effectiveTo())))
      throw new EnrollmentConflict("PRICE_VERSION_NOT_SELLABLE");
  }

  private void requireReadable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的报名读取权限");
  }

  private Record requireEnrollment(ResourceScope scope, UUID id) {
    var row = requireEnrollmentByTenant(id);
    if (!scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("报名不存在或不可访问");
    return row;
  }

  private Record requireEnrollmentByTenant(UUID id) {
    var row =
        sql.fetchOne(
            "select e.id,e.school_id,e.student_id,e.product_id,e.price_version_id,e.curriculum_name,"
                + "e.product_name,e.price,e.hours,e.service_fee,e.settlement_mode,e.status,e.version,"
                + "e.created_at,"
                + "(select coalesce(sum(c.amount),0) from enrollment.charge c where c.enrollment_id=e.id)"
                + " as net_amount from enrollment.enrollment e where e.id=?",
            id);
    if (row == null) throw new AccessDeniedException("报名不存在或不可访问");
    return row;
  }

  private BigDecimal netAmount(UUID enrollmentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from enrollment.charge where enrollment_id=?",
                enrollmentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private EnrollmentView view(Record row, String studentName) {
    return new EnrollmentView(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        row.get("student_id", UUID.class),
        studentName,
        row.get("product_id", UUID.class),
        row.get("price_version_id", UUID.class),
        row.get("curriculum_name", String.class),
        row.get("product_name", String.class),
        money(row.get("price", BigDecimal.class)),
        money(row.get("hours", BigDecimal.class)),
        money(row.get("service_fee", BigDecimal.class)),
        row.get("settlement_mode", Integer.class),
        row.get("status", String.class),
        money(row.get("net_amount", BigDecimal.class)),
        row.get("version", Long.class),
        row.get("created_at", java.time.OffsetDateTime.class) == null
            ? null
            : row.get("created_at", java.time.OffsetDateTime.class).toInstant().toString());
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private LocalDate businessDate(String value) {
    if (value == null || value.isBlank()) return LocalDate.now();
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("报名日期须为 YYYY-MM-DD");
    }
  }

  private UUID previousRequest(ResourceScope scope, UUID requestId, String fingerprint) {
    var row =
        sql.fetchOne(
            "select resource_id,fingerprint from enrollment.creation_request"
                + " where tenant_id=? and actor_id=? and request_id=?",
            scope.tenantId(),
            scope.accountId(),
            requestId);
    if (row == null) return null;
    if (!fingerprint.equals(row.get("fingerprint", String.class)))
      throw new EnrollmentConflict("IDEMPOTENCY_MISMATCH");
    return row.get("resource_id", UUID.class);
  }

  private void rememberRequest(
      ResourceScope scope, UUID requestId, UUID resource, String fingerprint) {
    sql.execute(
        "insert into enrollment.creation_request(tenant_id,actor_id,request_id,kind,resource_id,fingerprint)"
            + " values (?,?,?,'ENROLLMENT',?,?)",
        scope.tenantId(),
        scope.accountId(),
        requestId,
        resource,
        fingerprint);
  }

  private String fingerprint(String... parts) {
    var text = String.join("\u001f", parts);
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      var hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
      return hex.toString();
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("无法计算请求摘要", unavailable);
    }
  }

  private void audit(
      ResourceScope scope, String action, UUID resource, UUID school, String fields) {
    audit(scope.reader(), action, resource, school, fields);
  }

  private void audit(Actor actor, String action, UUID resource, UUID school, String fields) {
    sql.execute(
        "insert into audit.enrollment_event(tenant_id,actor_id,action,resource_id,school_id,changed_fields,trace_id)"
            + " values (?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        fields,
        RequestTrace.id());
  }

  /** 供 SQL 层复用的唯一约束判断（当前仅用于明确记录，不吞掉其他错误）。 */
  private boolean uniqueViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sqlError && "23505".equals(sqlError.getSQLState()))
        return true;
    return false;
  }
}
