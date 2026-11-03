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
                          new EnrollmentCapabilities.EnrollmentSchoolOption(
                              key, names.get(key), scope.maintainableSchoolIds().contains(key)))
                  .toList();
          return new EnrollmentCapabilities(
              true,
              options.stream()
                  .anyMatch(EnrollmentCapabilities.EnrollmentSchoolOption::maintainable),
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
            "select id,school_id,student_id,status,price,service_fee,hours,settlement_mode"
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
        row.get("service_fee", java.math.BigDecimal.class),
        discountAmount(enrollmentId),
        row.get("hours", java.math.BigDecimal.class),
        row.get("settlement_mode", Integer.class),
        cancelledHours(enrollmentId),
        courseRefs(enrollmentId));
  }

  private List<EnrollmentAccess.CourseRef> courseRefs(UUID enrollmentId) {
    return sql.fetch(
            "select curriculum_id,hours,amount,cancelled_hours,cancelled_amount"
                + " from enrollment.enrollment_course where enrollment_id=? order by created_at,id",
            enrollmentId)
        .map(
            row ->
                new EnrollmentAccess.CourseRef(
                    row.get("curriculum_id", UUID.class),
                    row.get("hours", BigDecimal.class),
                    row.get("amount", BigDecimal.class),
                    row.get("cancelled_hours", BigDecimal.class),
                    row.get("cancelled_amount", BigDecimal.class)));
  }

  @Override
  public void resolveSettlementMode(UUID enrollmentId, int resolvedMode) {
    if (resolvedMode != 1 && resolvedMode != 2)
      throw new IllegalArgumentException("自动模式只能解析为 1 或 2");
    sql.execute(
        "update enrollment.enrollment set settlement_mode=?,settlement_mode_resolved_at="
            + "CURRENT_TIMESTAMP where id=? and settlement_mode=0",
        resolvedMode,
        enrollmentId);
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
          // 应收 = 商品价格 + 服务费；服务费在价格快照里单列，不摊进课时单价。
          var receivable =
              new BigDecimal(snapshot.price()).add(new BigDecimal(snapshot.serviceFee()));
          sql.execute(
              "insert into enrollment.charge(tenant_id,enrollment_id,kind,amount,reason)"
                  + " values (?,?,'ORIGINAL',?,'报名原始约定（含服务费）')",
              scope.tenantId(),
              id,
              receivable);
          sql.execute(
              "update enrollment.enrollment set declared_settlement_mode=? where id=?",
              snapshot.settlementMode(),
              id);
          insertCourses(scope.tenantId(), id, schoolId, snapshot, command.courses());
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
          var mode = row.get("settlement_mode", Integer.class);
          var cancelled = cancelledHours(id);
          if (hours.compareTo(purchased.subtract(cancelled)) > 0)
            throw new EnrollmentConflict("HOURS_EXCEED_REMAINING");
          BigDecimal amount;
          // 课程维度模式 2/3/5 按课程退；模式 1/4 按报名总额退。
          if (mode != null && mode != 1 && mode != 4) {
            // 模式 2/3/4/5：按课程退，金额取该课程单价；同时累计课程级已退，供模式 4/5 重算单价。
            var curriculumId = command.curriculumId();
            if (curriculumId == null) throw new EnrollmentConflict("CURRICULUM_REQUIRED");
            var line =
                sql.fetchOne(
                    "select id,hours,amount,cancelled_hours,cancelled_amount"
                        + " from enrollment.enrollment_course where enrollment_id=? and curriculum_id=?"
                        + " for update",
                    id,
                    curriculumId);
            if (line == null) throw new EnrollmentConflict("CURRICULUM_NOT_IN_ENROLLMENT");
            var lineHours = line.get("hours", BigDecimal.class);
            var lineCancelled = line.get("cancelled_hours", BigDecimal.class);
            if (hours.compareTo(lineHours.subtract(lineCancelled)) > 0)
              throw new EnrollmentConflict("HOURS_EXCEED_COURSE_REMAINING");
            var lineAmount = line.get("amount", BigDecimal.class);
            if (mode == 5) {
              // 模式 5：按"退课后实际费用"的课程单价退。
              lineAmount = lineAmount.subtract(line.get("cancelled_amount", BigDecimal.class));
              lineHours = lineHours.subtract(lineCancelled);
              if (lineHours.signum() <= 0)
                throw new EnrollmentConflict("HOURS_EXCEED_COURSE_REMAINING");
            }
            var unit = lineAmount.divide(lineHours, 6, RoundingMode.HALF_UP);
            amount = unit.multiply(hours).setScale(2, RoundingMode.HALF_UP);
            // 自定义退费额优先，课程级累计用最终金额，否则模式 4/5 的净额会算错。
            amount = customAmount(command, price, id, amount);
            sql.execute(
                "update enrollment.enrollment_course set cancelled_hours=cancelled_hours+?,"
                    + "cancelled_amount=cancelled_amount+?,version=version+1 where id=?",
                hours,
                amount,
                line.get("id", UUID.class));
          } else {
            var unit = price.divide(purchased, 6, RoundingMode.HALF_UP);
            amount = unit.multiply(hours).setScale(2, RoundingMode.HALF_UP);
            amount = customAmount(command, price, id, amount);
          }
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

  /**
   * 写入课程明细。
   *
   * <p>未提供明细时按价格版本所属课程生成一条，其课时与费用等于快照；提供明细时要求课程属于同一学校、互不重复， 且课时与费用合计必须等于快照，避免明细与总价脱钩。
   */
  private void insertCourses(
      UUID tenantId,
      UUID enrollmentId,
      UUID schoolId,
      org.teraschool.product.api.PriceSnapshot snapshot,
      List<CourseLine> lines) {
    var totalHours = new BigDecimal(snapshot.hours());
    var totalAmount = new BigDecimal(snapshot.price());
    var normalized = new ArrayList<CourseLine>();
    if (lines == null || lines.isEmpty()) {
      normalized.add(new CourseLine(snapshot.curriculumId(), snapshot.hours(), snapshot.price()));
    } else {
      var seen = new HashSet<UUID>();
      for (var line : lines) {
        if (line == null || line.curriculumId() == null)
          throw new IllegalArgumentException("课程明细必须指定课程");
        if (!seen.add(line.curriculumId())) throw new IllegalArgumentException("课程明细重复");
        var lineHours = positiveHours(line.hours());
        var lineAmount = positiveHours(line.amount());
        var exists =
            sql.fetchOne(
                "select 1 from product.curriculum where id=? and school_id=?",
                line.curriculumId(),
                schoolId);
        if (exists == null) throw new IllegalArgumentException("课程不属于该学校");
        normalized.add(
            new CourseLine(
                line.curriculumId(), lineHours.toPlainString(), lineAmount.toPlainString()));
      }
      var sumHours =
          normalized.stream()
              .map(item -> new BigDecimal(item.hours()))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      var sumAmount =
          normalized.stream()
              .map(item -> new BigDecimal(item.amount()))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (sumHours.compareTo(totalHours) != 0 || sumAmount.compareTo(totalAmount) != 0)
        throw new IllegalArgumentException("课程明细合计必须等于价格版本的课时与价格");
    }
    for (var line : normalized)
      sql.execute(
          "insert into enrollment.enrollment_course(tenant_id,enrollment_id,curriculum_id,hours,amount)"
              + " values (?,?,?,?,?)",
          tenantId,
          enrollmentId,
          line.curriculumId(),
          new BigDecimal(line.hours()),
          new BigDecimal(line.amount()));
  }

  /**
   * 自定义退费额（旧系统的 FeeQuit）：提供时以它为准，但不得超过剩余应收。
   *
   * <p>模式 4/5 的"退课后实际费用"正依赖这个金额，因此课程级累计必须用它。
   */
  private BigDecimal customAmount(
      CancelHours command, BigDecimal price, UUID enrollmentId, BigDecimal computed) {
    if (command.amount() == null || command.amount().isBlank()) return computed;
    var amount = positiveHours(command.amount());
    var remaining = price.subtract(cancelledAmount(enrollmentId));
    if (amount.compareTo(remaining) > 0) throw new EnrollmentConflict("AMOUNT_EXCEED_REMAINING");
    return amount;
  }

  private BigDecimal cancelledAmount(UUID enrollmentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from enrollment.hours_cancellation"
                    + " where enrollment_id=?",
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

  @Override
  public EnrollmentView applyDiscount(UUID id, ApplyDiscount command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return applyDiscountWithTrace(id, command);
    }
  }

  /**
   * 登记一次性减免。
   *
   * <p>只写"减免多少 + 原因"，落到应收减项，不承担优惠券、阶梯折扣等规则计算；审批走通用审批流（如需要）。
   */
  private EnrollmentView applyDiscountWithTrace(UUID id, ApplyDiscount command) {
    if (id == null || command == null) throw new IllegalArgumentException("需要报名与减免金额");
    var amount = positiveHours(command.amount());
    var reason = text(command.reason(), 200, "减免原因");
    var scope = actors.requireResourceScope("ENROLLMENT");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("ENROLLMENT");
          var row = requireEnrollmentForUpdate(id);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校的报名维护权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能登记减免");
          if (!"ACTIVE".equals(row.get("status", String.class)))
            throw new EnrollmentConflict("ENROLLMENT_NOT_ACTIVE");
          var original =
              row.get("price", BigDecimal.class).add(row.get("service_fee", BigDecimal.class));
          var outstanding = netAmount(id);
          if (amount.compareTo(outstanding) > 0)
            throw new EnrollmentConflict("DISCOUNT_EXCEED_RECEIVABLE");
          sql.execute(
              "insert into enrollment.discount(tenant_id,enrollment_id,amount,reason,created_by)"
                  + " values (?,?,?,?,?)",
              scope.tenantId(),
              id,
              amount,
              reason,
              scope.accountId());
          sql.execute(
              "insert into enrollment.charge(tenant_id,enrollment_id,kind,amount,reason)"
                  + " values (?,?,'ADJUSTMENT',?,'优惠减免')",
              scope.tenantId(),
              id,
              amount.negate());
          audit(scope, "DISCOUNT_ADDED", id, schoolId, amount.toPlainString());
          return view(
              requireEnrollment(verified, id),
              students.require(row.get("student_id", UUID.class)).name());
        });
  }

  @Override
  public String exportCsv(UUID schoolId) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireResourceScope("ENROLLMENT");
      if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
        throw new AccessDeniedException("无该学校的报名读取权限");
      return tx.execute(
          scope.tenantId(),
          () -> {
            var rows =
                sql.fetch(
                    "select e.id,e.product_name,e.curriculum_name,e.price,e.hours,"
                        + "coalesce((select sum(c.amount) from enrollment.charge c"
                        + " where c.enrollment_id=e.id),0) as net_amount,"
                        + "e.status,e.created_at,s.student_no,s.name as student_name"
                        + " from enrollment.enrollment e"
                        + " join archive.student s on s.id=e.student_id and s.tenant_id=e.tenant_id"
                        + " where e.school_id=? order by e.created_at desc limit 5000",
                    schoolId);
            var csv = new StringBuilder();
            csv.append("学员号,学员姓名,产品,课程,价格,课时,净应收,状态,报名时间").append(NEWLINE);
            for (var row : rows) {
              csv.append(cell(row.get("student_no", String.class)))
                  .append(',')
                  .append(cell(row.get("student_name", String.class)))
                  .append(',')
                  .append(cell(row.get("product_name", String.class)))
                  .append(',')
                  .append(cell(row.get("curriculum_name", String.class)))
                  .append(',')
                  .append(cell(money(row.get("price", BigDecimal.class))))
                  .append(',')
                  .append(cell(hours(row.get("hours", BigDecimal.class))))
                  .append(',')
                  .append(cell(money(row.get("net_amount", BigDecimal.class))))
                  .append(',')
                  .append(cell(row.get("status", String.class)))
                  .append(',')
                  .append(
                      cell(
                          row.get("created_at", java.time.OffsetDateTime.class) == null
                              ? null
                              : row.get("created_at", java.time.OffsetDateTime.class)
                                  .toInstant()
                                  .toString()))
                  .append(NEWLINE);
            }
            audit(scope, "ENROLLMENTS_EXPORTED", schoolId, schoolId, "rows=" + rows.size());
            return csv.toString();
          });
    }
  }

  private static final char NEWLINE = (char) 10;

  private String cell(String value) {
    if (value == null) return "";
    var text = value.replace("\"", "\"\"");
    return text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf(NEWLINE) >= 0
        ? "\"" + text + "\""
        : text;
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

  /** 报名累计优惠（只要 ADJUSTMENT 里"优惠减免"那部分；冲减退课时不在内）。 */
  private BigDecimal discountAmount(UUID enrollmentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from enrollment.discount"
                    + " where enrollment_id=?",
                enrollmentId)
            .get("total", java.math.BigDecimal.class);
    return value == null ? java.math.BigDecimal.ZERO : value;
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
            : row.get("created_at", java.time.OffsetDateTime.class).toInstant().toString(),
        courseViews(row.get("id", UUID.class)));
  }

  private List<CourseView> courseViews(UUID enrollmentId) {
    return sql.fetch(
            "select c.curriculum_id,cur.name,c.hours,c.amount,c.cancelled_hours,c.cancelled_amount"
                + " from enrollment.enrollment_course c"
                + " join product.curriculum cur on cur.id=c.curriculum_id"
                + " where c.enrollment_id=? order by c.created_at,c.id",
            enrollmentId)
        .map(
            row ->
                new CourseView(
                    row.get("curriculum_id", UUID.class),
                    row.get("name", String.class),
                    money(row.get("hours", BigDecimal.class)),
                    money(row.get("amount", BigDecimal.class)),
                    money(row.get("cancelled_hours", BigDecimal.class)),
                    money(row.get("cancelled_amount", BigDecimal.class))));
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
