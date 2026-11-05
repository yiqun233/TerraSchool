package org.teraschool.teaching.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.approval.api.ApprovalGate;
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.enrollment.api.EnrollmentAccess;
import org.teraschool.enrollment.api.EnrollmentQueries;
import org.teraschool.finance.api.PeriodGate;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.product.api.CurriculumAccess;
import org.teraschool.teaching.api.*;

/**
 * 班级、课次、考勤与课次消费的完整用例。
 *
 * <p>课次生命周期：草稿 → 发布 → 授课确认（含考勤）→ 关闭。关闭是消费与课时的唯一固化点：同一报名×课次只
 * 允许一条消费，重复关闭会被状态与唯一索引双重阻止。消费金额按报名冻结的单价与课时计算（模式 1 平均价格）， 不重新读取当前价格。
 */
@Service
public class TeachingService implements TeachingQueries, TeachingCommands, ConsumptionAccess {
  private static final java.util.regex.Pattern HOURS =
      java.util.regex.Pattern.compile("\\d{1,6}(\\.\\d{1,2})?");
  private static final Set<String> ATTENDANCE = Set.of("PRESENT", "ABSENT", "LEAVE", "PARTIAL");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final EnrollmentAccess enrollments;
  private final EnrollmentQueries enrollmentQueries;
  private final CurriculumAccess curricula;
  private final StudentAccess students;
  private final ApprovalGate approvals;
  private final PeriodGate periods;

  public TeachingService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      EnrollmentAccess enrollments,
      EnrollmentQueries enrollmentQueries,
      CurriculumAccess curricula,
      StudentAccess students,
      ApprovalGate approvals,
      PeriodGate periods) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.enrollments = enrollments;
    this.enrollmentQueries = enrollmentQueries;
    this.curricula = curricula;
    this.students = students;
    this.approvals = approvals;
    this.periods = periods;
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public TeachingCapabilities capabilities() {
    var scope = actors.requireResourceScope("TEACHING");
    var attendanceSchools = schoolsOf("ATTENDANCE_RECORD");
    var closeSchools = schoolsOf("LESSON_CLOSE");
    if (scope.readableSchoolIds().isEmpty())
      return new TeachingCapabilities(true, false, false, false, List.of());
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
                          new TeachingCapabilities.TeachingSchoolOption(
                              key,
                              names.get(key),
                              scope.maintainableSchoolIds().contains(key),
                              attendanceSchools.contains(key),
                              closeSchools.contains(key)))
                  .toList();
          return new TeachingCapabilities(
              true,
              options.stream().anyMatch(TeachingCapabilities.TeachingSchoolOption::maintainable),
              options.stream()
                  .anyMatch(TeachingCapabilities.TeachingSchoolOption::canRecordAttendance),
              options.stream().anyMatch(TeachingCapabilities.TeachingSchoolOption::canCloseLesson),
              options);
        });
  }

  @Override
  public TeacherWorkbench myWorkbench(String fromText, String toText) {
    var from = workbenchDate(fromText);
    var to = workbenchDate(toText);
    if (to.isBefore(from) || to.isAfter(from.plusDays(92)))
      throw new IllegalArgumentException("教师课表范围不能倒置或超过 93 天");
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var schoolIds = new ArrayList<>(verified.readableSchoolIds());
          if (schoolIds.isEmpty()) return new TeacherWorkbench(List.of(), List.of());
          var marks = String.join(",", Collections.nCopies(schoolIds.size(), "?"));
          var args = new ArrayList<Object>();
          args.add(verified.accountId());
          args.addAll(schoolIds);
          args.add(from);
          args.add(to);
          var rows =
              sql.fetch(
                  "select l.id,l.class_id,c.name as class_name,cur.name as curriculum_name,"
                      + "l.lesson_date,l.starts_at,l.ends_at,l.room_name,l.topic,l.status,l.version,"
                      + "c.status as class_status,c.group_training "
                      + "from teaching.lesson l join teaching.class c on c.id=l.class_id "
                      + "join product.curriculum cur on cur.id=c.curriculum_id "
                      + "where l.teacher_id in (select t.id from organization.teacher t "
                      + "join organization.employee e on e.tenant_id=t.tenant_id and e.id=t.employee_id "
                      + "where e.account_id=? and t.active and t.school_id in ("
                      + marks
                      + ")) and l.lesson_date between ? and ? "
                      + "order by l.lesson_date,l.starts_at,l.id limit 1000",
                  args.toArray());
          var lessonViews =
              rows.map(
                  row ->
                      new TeacherLessonView(
                          row.get("id", UUID.class),
                          row.get("class_id", UUID.class),
                          row.get("class_name", String.class),
                          row.get("curriculum_name", String.class),
                          date(row.get("lesson_date", LocalDate.class)),
                          row.get("starts_at").toString(),
                          row.get("ends_at").toString(),
                          row.get("room_name", String.class),
                          row.get("topic", String.class),
                          row.get("status", String.class),
                          row.get("version", Long.class)));
          var grouped = new LinkedHashMap<UUID, TeacherClassView>();
          for (var row : rows) {
            var id = row.get("class_id", UUID.class);
            var previous = grouped.get(id);
            int count = previous == null ? 1 : previous.lessonCount() + 1;
            grouped.put(
                id,
                new TeacherClassView(
                    id,
                    row.get("class_name", String.class),
                    row.get("curriculum_name", String.class),
                    row.get("class_status", String.class),
                    Boolean.TRUE.equals(row.get("group_training", Boolean.class)),
                    count));
          }
          return new TeacherWorkbench(List.copyOf(grouped.values()), lessonViews);
        });
  }

  private static LocalDate workbenchDate(String input) {
    try {
      if (input == null || !input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException("日期格式无效");
      return LocalDate.parse(input);
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException("日期格式无效");
    }
  }

  @Override
  public TeacherClassWorkbench myClass(UUID classId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var classRow = requireClass(verified, classId);
          var schoolId = classRow.get("school_id", UUID.class);
          var teacherId =
              sql.fetchOne(
                  "select t.id from organization.teacher t"
                      + " join organization.employee e on e.tenant_id=t.tenant_id and e.id=t.employee_id"
                      + " where e.account_id=? and t.school_id=? and t.active",
                  verified.accountId(),
                  schoolId);
          if (teacherId == null) throw new AccessDeniedException("当前账号未绑定该班级学校的在职教师");
          var assignedTeacherId = teacherId.get("id", UUID.class);
          var lessons =
              sql.fetch(
                  "select l.id,l.class_id,c.name as class_name,cur.name as curriculum_name,"
                      + "l.lesson_date,l.starts_at,l.ends_at,l.room_name,l.topic,l.status,l.version "
                      + "from teaching.lesson l join teaching.class c on c.id=l.class_id "
                      + "join product.curriculum cur on cur.id=c.curriculum_id "
                      + "where l.class_id=? and l.teacher_id=? "
                      + "order by l.lesson_date,l.starts_at,l.id limit 1000",
                  classId,
                  assignedTeacherId);
          if (lessons.isEmpty()) throw new AccessDeniedException("当前教师不是该班级的任课教师");

          var memberRows =
              sql.fetch(
                  "select enrollment_id,joined_on,active from teaching.enrollment_class"
                      + " where class_id=? order by joined_on,enrollment_id",
                  classId);
          var enrollmentsInClass =
              memberRows.stream()
                  .map(row -> enrollments.require(row.get("enrollment_id", UUID.class)))
                  .toList();
          var studentNames =
              students.names(
                  enrollmentsInClass.stream()
                      .map(EnrollmentAccess.EnrollmentRef::studentId)
                      .toList());
          var members =
              memberRows.map(
                  row -> {
                    var enrollment = enrollments.require(row.get("enrollment_id", UUID.class));
                    return new ClassMemberView(
                        enrollment.id(),
                        studentNames.get(enrollment.studentId()),
                        row.get("joined_on", LocalDate.class).toString(),
                        Boolean.TRUE.equals(row.get("active", Boolean.class)) ? "ACTIVE" : "LEFT");
                  });
          var lessonViews =
              lessons.map(
                  row ->
                      new TeacherLessonView(
                          row.get("id", UUID.class),
                          row.get("class_id", UUID.class),
                          row.get("class_name", String.class),
                          row.get("curriculum_name", String.class),
                          date(row.get("lesson_date", LocalDate.class)),
                          row.get("starts_at").toString(),
                          row.get("ends_at").toString(),
                          row.get("room_name", String.class),
                          row.get("topic", String.class),
                          row.get("status", String.class),
                          row.get("version", Long.class)));
          var classInfo =
              new TeacherClassView(
                  classId,
                  classRow.get("name", String.class),
                  classRow.get("curriculum_name", String.class),
                  classRow.get("status", String.class),
                  Boolean.TRUE.equals(classRow.get("group_training", Boolean.class)),
                  lessonViews.size());
          return new TeacherClassWorkbench(classInfo, members, lessonViews);
        });
  }

  @Override
  public DailyResourceSchedule dailyResourceSchedule(
      UUID schoolId, String dayText, int page, int pageSize) {
    var scope = actors.requireResourceScope("TEACHING");
    if (page < 0 || page > 100_000 || pageSize < 1 || pageSize > 200)
      throw new IllegalArgumentException("资源日报分页参数超出范围");
    var day = workbenchDate(dayText);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          requireReadable(verified, schoolId);
          var totals =
              sql.fetchOne(
                  "select count(*) as total,count(*) filter (where l.teacher_id is null) as missing_teacher,"
                      + "count(*) filter (where l.room_id is null) as missing_room"
                      + " from teaching.lesson l join teaching.class c on c.id=l.class_id"
                      + " where c.school_id=? and l.lesson_date=?",
                  schoolId,
                  day);
          var rows =
              sql.fetch(
                  "select l.id,c.name as class_name,l.lesson_date,l.starts_at,l.ends_at,"
                      + "coalesce(t.name,l.teacher_name) as teacher_name,coalesce(r.name,l.room_name) as room_name,"
                      + "l.topic,l.status,l.teacher_id,l.room_id from teaching.lesson l"
                      + " join teaching.class c on c.id=l.class_id"
                      + " left join organization.teacher t on t.id=l.teacher_id"
                      + " left join organization.room r on r.id=l.room_id"
                      + " where c.school_id=? and l.lesson_date=?"
                      + " order by l.starts_at,c.name,l.id limit ? offset ?",
                  schoolId,
                  day,
                  pageSize,
                  (long) page * pageSize);
          var slots =
              rows.map(
                  row ->
                      new ResourceScheduleSlot(
                          row.get("id", UUID.class),
                          row.get("class_name", String.class),
                          date(row.get("lesson_date", LocalDate.class)),
                          row.get("starts_at").toString(),
                          row.get("ends_at").toString(),
                          row.get("teacher_name", String.class),
                          row.get("room_name", String.class),
                          row.get("topic", String.class),
                          row.get("status", String.class)));
          return new DailyResourceSchedule(
              day.toString(),
              slots,
              totals.get("missing_teacher", Long.class).intValue(),
              totals.get("missing_room", Long.class).intValue(),
              totals.get("total", Long.class),
              page,
              pageSize);
        });
  }

  @Override
  public StudentConsumptionPage studentConsumption(UUID schoolId, int page, int size) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (page < 0 || page > 100000 || size < 1 || size > 100)
      throw new IllegalArgumentException("分页超出范围");
    var teachingScope = actors.requireResourceScope("TEACHING");
    var fundsActor = actors.requireAction("FUNDS_READ");
    if (!teachingScope.tenantId().equals(fundsActor.tenantId())
        || !teachingScope.readableSchoolIds().contains(schoolId)
        || !fundsActor.schools().contains(schoolId))
      throw new AccessDeniedException("无该学校学员消费报表权限");
    return tx.execute(
        teachingScope.tenantId(),
        () -> {
          var verifiedTeaching = actors.requireResourceScopeInTransaction("TEACHING");
          var verifiedFunds = actors.requireActionInTransaction("FUNDS_READ");
          if (!verifiedTeaching.readableSchoolIds().contains(schoolId)
              || !verifiedFunds.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校学员消费报表权限");
          var bases = enrollmentQueries.consumptionBase(schoolId, page, size);
          var studentIds =
              bases.items().stream().map(EnrollmentQueries.ConsumptionBase::studentId).toList();
          var studentNames = students.names(studentIds);
          var totals = new HashMap<UUID, BigDecimal[]>();
          if (!bases.items().isEmpty()) {
            var placeholders = String.join(",", Collections.nCopies(bases.items().size(), "?"));
            var ids =
                bases.items().stream()
                    .map(EnrollmentQueries.ConsumptionBase::enrollmentId)
                    .toList();
            var rows =
                sql.fetch(
                    "select enrollment_id,coalesce(sum(hours),0) as consumed_hours,"
                        + "coalesce(sum(amount),0) as consumed_amount from teaching.consumption"
                        + " where enrollment_id in ("
                        + placeholders
                        + ") group by enrollment_id",
                    ids.toArray());
            for (var row : rows)
              totals.put(
                  row.get("enrollment_id", UUID.class),
                  new BigDecimal[] {
                    row.get("consumed_hours", BigDecimal.class),
                    row.get("consumed_amount", BigDecimal.class)
                  });
          }
          var items =
              bases.items().stream()
                  .map(
                      base -> {
                        var amounts =
                            totals.getOrDefault(
                                base.enrollmentId(),
                                new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                        var consumedHours = amounts[0];
                        var remaining =
                            new BigDecimal(base.purchasedHours())
                                .subtract(consumedHours)
                                .subtract(new BigDecimal(base.cancelledHours()));
                        return new StudentConsumptionRow(
                            base.enrollmentId(),
                            base.studentId(),
                            studentNames.getOrDefault(base.studentId(), ""),
                            base.status(),
                            base.settlementMode(),
                            base.price(),
                            base.purchasedHours(),
                            hours(consumedHours),
                            money(amounts[1]),
                            base.cancelledHours(),
                            hours(remaining));
                      })
                  .toList();
          return new StudentConsumptionPage(items, bases.page(), bases.size(), bases.total());
        });
  }

  @Override
  public ClassConsumptionReport classConsumptionReport(
      UUID schoolId, String fromText, String toText) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var from = workbenchDate(fromText);
    var to = workbenchDate(toText);
    if (to.isBefore(from) || to.isAfter(from.plusDays(30)))
      throw new IllegalArgumentException("班级课次消费日期范围须为 1 至 31 天");
    var teachingScope = actors.requireResourceScope("TEACHING");
    var enrollmentScope = actors.requireResourceScope("ENROLLMENT");
    var fundsActor = actors.requireAction("FUNDS_READ");
    if (!teachingScope.tenantId().equals(enrollmentScope.tenantId())
        || !teachingScope.tenantId().equals(fundsActor.tenantId())
        || !teachingScope.readableSchoolIds().contains(schoolId)
        || !enrollmentScope.readableSchoolIds().contains(schoolId)
        || !fundsActor.schools().contains(schoolId))
      throw new AccessDeniedException("需要报名、教务和资金的学校读取权限");
    return tx.execute(
        teachingScope.tenantId(),
        () -> {
          var verifiedTeaching = actors.requireResourceScopeInTransaction("TEACHING");
          var verifiedEnrollment = actors.requireResourceScopeInTransaction("ENROLLMENT");
          var verifiedFunds = actors.requireActionInTransaction("FUNDS_READ");
          if (!verifiedTeaching.readableSchoolIds().contains(schoolId)
              || !verifiedEnrollment.readableSchoolIds().contains(schoolId)
              || !verifiedFunds.schools().contains(schoolId))
            throw new AccessDeniedException("需要报名、教务和资金的学校读取权限");
          var rows =
              sql.fetch(
                  "select c.id as class_id,c.name as class_name,count(distinct l.id) as lesson_count,"
                      + "coalesce(sum(x.hours),0) as consumed_hours,coalesce(sum(x.amount),0) as consumed_amount"
                      + " from teaching.consumption x"
                      + " join teaching.lesson l on l.id=x.lesson_id and l.tenant_id=x.tenant_id"
                      + " join teaching.class c on c.id=l.class_id and c.tenant_id=l.tenant_id"
                      + " where c.school_id=? and l.lesson_date between ? and ?"
                      + " group by c.id,c.name order by c.name,c.id limit 5001",
                  schoolId,
                  from,
                  to);
          org.teraschool.platform.api.CsvExport.requireComplete(rows.size());
          return new ClassConsumptionReport(
              from.toString(),
              to.toString(),
              rows.map(
                  row ->
                      new ClassConsumptionRow(
                          row.get("class_id", UUID.class),
                          row.get("class_name", String.class),
                          row.get("lesson_count", Long.class),
                          hours(row.get("consumed_hours", BigDecimal.class)),
                          money(row.get("consumed_amount", BigDecimal.class)))));
        });
  }

  @Override
  public AttendanceReport attendanceReport(UUID schoolId, String fromText, String toText) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var from = workbenchDate(fromText);
    var to = workbenchDate(toText);
    if (to.isBefore(from) || to.isAfter(from.plusDays(30)))
      throw new IllegalArgumentException("考勤报表日期范围须为 1 至 31 天");
    var scope = actors.requireResourceScope("TEACHING");
    requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          requireReadable(verified, schoolId);
          var rows =
              sql.fetch(
                  "select l.id,c.name as class_name,l.lesson_date,l.starts_at,l.ends_at,l.status,"
                      + "count(a.enrollment_id) filter (where a.status='PRESENT') as present,"
                      + "count(a.enrollment_id) filter (where a.status='PARTIAL') as partial,"
                      + "count(a.enrollment_id) filter (where a.status='ABSENT') as absent,"
                      + "count(a.enrollment_id) filter (where a.status='LEAVE') as leave,"
                      + "count(a.enrollment_id) as recorded from teaching.lesson l"
                      + " join teaching.class c on c.id=l.class_id"
                      + " left join teaching.attendance a on a.lesson_id=l.id"
                      + " where c.school_id=? and l.lesson_date between ? and ?"
                      + " group by l.id,c.name order by l.lesson_date,l.starts_at,c.name,l.id",
                  schoolId,
                  from,
                  to);
          return new AttendanceReport(
              from.toString(),
              to.toString(),
              rows.map(
                  row ->
                      new LessonAttendanceSummary(
                          row.get("id", UUID.class),
                          row.get("class_name", String.class),
                          date(row.get("lesson_date", LocalDate.class)),
                          row.get("starts_at").toString(),
                          row.get("ends_at").toString(),
                          row.get("status", String.class),
                          row.get("present", Long.class),
                          row.get("partial", Long.class),
                          row.get("absent", Long.class),
                          row.get("leave", Long.class),
                          row.get("recorded", Long.class))));
        });
  }

  @Override
  public List<ClassView> classes(UUID schoolId) {
    var scope = actors.requireResourceScope("TEACHING");
    requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var schoolName = schools.require(schoolId).name();
          return sql.fetch(
                  "select c.id,c.school_id,c.curriculum_id,c.name,c.status,c.version,c.group_training,"
                      + "cur.name as curriculum_name,"
                      + "(select count(*) from teaching.enrollment_class m where m.class_id=c.id and m.active)"
                      + " as member_count from teaching.class c"
                      + " join product.curriculum cur on cur.id=c.curriculum_id"
                      + " where c.school_id=? order by c.name, c.id limit 200",
                  schoolId)
              .map(row -> classView(row, schoolName));
        });
  }

  @Override
  public List<ClassMemberView> members(UUID classId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = requireClass(scope, classId);
          var rows =
              sql.fetch(
                  "select enrollment_id,joined_on,active from teaching.enrollment_class"
                      + " where class_id=? order by joined_on, enrollment_id",
                  row.get("id", UUID.class));
          var names =
              students.names(
                  rows.map(
                      item ->
                          enrollments.require(item.get("enrollment_id", UUID.class)).studentId()));
          return rows.map(
              item -> {
                var enrollment = enrollments.require(item.get("enrollment_id", UUID.class));
                return new ClassMemberView(
                    item.get("enrollment_id", UUID.class),
                    names.get(enrollment.studentId()),
                    item.get("joined_on", LocalDate.class).toString(),
                    Boolean.TRUE.equals(item.get("active", Boolean.class)) ? "ACTIVE" : "LEFT");
              });
        });
  }

  @Override
  public List<LessonView> lessons(UUID classId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireClass(scope, classId);
          return sql.fetch(
                  "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,l.teacher_name,"
                      + "l.room_name,l.topic,l.hours,l.status,l.version,l.teacher_id,l.room_id,"
                      + "(select count(*) from teaching.attendance a where a.lesson_id=l.id)"
                      + " as attendance_count from teaching.lesson l where l.class_id=?"
                      + " order by l.lesson_date, l.starts_at, l.id limit 200",
                  classId)
              .map(this::lessonView);
        });
  }

  @Override
  public List<AttendanceInput> attendance(UUID lessonId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireLesson(scope, lessonId);
          return sql.fetch(
                  "select enrollment_id,status,attended_minutes from teaching.attendance where lesson_id=?"
                      + " order by enrollment_id",
                  lessonId)
              .map(
                  row ->
                      new AttendanceInput(
                          row.get("enrollment_id", UUID.class),
                          row.get("status", String.class),
                          row.get("attended_minutes", Integer.class)));
        });
  }

  @Override
  public EnrollmentReconciliation reconciliation(UUID enrollmentId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var enrollment = enrollments.require(enrollmentId);
          if (!scope.readableSchoolIds().contains(enrollment.schoolId()))
            throw new AccessDeniedException("无该报名的教务读取权限");
          var consumptions =
              sql.fetch(
                      "select c.id,c.enrollment_id,c.lesson_id,l.lesson_date,c.hours,c.amount,"
                          + "c.settlement_mode,c.price_snapshot,c.hours_snapshot"
                          // 期初消耗没有课次（lesson_id 为空），必须用左连接，否则会被过滤掉。
                          + " from teaching.consumption c left join teaching.lesson l on l.id=c.lesson_id"
                          + " where c.enrollment_id=? order by l.lesson_date nulls first, c.id",
                      enrollmentId)
                  .map(
                      row ->
                          new ConsumptionView(
                              row.get("id", UUID.class),
                              row.get("enrollment_id", UUID.class),
                              row.get("lesson_id", UUID.class),
                              date(row.get("lesson_date", LocalDate.class)),
                              hours(row.get("hours", BigDecimal.class)),
                              money(row.get("amount", BigDecimal.class)),
                              row.get("settlement_mode", Integer.class),
                              money(row.get("price_snapshot", BigDecimal.class)),
                              hours(row.get("hours_snapshot", BigDecimal.class))));
          var consumedHours =
              consumptions.stream()
                  .map(item -> new BigDecimal(item.hours()))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          var consumedAmount =
              consumptions.stream()
                  .map(item -> new BigDecimal(item.amount()))
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          return new EnrollmentReconciliation(
              enrollmentId,
              enrollment.status(),
              String.valueOf(enrollment.settlementMode()),
              money(enrollment.price()),
              hours(enrollment.hours()),
              hours(consumedHours),
              money(consumedAmount),
              hours(enrollment.cancelledHours()),
              hours(
                  enrollment.hours().subtract(consumedHours).subtract(enrollment.cancelledHours())),
              consumptions);
        });
  }

  @Override
  public Consumed consumed(UUID enrollmentId) {
    var row =
        sql.fetchOne(
            "select coalesce(sum(hours),0) as hours, coalesce(sum(amount),0) as amount"
                + " from teaching.consumption where enrollment_id=?",
            enrollmentId);
    return new Consumed(
        enrollmentId,
        row == null ? BigDecimal.ZERO : row.get("hours", BigDecimal.class),
        row == null ? BigDecimal.ZERO : row.get("amount", BigDecimal.class));
  }

  // ---------------------------------------------------------------- 班级

  @Override
  public ClassView createClass(CreateClass command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createClassWithTrace(command);
    }
  }

  @Override
  public ClassView closeClass(UUID classId, LessonVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (classId == null || command == null || command.expectedVersion() == null)
        throw new IllegalArgumentException("需要班级和版本");
      var scope = actors.requireResourceScope("TEACHING");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("TEACHING");
            var row = requireClass(verified, classId);
            var schoolId = row.get("school_id", UUID.class);
            if (!verified.maintainableSchoolIds().contains(schoolId))
              throw new AccessDeniedException("无班级维护权限");
            if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能结班");
            if (!"ACTIVE".equals(row.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            if (!row.get("version", Long.class).equals(command.expectedVersion()))
              throw new TeachingConflict("VERSION_CONFLICT");
            if (sql.fetchExists(
                sql.selectOne()
                    .from("teaching.lesson")
                    .where("class_id=? and status<>'CLOSED'", classId)))
              throw new TeachingConflict("CLASS_HAS_OPEN_LESSONS");
            int changed =
                sql.execute(
                    "update teaching.class set status='CLOSED',version=version+1 where id=? and version=? and status='ACTIVE'",
                    classId,
                    command.expectedVersion());
            if (changed != 1) throw new TeachingConflict("VERSION_CONFLICT");
            sql.execute(
                "update teaching.enrollment_class set active=false where class_id=? and active",
                classId);
            audit(verified, "CLASS_CLOSED", classId, schoolId, "members-ended");
            return classView(requireClass(verified, classId), schools.require(schoolId).name());
          });
    }
  }

  @Override
  public ClassView cancelClass(UUID classId, CancelClass command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (classId == null
          || command == null
          || command.requestId() == null
          || command.expectedVersion() == null) throw new IllegalArgumentException("需要班级、请求身份与版本");
      var reason = text(command.reason(), 200, "取消原因");
      if (reason == null) throw new IllegalArgumentException("取消原因必填");
      var scope = actors.requireResourceScope("TEACHING");
      var fingerprint =
          fingerprint(
              "CLASS_CANCEL", classId.toString(), command.expectedVersion().toString(), reason);
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("TEACHING");
            var current =
                sql.fetchOne(
                    "select school_id,status,version from teaching.class where id=? for update",
                    classId);
            if (current == null) throw new AccessDeniedException("班级不存在或不可访问");
            var schoolId = current.get("school_id", UUID.class);
            if (!verified.maintainableSchoolIds().contains(schoolId))
              throw new AccessDeniedException("无该学校班级维护权限");
            var school = schools.require(schoolId);
            if (!school.active()) throw new AccessDeniedException("学校已停用，不能取消班级");
            var previous = previousRequest(scope, command.requestId(), "CLASS", fingerprint);
            if (previous != null) return classView(requireClass(verified, previous), school.name());
            if (!current.get("version", Long.class).equals(command.expectedVersion()))
              throw new TeachingConflict("VERSION_CONFLICT");
            if (!"ACTIVE".equals(current.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            if (sql.fetchOne("select 1 from teaching.lesson where class_id=? limit 1", classId)
                != null) throw new TeachingConflict("CLASS_HAS_LESSONS");
            if (sql.fetchOne(
                    "select 1 from teaching.enrollment_class where class_id=? and active limit 1",
                    classId)
                != null) throw new TeachingConflict("CLASS_HAS_ACTIVE_MEMBERS");
            int changed =
                sql.execute(
                    "update teaching.class set status='CANCELLED',version=version+1"
                        + " where id=? and status='ACTIVE' and version=?",
                    classId,
                    command.expectedVersion());
            if (changed != 1) throw new TeachingConflict("VERSION_CONFLICT");
            rememberRequest(scope, command.requestId(), "CLASS", classId, fingerprint);
            audit(scope, "CLASS_CANCELLED", classId, schoolId, "reason=" + reason);
            return classView(requireClass(verified, classId), school.name());
          });
    }
  }

  private ClassView createClassWithTrace(CreateClass command) {
    if (command == null || command.requestId() == null || command.schoolId() == null)
      throw new IllegalArgumentException("需要请求身份和学校");
    var name = text(command.name(), 100, "班级名称");
    if (name == null) throw new IllegalArgumentException("班级名称必填");
    var scope = actors.requireResourceScope("TEACHING");
    requireMaintainable(scope, command.schoolId());
    var fingerprint =
        fingerprint(
            "CLASS",
            name,
            String.valueOf(command.curriculumId()),
            classType(command.classType()),
            Boolean.toString(command.groupTraining()));
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          requireMaintainable(verified, command.schoolId());
          var school = schools.require(command.schoolId());
          if (!school.active()) throw new AccessDeniedException("学校已停用，不能新建班级");
          var previous = previousRequest(scope, command.requestId(), "CLASS", fingerprint);
          if (previous != null) return classView(requireClass(verified, previous), school.name());
          var curriculum = curricula.require(command.curriculumId());
          if (!command.schoolId().equals(curriculum.schoolId()))
            throw new AccessDeniedException("课程不属于该学校");
          if (!curriculum.active()) throw new TeachingConflict("CURRICULUM_INACTIVE");
          var id = UUID.randomUUID();
          try {
            sql.execute(
                "insert into teaching.class(id,tenant_id,school_id,curriculum_id,name,class_type,group_training)"
                    + " values (?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                command.curriculumId(),
                name,
                classType(command.classType()),
                command.groupTraining());
          } catch (RuntimeException error) {
            if (uniqueViolation(error)) throw new TeachingConflict("DUPLICATE_CLASS_NAME");
            throw error;
          }
          rememberRequest(scope, command.requestId(), "CLASS", id, fingerprint);
          audit(scope, "CLASS_CREATED", id, command.schoolId(), "");
          return classView(requireClass(verified, id), school.name());
        });
  }

  @Override
  public ClassView addMember(UUID classId, AddClassMember command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return addMemberWithTrace(classId, command);
    }
  }

  private ClassView addMemberWithTrace(UUID classId, AddClassMember command) {
    if (classId == null || command == null || command.enrollmentId() == null)
      throw new IllegalArgumentException("需要班级和报名");
    return addMembersWithTrace(new AddClassMembers(command.enrollmentId(), List.of(classId)))
        .getFirst();
  }

  @Override
  public List<ClassView> addMembers(AddClassMembers command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return addMembersWithTrace(command);
    }
  }

  private List<ClassView> addMembersWithTrace(AddClassMembers command) {
    if (command == null || command.enrollmentId() == null || command.classIds().isEmpty())
      throw new IllegalArgumentException("需要报名和目标班级");
    if (command.classIds().size() > 20) throw new TeachingConflict("CLASS_MEMBER_BATCH_TOO_LARGE");
    if (new HashSet<>(command.classIds()).size() != command.classIds().size())
      throw new TeachingConflict("CLASS_MEMBER_BATCH_DUPLICATE_TARGET");
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var enrollment = enrollments.require(command.enrollmentId());
          if (!"ACTIVE".equals(enrollment.status()))
            throw new TeachingConflict("ENROLLMENT_NOT_ACTIVE");
          if (enrollment.courses().isEmpty())
            throw new TeachingConflict("ENROLLMENT_HAS_NO_COURSE");
          var schoolId = enrollment.schoolId();
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无班级维护权限");
          if (!schools.require(schoolId).active())
            throw new AccessDeniedException("学校已停用，不能调整班级成员");

          var classes = new ArrayList<Record>();
          for (var classId : command.classIds()) {
            var target = requireClass(verified, classId);
            if (!schoolId.equals(target.get("school_id", UUID.class)))
              throw new AccessDeniedException("报名与目标班级须属于同一学校");
            if (!"ACTIVE".equals(target.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            var curriculumId = target.get("curriculum_id", UUID.class);
            if (enrollment.courses().stream()
                .noneMatch(course -> curriculumId.equals(course.curriculumId())))
              throw new TeachingConflict("ENROLLMENT_COURSE_MISMATCH");
            classes.add(target);
          }

          if (enrollment.settlementMode() == 0) {
            var resolvedModes =
                classes.stream()
                    .map(target -> "VIP".equals(target.get("class_type", String.class)) ? 1 : 2)
                    .collect(java.util.stream.Collectors.toSet());
            if (resolvedModes.size() != 1)
              throw new TeachingConflict("AUTO_SETTLEMENT_MODE_CLASS_TYPE_MISMATCH");
            enrollments.resolveSettlementMode(enrollment.id(), resolvedModes.iterator().next());
          }

          var schoolName = schools.require(schoolId).name();
          var result = new ArrayList<ClassView>();
          for (var target : classes) {
            var classId = target.get("id", UUID.class);
            boolean alreadyMember =
                sql.fetchOne(
                        "select 1 from teaching.enrollment_class where enrollment_id=? and class_id=? and active",
                        command.enrollmentId(),
                        classId)
                    != null;
            if (!alreadyMember) {
              sql.execute(
                  "insert into teaching.enrollment_class(tenant_id,enrollment_id,class_id,joined_on)"
                      + " values (?,?,?,CURRENT_DATE)",
                  scope.tenantId(),
                  command.enrollmentId(),
                  classId);
              audit(scope, "CLASS_MEMBER_ADDED", classId, schoolId, "members");
            }
            result.add(classView(requireClass(verified, classId), schoolName));
          }
          return List.copyOf(result);
        });
  }

  @Override
  public ClassView transferMember(UUID enrollmentId, TransferClassMember command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (enrollmentId == null
          || command == null
          || command.requestId() == null
          || command.expectedFromClassId() == null
          || command.toClassId() == null
          || command.expectedFromClassId().equals(command.toClassId()))
        throw new IllegalArgumentException("需要请求身份、原班、新班和报名");
      var reason = text(command.reason(), 200, "转班原因");
      if (reason == null) throw new IllegalArgumentException("转班原因必填");
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      var resourceScope = actors.requireResourceScope("TEACHING");
      if (!scope.tenantId().equals(resourceScope.tenantId())
          || !scope.accountId().equals(resourceScope.accountId()))
        throw new AccessDeniedException("无教务转班权限");
      var requestFingerprint =
          fingerprint(
              "ENROLLMENT_CLASS_TRANSFER",
              enrollmentId.toString(),
              command.expectedFromClassId().toString(),
              command.toClassId().toString(),
              Objects.toString(command.approvalId(), ""),
              reason);
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var verifiedTeaching = actors.requireResourceScopeInTransaction("TEACHING");
            var previous =
                previousRequest(
                    verifiedTeaching, command.requestId(), "CLASS_TRANSFER", requestFingerprint);
            if (previous != null) {
              var previousClass = requireClass(verifiedTeaching, previous);
              return classView(
                  previousClass,
                  schools.require(previousClass.get("school_id", UUID.class)).name());
            }

            var source = requireClass(verifiedTeaching, command.expectedFromClassId());
            var target = requireClass(verifiedTeaching, command.toClassId());
            var schoolId = source.get("school_id", UUID.class);
            if (!verified.schools().contains(schoolId)
                || !schoolId.equals(target.get("school_id", UUID.class)))
              throw new AccessDeniedException("转班仅支持同校班级");
            if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能转班");
            if (!"ACTIVE".equals(source.get("status", String.class))
                || !"ACTIVE".equals(target.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            var enrollment = enrollments.require(enrollmentId);
            if (!schoolId.equals(enrollment.schoolId()))
              throw new AccessDeniedException("报名不属于该学校");
            if (!"ACTIVE".equals(enrollment.status()))
              throw new TeachingConflict("ENROLLMENT_NOT_ACTIVE");
            if (!Objects.equals(
                source.get("curriculum_id", UUID.class), target.get("curriculum_id", UUID.class)))
              throw new TeachingConflict("TRANSFER_CURRICULUM_MISMATCH");
            resolveAutoMode(enrollment, command.toClassId());
            var membership =
                sql.fetchOne(
                    "select 1 from teaching.enrollment_class where enrollment_id=? and class_id=?"
                        + " and active for update",
                    enrollmentId,
                    command.expectedFromClassId());
            if (membership == null) throw new TeachingConflict("SOURCE_CLASS_MEMBERSHIP_CHANGED");
            var targetMembership =
                sql.fetchOne(
                    "select 1 from teaching.enrollment_class where enrollment_id=? and class_id=?"
                        + " and active for update",
                    enrollmentId,
                    command.toClassId());
            if (targetMembership != null) throw new TeachingConflict("TARGET_CLASS_ALREADY_ACTIVE");

            approvals.consume(
                "ENROLLMENT_TRANSFER",
                schoolId,
                enrollmentId,
                BigDecimal.ZERO,
                command.approvalId());
            sql.execute(
                "update teaching.enrollment_class set active=false where enrollment_id=?"
                    + " and class_id=? and active",
                enrollmentId,
                command.expectedFromClassId());
            int joined =
                sql.execute(
                    "insert into teaching.enrollment_class(tenant_id,enrollment_id,class_id,joined_on)"
                        + " values (?,?,?,CURRENT_DATE) on conflict (enrollment_id,class_id)"
                        + " do update set active=true,joined_on=excluded.joined_on"
                        + " where not teaching.enrollment_class.active",
                    scope.tenantId(),
                    enrollmentId,
                    command.toClassId());
            if (joined != 1) throw new TeachingConflict("TARGET_CLASS_ALREADY_ACTIVE");
            sql.execute(
                "insert into teaching.class_transfer(tenant_id,enrollment_id,from_class_id,to_class_id,"
                    + "approval_id,reason,actor_id) values (?,?,?,?,?,?,?)",
                scope.tenantId(),
                enrollmentId,
                command.expectedFromClassId(),
                command.toClassId(),
                command.approvalId(),
                reason,
                scope.accountId());
            rememberRequest(
                verifiedTeaching,
                command.requestId(),
                "CLASS_TRANSFER",
                command.toClassId(),
                requestFingerprint);
            audit(
                scope,
                "ENROLLMENT_CLASS_TRANSFERRED",
                enrollmentId,
                schoolId,
                command.expectedFromClassId() + "->" + command.toClassId());
            return classView(
                requireClass(verifiedTeaching, command.toClassId()),
                schools.require(schoolId).name());
          });
    }
  }

  @Override
  public ClassView withdrawMember(UUID enrollmentId, WithdrawClassMember command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (enrollmentId == null
          || command == null
          || command.requestId() == null
          || command.classId() == null) throw new IllegalArgumentException("需要请求身份、班级和报名");
      var reason = text(command.reason(), 200, "退班原因");
      if (reason == null) throw new IllegalArgumentException("退班原因必填");
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      var resourceScope = actors.requireResourceScope("TEACHING");
      if (!scope.tenantId().equals(resourceScope.tenantId())
          || !scope.accountId().equals(resourceScope.accountId()))
        throw new AccessDeniedException("无教务退班权限");
      var requestFingerprint =
          fingerprint(
              "ENROLLMENT_CLASS_WITHDRAWAL",
              enrollmentId.toString(),
              command.classId().toString(),
              Objects.toString(command.approvalId(), ""),
              reason);
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var verifiedTeaching = actors.requireResourceScopeInTransaction("TEACHING");
            var previous =
                previousRequest(
                    verifiedTeaching, command.requestId(), "CLASS_WITHDRAWAL", requestFingerprint);
            if (previous != null) {
              var previousClass = requireClass(verifiedTeaching, previous);
              return classView(
                  previousClass,
                  schools.require(previousClass.get("school_id", UUID.class)).name());
            }

            var classRow = requireClass(verifiedTeaching, command.classId());
            var schoolId = classRow.get("school_id", UUID.class);
            if (!verified.schools().contains(schoolId)) throw new AccessDeniedException("无权维护该校班级");
            if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能退班");
            if (!"ACTIVE".equals(classRow.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            var enrollment = enrollments.require(enrollmentId);
            if (!schoolId.equals(enrollment.schoolId()))
              throw new AccessDeniedException("报名不属于该学校");
            if (!"ACTIVE".equals(enrollment.status()))
              throw new TeachingConflict("ENROLLMENT_NOT_ACTIVE");
            var membership =
                sql.fetchOne(
                    "select 1 from teaching.enrollment_class where enrollment_id=? and class_id=?"
                        + " and active for update",
                    enrollmentId,
                    command.classId());
            if (membership == null) throw new TeachingConflict("CLASS_MEMBERSHIP_NOT_ACTIVE");

            approvals.consume(
                "ENROLLMENT_WITHDRAWAL",
                schoolId,
                enrollmentId,
                BigDecimal.ZERO,
                command.approvalId());
            int ended =
                sql.execute(
                    "update teaching.enrollment_class set active=false where enrollment_id=?"
                        + " and class_id=? and active",
                    enrollmentId,
                    command.classId());
            if (ended != 1) throw new TeachingConflict("CLASS_MEMBERSHIP_CHANGED");
            sql.execute(
                "insert into teaching.class_withdrawal(tenant_id,enrollment_id,class_id,approval_id,reason,actor_id)"
                    + " values (?,?,?,?,?,?)",
                scope.tenantId(),
                enrollmentId,
                command.classId(),
                command.approvalId(),
                reason,
                scope.accountId());
            rememberRequest(
                verifiedTeaching,
                command.requestId(),
                "CLASS_WITHDRAWAL",
                command.classId(),
                requestFingerprint);
            audit(
                scope,
                "ENROLLMENT_CLASS_WITHDRAWN",
                enrollmentId,
                schoolId,
                command.classId().toString());
            return classView(classRow, schools.require(schoolId).name());
          });
    }
  }

  // ---------------------------------------------------------------- 课次

  @Override
  public LessonView createLesson(CreateLesson command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createLessonWithTrace(command);
    }
  }

  private LessonView createLessonWithTrace(CreateLesson command) {
    if (command == null || command.requestId() == null || command.classId() == null)
      throw new IllegalArgumentException("需要请求身份和班级");
    var date = businessDate(command.lessonDate(), "上课日期");
    var startsAt = time(command.startsAt(), "开始时间");
    var endsAt = time(command.endsAt(), "结束时间");
    if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
    var lessonHours = positiveHours(command.hours(), "授课课时");
    var scope = actors.requireResourceScope("TEACHING");
    var fingerprint =
        fingerprint(
            "LESSON",
            command.classId().toString(),
            date.toString(),
            startsAt.toString(),
            endsAt.toString(),
            lessonHours.toPlainString());
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var row = requireClass(verified, command.classId());
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无课次维护权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能新建课次");
          if (!"ACTIVE".equals(row.get("status", String.class)))
            throw new TeachingConflict("CLASS_NOT_ACTIVE");
          var previous = previousRequest(scope, command.requestId(), "LESSON", fingerprint);
          if (previous != null) return lessonView(requireLesson(verified, previous));
          var teacher = resource(command.teacherId(), "TEACHER", schoolId, command.teacherName());
          var room = resource(command.roomId(), "ROOM", schoolId, command.roomName());
          var id = UUID.randomUUID();
          sql.execute(
              "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,"
                  + "teacher_name,room_name,topic,hours,teacher_id,room_id)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              scope.tenantId(),
              command.classId(),
              date,
              startsAt,
              endsAt,
              teacher.name(),
              room.name(),
              text(command.topic(), 100, "课题"),
              lessonHours,
              teacher.id(),
              room.id());
          rememberRequest(scope, command.requestId(), "LESSON", id, fingerprint);
          audit(scope, "LESSON_CREATED", id, schoolId, "");
          return lessonView(requireLesson(verified, id));
        });
  }

  @Override
  public LessonView publishLesson(UUID id, LessonVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return transition(id, command, "PUBLISHED", "LESSON_PUBLISHED", "TEACHING_MAINTAIN");
    }
  }

  @Override
  public LessonView rescheduleLesson(UUID id, RescheduleLesson command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null
          || command == null
          || command.requestId() == null
          || command.expectedVersion() == null) throw new IllegalArgumentException("需要课次、请求身份和版本");
      var date = businessDate(command.lessonDate(), "新上课日期");
      var startsAt = time(command.startsAt(), "新开始时间");
      var endsAt = time(command.endsAt(), "新结束时间");
      if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
      var reason = command.reason() == null ? "" : command.reason().strip();
      if (reason.isEmpty() || reason.length() > 200)
        throw new IllegalArgumentException("调课原因必填且不能超过 200 字");
      var actor = actors.requireAction("TEACHING_MAINTAIN");
      var digest =
          fingerprint(
              id.toString(),
              command.expectedVersion().toString(),
              date.toString(),
              startsAt.toString(),
              endsAt.toString(),
              reason);
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var row = requireLessonForUpdate(id);
            var schoolId = classSchool(row.get("class_id", UUID.class));
            if (!verified.schools().contains(schoolId))
              throw new AccessDeniedException("无该学校的教务维护权限");
            if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能调课");
            var previous =
                sql.fetchOne(
                    "select lesson_id,fingerprint from teaching.lesson_reschedule_request"
                        + " where tenant_id=? and actor_id=? and request_id=?",
                    actor.tenantId(),
                    actor.accountId(),
                    command.requestId());
            if (previous != null) {
              if (!id.equals(previous.get("lesson_id", UUID.class))
                  || !digest.equals(previous.get("fingerprint", String.class)))
                throw new TeachingConflict("IDEMPOTENCY_MISMATCH");
              return lessonView(requireLessonForUpdate(id));
            }
            if (!"PUBLISHED".equals(row.get("status", String.class)))
              throw new TeachingConflict("LESSON_NOT_RESCHEDULABLE");
            if (!command.expectedVersion().equals(row.get("version", Long.class)))
              throw new TeachingConflict("VERSION_CONFLICT");
            if (row.get("attendance_count", Integer.class) != 0
                || sql.fetchExists(
                    sql.selectOne().from("teaching.consumption").where("lesson_id=?", id)))
              throw new TeachingConflict("LESSON_HAS_BUSINESS_FACTS");
            var oldDate = row.get("lesson_date", LocalDate.class);
            var oldStartsAt = row.get("starts_at", LocalTime.class);
            var oldEndsAt = row.get("ends_at", LocalTime.class);
            if (oldDate.equals(date) && oldStartsAt.equals(startsAt) && oldEndsAt.equals(endsAt))
              throw new TeachingConflict("RESCHEDULE_NO_CHANGE");
            ensureLessonSlotAvailable(row, schoolId, date, startsAt, endsAt);
            int updated =
                sql.execute(
                    "update teaching.lesson set lesson_date=?,starts_at=?,ends_at=?,version=version+1"
                        + " where id=? and version=? and status='PUBLISHED'",
                    date,
                    startsAt,
                    endsAt,
                    id,
                    command.expectedVersion());
            if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
            sql.execute(
                "insert into teaching.lesson_reschedule(id,tenant_id,lesson_id,actor_id,request_id,"
                    + "old_lesson_date,old_starts_at,old_ends_at,new_lesson_date,new_starts_at,new_ends_at,reason)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                actor.tenantId(),
                id,
                actor.accountId(),
                command.requestId(),
                oldDate,
                oldStartsAt,
                oldEndsAt,
                date,
                startsAt,
                endsAt,
                reason);
            sql.execute(
                "insert into teaching.lesson_reschedule_request(tenant_id,actor_id,request_id,lesson_id,fingerprint)"
                    + " values (?,?,?,?,?)",
                actor.tenantId(),
                actor.accountId(),
                command.requestId(),
                id,
                digest);
            audit(verified, "LESSON_RESCHEDULED", id, schoolId, "schedule");
            return lessonView(requireLessonForUpdate(id));
          });
    }
  }

  @Override
  public LessonView swapLessons(UUID id, SwapLessons command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null
          || command == null
          || command.requestId() == null
          || command.otherLessonId() == null
          || command.expectedVersion() == null
          || command.expectedOtherVersion() == null
          || id.equals(command.otherLessonId()))
        throw new IllegalArgumentException("需要两个不同课次、版本和请求身份");
      var reason = command.reason() == null ? "" : command.reason().strip();
      if (reason.isEmpty() || reason.length() > 200)
        throw new IllegalArgumentException("换课原因必填且不能超过 200 字");
      var actor = actors.requireAction("TEACHING_MAINTAIN");
      var digest =
          fingerprint(
              "SWAP",
              id.toString(),
              command.otherLessonId().toString(),
              command.expectedVersion().toString(),
              command.expectedOtherVersion().toString(),
              reason);
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var previous =
                sql.fetchOne(
                    "select lesson_id,fingerprint from teaching.lesson_reschedule_request"
                        + " where tenant_id=? and actor_id=? and request_id=?",
                    actor.tenantId(),
                    actor.accountId(),
                    command.requestId());
            if (previous != null) {
              if (!id.equals(previous.get("lesson_id", UUID.class))
                  || !digest.equals(previous.get("fingerprint", String.class)))
                throw new TeachingConflict("IDEMPOTENCY_MISMATCH");
              return lessonView(requireLessonForUpdate(id));
            }
            var first = requireLessonForUpdate(id);
            var second = requireLessonForUpdate(command.otherLessonId());
            var classId = first.get("class_id", UUID.class);
            var schoolId = classSchool(classId);
            if (!classId.equals(second.get("class_id", UUID.class)))
              throw new TeachingConflict("SWAP_CLASS_MISMATCH");
            if (!verified.schools().contains(schoolId))
              throw new AccessDeniedException("无该学校的教务维护权限");
            if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能换课");
            if (!"PUBLISHED".equals(first.get("status", String.class))
                || !"PUBLISHED".equals(second.get("status", String.class)))
              throw new TeachingConflict("LESSON_NOT_RESCHEDULABLE");
            if (!command.expectedVersion().equals(first.get("version", Long.class))
                || !command.expectedOtherVersion().equals(second.get("version", Long.class)))
              throw new TeachingConflict("VERSION_CONFLICT");
            for (var row : List.of(first, second)) {
              if (row.get("attendance_count", Integer.class) != 0
                  || sql.fetchExists(
                      sql.selectOne()
                          .from("teaching.consumption")
                          .where("lesson_id=?", row.get("id", UUID.class))))
                throw new TeachingConflict("LESSON_HAS_BUSINESS_FACTS");
            }
            var firstDate = first.get("lesson_date", LocalDate.class);
            var firstStarts = first.get("starts_at", LocalTime.class);
            var firstEnds = first.get("ends_at", LocalTime.class);
            var secondDate = second.get("lesson_date", LocalDate.class);
            var secondStarts = second.get("starts_at", LocalTime.class);
            var secondEnds = second.get("ends_at", LocalTime.class);
            sql.execute(
                "update teaching.lesson set lesson_date=case id when cast(? as uuid) then cast(? as date)"
                    + " when cast(? as uuid) then cast(? as date) end,"
                    + "starts_at=case id when cast(? as uuid) then cast(? as time)"
                    + " when cast(? as uuid) then cast(? as time) end,"
                    + "ends_at=case id when cast(? as uuid) then cast(? as time)"
                    + " when cast(? as uuid) then cast(? as time) end,version=version+1"
                    + " where id in (?,?)",
                id,
                secondDate,
                command.otherLessonId(),
                firstDate,
                id,
                secondStarts,
                command.otherLessonId(),
                firstStarts,
                id,
                secondEnds,
                command.otherLessonId(),
                firstEnds,
                id,
                command.otherLessonId());
            ensureLessonSlotAvailable(
                requireLessonForUpdate(id), schoolId, secondDate, secondStarts, secondEnds);
            ensureLessonSlotAvailable(
                requireLessonForUpdate(command.otherLessonId()),
                schoolId,
                firstDate,
                firstStarts,
                firstEnds);
            var requestRow = UUID.randomUUID();
            sql.execute(
                "insert into teaching.lesson_reschedule(id,tenant_id,lesson_id,actor_id,request_id,"
                    + "old_lesson_date,old_starts_at,old_ends_at,new_lesson_date,new_starts_at,new_ends_at,reason)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
                requestRow,
                actor.tenantId(),
                id,
                actor.accountId(),
                command.requestId(),
                firstDate,
                firstStarts,
                firstEnds,
                secondDate,
                secondStarts,
                secondEnds,
                reason);
            sql.execute(
                "insert into teaching.lesson_reschedule(id,tenant_id,lesson_id,actor_id,request_id,"
                    + "old_lesson_date,old_starts_at,old_ends_at,new_lesson_date,new_starts_at,new_ends_at,reason)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                actor.tenantId(),
                command.otherLessonId(),
                actor.accountId(),
                command.requestId(),
                secondDate,
                secondStarts,
                secondEnds,
                firstDate,
                firstStarts,
                firstEnds,
                reason);
            sql.execute(
                "insert into teaching.lesson_reschedule_request(tenant_id,actor_id,request_id,lesson_id,fingerprint)"
                    + " values (?,?,?,?,?)",
                actor.tenantId(),
                actor.accountId(),
                command.requestId(),
                id,
                digest);
            audit(verified, "LESSON_SWAPPED", id, schoolId, "schedule");
            audit(verified, "LESSON_SWAPPED", command.otherLessonId(), schoolId, "schedule");
            return lessonView(requireLessonForUpdate(id));
          });
    }
  }

  @Override
  public LessonView createMakeupLesson(UUID sourceLessonId, CreateMakeupLesson command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (sourceLessonId == null || command == null || command.requestId() == null)
        throw new IllegalArgumentException("需要原课次和请求身份");
      var date = businessDate(command.lessonDate(), "补课日期");
      var startsAt = time(command.startsAt(), "补课开始时间");
      var endsAt = time(command.endsAt(), "补课结束时间");
      if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
      var reason = command.reason() == null ? "" : command.reason().strip();
      if (reason.isEmpty() || reason.length() > 200)
        throw new IllegalArgumentException("补课原因必填且不能超过 200 字");
      var actor = actors.requireAction("TEACHING_MAINTAIN");
      var digest =
          fingerprint(
              sourceLessonId.toString(),
              date.toString(),
              startsAt.toString(),
              endsAt.toString(),
              reason);
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var previous =
                sql.fetchOne(
                    "select source_lesson_id,makeup_lesson_id,fingerprint from teaching.lesson_makeup"
                        + " where tenant_id=? and actor_id=? and request_id=?",
                    actor.tenantId(),
                    actor.accountId(),
                    command.requestId());
            if (previous != null) {
              if (!sourceLessonId.equals(previous.get("source_lesson_id", UUID.class))
                  || !digest.equals(previous.get("fingerprint", String.class))) {
                throw new TeachingConflict("IDEMPOTENCY_MISMATCH");
              }
              return lessonView(
                  requireLessonForUpdate(previous.get("makeup_lesson_id", UUID.class)));
            }
            var source = requireLessonForUpdate(sourceLessonId);
            var classId = source.get("class_id", UUID.class);
            var schoolId = classSchool(classId);
            if (!verified.schools().contains(schoolId))
              throw new AccessDeniedException("无该学校的教务维护权限");
            if (!schools.require(schoolId).active())
              throw new AccessDeniedException("学校已停用，不能安排补课");
            if (!"CLOSED".equals(source.get("status", String.class)))
              throw new TeachingConflict("MAKEUP_SOURCE_NOT_CLOSED");
            if (!date.isAfter(source.get("lesson_date", LocalDate.class)))
              throw new TeachingConflict("MAKEUP_DATE_MUST_FOLLOW_SOURCE");
            if (!sql.fetchExists(
                sql.selectOne()
                    .from("teaching.attendance")
                    .where("lesson_id=? and status in ('ABSENT','LEAVE')", sourceLessonId)))
              throw new TeachingConflict("MAKEUP_REQUIRES_ABSENCE");
            var classRow = sql.fetchOne("select status from teaching.class where id=?", classId);
            if (classRow == null || !"ACTIVE".equals(classRow.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            ensureLessonSlotAvailable(source, schoolId, date, startsAt, endsAt);
            var makeupId = UUID.randomUUID();
            var sourceTopic = source.get("topic", String.class);
            var topic = sourceTopic == null || sourceTopic.isBlank() ? "补课" : sourceTopic + "（补课）";
            if (topic.length() > 100) topic = topic.substring(0, 100);
            sql.execute(
                "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,"
                    + "teacher_name,room_name,topic,hours,teacher_id,room_id) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                makeupId,
                actor.tenantId(),
                classId,
                date,
                startsAt,
                endsAt,
                source.get("teacher_name", String.class),
                source.get("room_name", String.class),
                topic,
                source.get("hours", BigDecimal.class),
                source.get("teacher_id", UUID.class),
                source.get("room_id", UUID.class));
            sql.execute(
                "insert into teaching.lesson_makeup(tenant_id,source_lesson_id,makeup_lesson_id,actor_id,request_id,fingerprint,reason)"
                    + " values (?,?,?,?,?,?,?)",
                actor.tenantId(),
                sourceLessonId,
                makeupId,
                actor.accountId(),
                command.requestId(),
                digest,
                reason);
            audit(verified, "LESSON_MAKEUP_CREATED", makeupId, schoolId, sourceLessonId.toString());
            return lessonView(requireLessonForUpdate(makeupId));
          });
    }
  }

  private void ensureLessonSlotAvailable(
      Record lesson, UUID schoolId, LocalDate date, LocalTime startsAt, LocalTime endsAt) {
    var id = lesson.get("id", UUID.class);
    var classId = lesson.get("class_id", UUID.class);
    var teacherId = lesson.get("teacher_id", UUID.class);
    var teacherName = lesson.get("teacher_name", String.class);
    var roomId = lesson.get("room_id", UUID.class);
    var roomName = lesson.get("room_name", String.class);
    if (sql.fetchExists(
        sql.selectOne()
            .from("teaching.lesson")
            .where(
                "class_id=? and id<>? and lesson_date=? and starts_at<? and ends_at>?",
                classId,
                id,
                date,
                endsAt,
                startsAt))) throw new TeachingConflict("LESSON_OVERLAP");
    var policy =
        sql.fetchOne(
            "select block_teacher_unavailable,block_room_unavailable,min_gap_minutes"
                + " from teaching.schedule_policy where school_id=?",
            schoolId);
    boolean blockTeacher = policy == null || policy.get("block_teacher_unavailable", Boolean.class);
    boolean blockRoom = policy == null || policy.get("block_room_unavailable", Boolean.class);
    int minGap = policy == null ? 0 : policy.get("min_gap_minutes", Integer.class);
    if (teacherId != null || teacherName != null) {
      var clash =
          sql.fetchExists(
              sql.selectOne()
                  .from("teaching.lesson")
                  .where(
                      "id<>? and lesson_date=? and starts_at<? and ends_at>? and (teacher_id=? or teacher_name=?)",
                      id,
                      date,
                      endsAt,
                      startsAt,
                      teacherId,
                      teacherName));
      if (clash) throw new TeachingConflict("TEACHER_CLASH");
      if (minGap > 0) {
        var gapClash =
            sql.fetchExists(
                sql.selectOne()
                    .from("teaching.lesson")
                    .where(
                        "id<>? and lesson_date=? and (teacher_id=? or teacher_name=?) and ends_at>? and starts_at<?",
                        id,
                        date,
                        teacherId,
                        teacherName,
                        startsAt.minusMinutes(minGap),
                        endsAt.plusMinutes(minGap)));
        if (gapClash) throw new TeachingConflict("TEACHER_MIN_GAP");
      }
      if (blockTeacher
          && sql.fetchExists(
              sql.selectOne()
                  .from("teaching.resource_block")
                  .where(
                      "teacher_id=? and block_date=? and starts_at<? and ends_at>?",
                      teacherId,
                      date,
                      endsAt,
                      startsAt))) throw new TeachingConflict("TEACHER_UNAVAILABLE");
    }
    if (roomId != null || roomName != null) {
      if (sql.fetchExists(
          sql.selectOne()
              .from("teaching.lesson")
              .where(
                  "id<>? and lesson_date=? and starts_at<? and ends_at>? and (room_id=? or room_name=?)",
                  id,
                  date,
                  endsAt,
                  startsAt,
                  roomId,
                  roomName))) throw new TeachingConflict("ROOM_CLASH");
      if (blockRoom
          && sql.fetchExists(
              sql.selectOne()
                  .from("teaching.resource_block")
                  .where(
                      "room_id=? and block_date=? and starts_at<? and ends_at>?",
                      roomId,
                      date,
                      endsAt,
                      startsAt))) throw new TeachingConflict("ROOM_UNAVAILABLE");
    }
  }

  @Override
  public LessonView confirmAttendance(UUID id, ConfirmAttendance command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return confirmWithTrace(id, command);
    }
  }

  private LessonView confirmWithTrace(UUID id, ConfirmAttendance command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要课次和版本");
    var record = actors.requireAction("ATTENDANCE_RECORD");
    return tx.execute(
        record.tenantId(),
        () -> {
          schools.lockTenant(record.tenantId());
          var verified = actors.requireActionInTransaction("ATTENDANCE_RECORD");
          var row = requireLessonForUpdate(id);
          var schoolId = classSchool(row.get("class_id", UUID.class));
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的考勤登记权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能登记考勤");
          if (!"PUBLISHED".equals(row.get("status", String.class)))
            throw new TeachingConflict("LESSON_NOT_PUBLISHED");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new TeachingConflict("VERSION_CONFLICT");
          var members =
              sql.fetch(
                      "select enrollment_id from teaching.enrollment_class where class_id=? and active"
                          + " order by enrollment_id",
                      row.get("class_id", UUID.class))
                  .map(item -> item.get("enrollment_id", UUID.class));
          if (members.isEmpty()) throw new TeachingConflict("CLASS_HAS_NO_MEMBER");
          var provided = new HashMap<UUID, AttendanceInput>();
          for (var input : command.records()) {
            if (input == null
                || input.enrollmentId() == null
                || !ATTENDANCE.contains(input.status()))
              throw new IllegalArgumentException("考勤状态无效");
            if ("PARTIAL".equals(input.status())) {
              var duration =
                  java.time.Duration.between(
                          row.get("starts_at", LocalTime.class),
                          row.get("ends_at", LocalTime.class))
                      .toMinutes();
              if (input.attendedMinutes() == null
                  || input.attendedMinutes() <= 0
                  || input.attendedMinutes() >= duration)
                throw new IllegalArgumentException("部分缺勤分钟必须大于 0 且少于课次时长");
              var billableHours =
                  row.get("hours", BigDecimal.class)
                      .multiply(BigDecimal.valueOf(input.attendedMinutes()))
                      .divide(BigDecimal.valueOf(duration), 2, RoundingMode.HALF_UP);
              if (billableHours.signum() <= 0)
                throw new IllegalArgumentException("部分缺勤时间过短，按当前课时精度无法计费");
            } else if (input.attendedMinutes() != null) {
              throw new IllegalArgumentException("仅部分缺勤可以填写实际出勤分钟");
            }
            if (provided.put(input.enrollmentId(), input) != null)
              throw new IllegalArgumentException("同一次考勤出现重复报名");
          }
          if (!provided.keySet().equals(new HashSet<>(members)))
            throw new TeachingConflict("ATTENDANCE_MUST_COVER_MEMBERS");
          var makeup =
              sql.fetchOne(
                  "select source_lesson_id from teaching.lesson_makeup where makeup_lesson_id=?",
                  id);
          if (makeup != null) {
            var eligible =
                new HashSet<UUID>(
                    sql.fetch(
                            "select enrollment_id from teaching.attendance where lesson_id=?"
                                + " and status in ('ABSENT','LEAVE')",
                            makeup.get("source_lesson_id", UUID.class))
                        .map(item -> item.get("enrollment_id", UUID.class)));
            if (provided.values().stream()
                .anyMatch(
                    item ->
                        ("PRESENT".equals(item.status()) || "PARTIAL".equals(item.status()))
                            && !eligible.contains(item.enrollmentId())))
              throw new TeachingConflict("MAKEUP_ATTENDANCE_NOT_ELIGIBLE");
          }
          for (var entry : provided.entrySet())
            sql.execute(
                "insert into teaching.attendance(tenant_id,lesson_id,enrollment_id,status,attended_minutes)"
                    + " values (?,?,?,?,?) on conflict (lesson_id,enrollment_id)"
                    + " do update set status=excluded.status,attended_minutes=excluded.attended_minutes,"
                    + " recorded_at=CURRENT_TIMESTAMP",
                record.tenantId(),
                id,
                entry.getKey(),
                entry.getValue().status(),
                entry.getValue().attendedMinutes());
          int updated =
              sql.execute(
                  "update teaching.lesson set status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP,"
                      + "version=version+1 where id=? and version=? and status='PUBLISHED'",
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
          audit(record, "ATTENDANCE_CONFIRMED", id, schoolId, "attendance");
          return lessonView(requireLessonForUpdate(id));
        });
  }

  @Override
  public LessonView closeLesson(UUID id, LessonVersion command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return closeWithTrace(id, command);
    }
  }

  private LessonView closeWithTrace(UUID id, LessonVersion command) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要课次和版本");
    var close = actors.requireAction("LESSON_CLOSE");
    return tx.execute(
        close.tenantId(),
        () -> {
          schools.lockTenant(close.tenantId());
          var verified = actors.requireActionInTransaction("LESSON_CLOSE");
          var row = requireLessonForUpdate(id);
          var schoolId = classSchool(row.get("class_id", UUID.class));
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的课次关闭权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能关闭课次");
          if (!"CONFIRMED".equals(row.get("status", String.class)))
            throw new TeachingConflict("LESSON_NOT_CONFIRMED");
          // 结算模式防护：历史数据若是未实现的模式，宁可拒绝消费也不按模式 1 静默计算。
          // 出勤名单在下面读取，这里先做一次同源校验。
          // 课次消费按上课日期归属期间；该月已锁定则拒绝关闭。
          periods.requireOpen(schoolId, row.get("lesson_date", LocalDate.class));
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new TeachingConflict("VERSION_CONFLICT");
          var lessonHours = row.get("hours", BigDecimal.class);
          var lessonDate = row.get("lesson_date", LocalDate.class);
          var attending =
              sql.fetch(
                  "select a.enrollment_id,a.status,a.attended_minutes from teaching.attendance a"
                      + " where a.lesson_id=? and a.status in ('PRESENT','PARTIAL')",
                  id);
          var lessonDurationMinutes =
              java.time.Duration.between(
                      row.get("starts_at", LocalTime.class), row.get("ends_at", LocalTime.class))
                  .toMinutes();
          var lessonCurriculum =
              sql.fetchOne(
                      "select curriculum_id from teaching.class where id=?",
                      row.get("class_id", UUID.class))
                  .get("curriculum_id", UUID.class);
          for (var item : attending) {
            var enrollmentId = item.get("enrollment_id", UUID.class);
            var consumedLessonHours = lessonHours;
            if ("PARTIAL".equals(item.get("status", String.class))) {
              consumedLessonHours =
                  lessonHours
                      .multiply(BigDecimal.valueOf(item.get("attended_minutes", Integer.class)))
                      .divide(BigDecimal.valueOf(lessonDurationMinutes), 2, RoundingMode.HALF_UP);
              if (consumedLessonHours.signum() <= 0)
                throw new TeachingConflict("PARTIAL_ATTENDANCE_TOO_SHORT_TO_BILL");
            }
            var enrollment = enrollments.require(enrollmentId);
            var mode = enrollment.settlementMode();
            if (enrollment.validUntil() != null && lessonDate.isAfter(enrollment.validUntil()))
              throw new TeachingConflict("ENROLLMENT_TERM_EXPIRED");
            if (mode < 1 || mode > 5) throw new TeachingConflict("SETTLEMENT_MODE_UNSUPPORTED");
            // 课程维度模式：2/3/5 只对「报名的课程」计价，课次课程不在明细内则本期不产生消耗。
            var perCourse = mode == 2 || mode == 3 || mode == 5;
            UUID consumedCurriculum = null;
            BigDecimal fee = enrollment.price();
            BigDecimal feeHours = enrollment.hours();
            BigDecimal capHours = enrollment.hours().subtract(enrollment.cancelledHours());
            BigDecimal courseRefund = BigDecimal.ZERO;
            if (perCourse) {
              var line =
                  enrollment.courses().stream()
                      .filter(course -> course.curriculumId().equals(lessonCurriculum))
                      .findFirst()
                      .orElse(null);
              if (line == null) continue;
              consumedCurriculum = line.curriculumId();
              fee = line.amount();
              feeHours = line.hours();
              courseRefund = line.cancelledAmount();
              if (mode == 3 || mode == 5) capHours = line.hours().subtract(line.cancelledHours());
            }
            // 模式 4/5 是"退课后实际费用"变体：先扣掉已退费用与已退课时，再算单价。
            // 模式 1（平均价格）在旧系统里就是"总价 − 优惠"再除以总课时
            // （FClassStudent#getConsumeEHM: fee = getTotalPrice() - getPriceDiscount()），这里对齐。
            if (mode == 1) {
              fee = fee.subtract(discountTotal(enrollment));
              if (fee.signum() < 0) fee = BigDecimal.ZERO;
            }
            // 模式 4/5 是"退课后实际费用"变体：先扣优惠，再扣已退费用与已退课时。
            // 优惠只减少应收，不摊进服务费（服务费按既有口径不摊销）。
            if (mode == 4) {
              fee = fee.subtract(discountTotal(enrollment)).subtract(cancelledAmount(enrollmentId));
              feeHours = feeHours.subtract(enrollment.cancelledHours());
            } else if (mode == 5 && consumedCurriculum != null) {
              // 旧系统模式 5 只减已退费用、不减优惠（FClassStudent#getConsumeEHM），先照旧系统。
              // 这会带来"课程模式消耗可能超过折后应收"的疑点，已登记为 Q35 待确认。
              for (var course : enrollment.courses())
                if (course.curriculumId().equals(consumedCurriculum)) {
                  fee = fee.subtract(course.cancelledAmount());
                  feeHours = feeHours.subtract(course.cancelledHours());
                  break;
                }
            }
            // 优惠可能把应收压到 0（甚至因数据问题变负）：不能算出负数单价。
            if (fee.signum() < 0) fee = BigDecimal.ZERO;
            if (feeHours.signum() <= 0) continue;
            var totalConsumed = consumedHours(enrollmentId);
            var courseConsumed = consumedHours(enrollmentId, consumedCurriculum);
            // 封顶：模式 3/5 按（净）课程课时，模式 1/2/4 按（净）总课时。
            var byCourse = mode == 3 || mode == 5;
            var consumed = byCourse ? courseConsumed : totalConsumed;
            // 计价分母与封顶上限分别表达：模式 2 取课程单价，仍按报名净总课时封顶。
            var totalRemaining =
                enrollment.hours().subtract(enrollment.cancelledHours()).subtract(totalConsumed);
            var remaining = capHours.subtract(consumed).min(totalRemaining);
            if (remaining.signum() <= 0) continue;
            // 拼接/截断规则未确认前明确拒绝跨界，整个关课事务回滚，不能整节超耗。
            if (consumedLessonHours.compareTo(remaining) > 0)
              throw new TeachingConflict("LESSON_HOURS_EXCEED_REMAINING");
            var unit = fee.divide(feeHours, 6, RoundingMode.HALF_UP);
            var amount = unit.multiply(consumedLessonHours).setScale(2, RoundingMode.HALF_UP);
            // 尾差修正：最后一段课时消耗时补齐到该范围内的应收总额。
            var trailingFee =
                mode == 1
                    ? fee.subtract(cancelledAmount(enrollmentId))
                    : mode == 3 ? fee.subtract(courseRefund) : fee;
            if (byCourse && courseConsumed.add(consumedLessonHours).compareTo(capHours) == 0)
              amount = trailingFee.subtract(consumedAmount(enrollmentId, consumedCurriculum));
            else if (mode != 2
                && !byCourse
                && totalConsumed.add(consumedLessonHours).compareTo(capHours) == 0)
              amount = trailingFee.subtract(consumedAmount(enrollmentId, null));
            if (amount.signum() < 0) amount = BigDecimal.ZERO;
            sql.execute(
                "insert into teaching.consumption(tenant_id,enrollment_id,lesson_id,hours,amount,"
                    + "settlement_mode,price_snapshot,hours_snapshot,curriculum_id)"
                    + " values (?,?,?,?,?,?,?,?,?)"
                    // 唯一约束改成了"仅课次消费"的部分索引，冲突推断需要带同样的谓词。
                    + " on conflict (enrollment_id,lesson_id) where source='LESSON' do nothing",
                close.tenantId(),
                enrollmentId,
                id,
                consumedLessonHours,
                amount,
                mode,
                fee,
                feeHours,
                consumedCurriculum);
          }
          int updated =
              sql.execute(
                  "update teaching.lesson set status='CLOSED',closed_at=CURRENT_TIMESTAMP,"
                      + "version=version+1 where id=? and version=? and status='CONFIRMED'",
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
          audit(
              close, "LESSON_CLOSED", id, schoolId, lessonDate + "/" + lessonHours.toPlainString());
          return lessonView(requireLessonForUpdate(id));
        });
  }

  // ---------------------------------------------------------------- 内部

  private LessonView transition(
      UUID id, LessonVersion command, String to, String action, String requiredAction) {
    if (id == null || command == null || command.expectedVersion() == null)
      throw new IllegalArgumentException("需要课次和版本");
    var actor = actors.requireAction(requiredAction);
    return tx.execute(
        actor.tenantId(),
        () -> {
          schools.lockTenant(actor.tenantId());
          var verified = actors.requireActionInTransaction(requiredAction);
          var row = requireLessonForUpdate(id);
          var schoolId = classSchool(row.get("class_id", UUID.class));
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的课次操作权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能操作课次");
          if (!"DRAFT".equals(row.get("status", String.class)))
            throw new TeachingConflict("LESSON_NOT_DRAFT");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new TeachingConflict("VERSION_CONFLICT");
          int updated =
              sql.execute(
                  "update teaching.lesson set status=?,published_at=CURRENT_TIMESTAMP,version=version+1"
                      + " where id=? and version=? and status='DRAFT'",
                  to,
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
          audit(verified, action, id, schoolId, "status");
          return lessonView(requireLessonForUpdate(id));
        });
  }

  private Set<UUID> schoolsOf(String action) {
    try {
      return actors.requireAction(action).schools();
    } catch (AccessDeniedException denied) {
      return Set.of();
    }
  }

  private void requireReadable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的教务读取权限");
  }

  private void requireMaintainable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的教务读取权限");
    if (!scope.maintainableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的教务维护权限");
  }

  private Record requireClass(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select c.id,c.school_id,c.curriculum_id,c.name,c.status,c.version,c.group_training,c.class_type,"
                + "cur.name as curriculum_name,"
                + "(select count(*) from teaching.enrollment_class m where m.class_id=c.id and m.active)"
                + " as member_count from teaching.class c"
                + " join product.curriculum cur on cur.id=c.curriculum_id where c.id=?",
            id);
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("班级不存在或不可访问");
    return row;
  }

  private Record requireLesson(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,l.teacher_name,l.room_name,"
                + "l.topic,l.hours,l.status,l.version,l.teacher_id,l.room_id,"
                + "(select count(*) from teaching.attendance a where a.lesson_id=l.id) as attendance_count"
                + " from teaching.lesson l where l.id=?",
            id);
    if (row == null) throw new AccessDeniedException("课次不存在或不可访问");
    if (!scope.readableSchoolIds().contains(classSchool(row.get("class_id", UUID.class))))
      throw new AccessDeniedException("课次不存在或不可访问");
    return row;
  }

  private Record requireLessonForUpdate(UUID id) {
    var row =
        sql.fetchOne(
            "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,l.teacher_name,l.room_name,"
                + "l.topic,l.hours,l.status,l.version,l.teacher_id,l.room_id,"
                + "(select count(*) from teaching.attendance a where a.lesson_id=l.id) as attendance_count"
                + " from teaching.lesson l where l.id=? for update",
            id);
    if (row == null) throw new AccessDeniedException("课次不存在或不可访问");
    return row;
  }

  private UUID classSchool(UUID classId) {
    var row = sql.fetchOne("select school_id from teaching.class where id=?", classId);
    if (row == null) throw new AccessDeniedException("班级不存在或不可访问");
    return row.get("school_id", UUID.class);
  }

  private ClassView classView(Record row, String schoolName) {
    return new ClassView(
        row.get("id", UUID.class),
        row.get("school_id", UUID.class),
        schoolName,
        row.get("curriculum_id", UUID.class),
        row.get("curriculum_name", String.class),
        row.get("name", String.class),
        row.get("status", String.class),
        row.get("group_training", Boolean.class),
        row.get("member_count", Integer.class),
        row.get("version", Long.class));
  }

  /** 课次的教师/教室：给了实体就校验并取名称快照，否则退回自由文本。 */
  private ResourceRef resource(UUID id, String kind, UUID schoolId, String fallbackName) {
    if (id == null) return new ResourceRef(null, text(fallbackName, 50, "教师"));
    var table = "TEACHER".equals(kind) ? "organization.teacher" : "organization.room";
    var row =
        sql.fetchOne(
            "select id,name,active from " + table + " where id=? and school_id=?", id, schoolId);
    if (row == null) throw new TeachingConflict("RESOURCE_NOT_IN_SCHOOL");
    if (!row.get("active", Boolean.class)) throw new TeachingConflict("RESOURCE_INACTIVE");
    return new ResourceRef(row.get("id", UUID.class), row.get("name", String.class));
  }

  private record ResourceRef(UUID id, String name) {}

  /**
   * 结算模式 0（自动）的解析：VIP 走平均价格(1)，其余（大班/普通）走课程匹配(2)。
   *
   * <p>只在声明值为 0 且尚未解析时写入一次，之后消费与退费共用同一口径。
   */
  private void resolveAutoMode(
      org.teraschool.enrollment.api.EnrollmentAccess.EnrollmentRef enrollment, UUID classId) {
    if (enrollment.settlementMode() != 0) return;
    var row = sql.fetchOne("select class_type from teaching.class where id=?", classId);
    var type = row == null ? "NORMAL" : row.get("class_type", String.class);
    enrollments.resolveSettlementMode(enrollment.id(), "VIP".equals(type) ? 1 : 2);
  }

  private String classType(String value) {
    var text =
        value == null || value.isBlank()
            ? "NORMAL"
            : value.strip().toUpperCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("VIP", "LARGE", "NORMAL").contains(text))
      throw new IllegalArgumentException("班型须为 VIP / LARGE / NORMAL");
    return text;
  }

  @Override
  public String exportLessonsCsv(UUID schoolId, String from, String to) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireResourceScope("TEACHING");
      if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
        throw new AccessDeniedException("无该学校的课次读取权限");
      var start = from == null || from.isBlank() ? LocalDate.now() : businessDate(from, "开始日期");
      var end = to == null || to.isBlank() ? start.plusDays(60) : businessDate(to, "结束日期");
      return tx.execute(
          scope.tenantId(),
          () -> {
            var rows =
                sql.fetch(
                    "select c.name as class_name,cl.curriculum_id,l.lesson_date,l.starts_at,"
                        + "l.ends_at,l.teacher_name,l.room_name,l.hours,l.status"
                        + " from teaching.lesson l"
                        + " join teaching.class c on c.id=l.class_id"
                        + " left join teaching.class cl on cl.id=l.class_id"
                        + " where c.school_id=? and l.lesson_date between ? and ?"
                        + " order by l.lesson_date, l.starts_at limit 5001",
                    schoolId,
                    start,
                    end);
            org.teraschool.platform.api.CsvExport.requireComplete(rows.size());
            var csv = new StringBuilder();
            csv.append("班级,日期,开始,结束,教师,教室,课时,状态").append(NEWLINE);
            for (var row : rows) {
              csv.append(cell(row.get("class_name", String.class)))
                  .append(',')
                  .append(cell(date(row.get("lesson_date", LocalDate.class))))
                  .append(',')
                  .append(cell(time(row.get("starts_at", LocalTime.class))))
                  .append(',')
                  .append(cell(time(row.get("ends_at", LocalTime.class))))
                  .append(',')
                  .append(cell(row.get("teacher_name", String.class)))
                  .append(',')
                  .append(cell(row.get("room_name", String.class)))
                  .append(',')
                  .append(cell(hours(row.get("hours", BigDecimal.class))))
                  .append(',')
                  .append(cell(row.get("status", String.class)))
                  .append(NEWLINE);
            }
            auditLessonsExport(scope, schoolId, rows.size());
            return csv.toString();
          });
    }
  }

  private void auditLessonsExport(ResourceScope scope, UUID schoolId, int rows) {
    sql.execute(
        "insert into audit.teaching_event(tenant_id,actor_id,action,resource_id,school_id,"
            + "changed_fields,trace_id) values (?,?,'LESSONS_EXPORTED',?,?,?,?)",
        scope.tenantId(),
        scope.accountId(),
        schoolId,
        schoolId,
        "rows=" + rows,
        RequestTrace.id());
  }

  private static final char NEWLINE = (char) 10;

  private String cell(String value) {
    return org.teraschool.platform.api.CsvExport.cell(value);
  }

  @Override
  public void openingConsumption(UUID enrollmentId, OpeningConsumption command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      actors.requireAction("MIGRATION_MANAGE");
      tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var migrationScope = actors.requireActionInTransaction("MIGRATION_MANAGE");
            if (enrollmentId == null || command == null)
              throw new IllegalArgumentException("需要报名与期初消耗");
            var enrollment = enrollments.require(enrollmentId);
            if (!verified.schools().contains(enrollment.schoolId())
                || !migrationScope.schools().contains(enrollment.schoolId()))
              throw new AccessDeniedException("无该学校的课次维护权限");
            if (!schools.require(enrollment.schoolId()).active())
              throw new AccessDeniedException("学校已停用，不能登记期初消费");
            var occurredOn = businessDate(command.occurredOn(), "期初日期");
            if (enrollment.validUntil() != null && occurredOn.isAfter(enrollment.validUntil()))
              throw new TeachingConflict("ENROLLMENT_TERM_EXPIRED");
            periods.requireOpen(enrollment.schoolId(), occurredOn);
            var hours = positiveHours(command.hours(), "期初消耗课时");
            // 旧库可能只有课时没有金额：允许 0，但不接受负数。
            var amount = nonNegativeAmount(command.amount());
            var existing =
                sql.fetchOne(
                    "select id from teaching.consumption where enrollment_id=? and source='OPENING'",
                    enrollmentId);
            if (existing != null) throw new TeachingConflict("OPENING_ALREADY_RECORDED");
            var consumed = consumedHours(enrollmentId);
            if (consumed
                    .add(hours)
                    .compareTo(enrollment.hours().subtract(enrollment.cancelledHours()))
                > 0) throw new TeachingConflict("HOURS_EXCEED_PURCHASED");
            sql.execute(
                "insert into teaching.consumption(tenant_id,enrollment_id,lesson_id,hours,amount,"
                    + "settlement_mode,price_snapshot,hours_snapshot,source,opening_occurred_on)"
                    + " values (?,?,null,?,?,?,?,?,'OPENING',?)",
                scope.tenantId(),
                enrollmentId,
                hours,
                amount,
                enrollment.settlementMode(),
                enrollment.price(),
                enrollment.hours(),
                occurredOn);
            audit(
                verified,
                "OPENING_CONSUMPTION_POSTED",
                enrollmentId,
                enrollment.schoolId(),
                hours.toPlainString() + "/" + amount.toPlainString());
            return null;
          });
    }
  }

  private LessonView lessonView(Record row) {
    var id = row.get("id", UUID.class);
    var makeup =
        sql.fetchOne(
            "select source_lesson_id from teaching.lesson_makeup where makeup_lesson_id=?", id);
    var makeupSourceId = makeup == null ? null : makeup.get("source_lesson_id", UUID.class);
    var makeupEnrollmentIds =
        makeupSourceId == null
            ? List.<UUID>of()
            : sql.fetch(
                    "select enrollment_id from teaching.attendance where lesson_id=?"
                        + " and status in ('ABSENT','LEAVE') order by enrollment_id",
                    makeupSourceId)
                .map(item -> item.get("enrollment_id", UUID.class));
    return new LessonView(
        id,
        row.get("class_id", UUID.class),
        date(row.get("lesson_date", LocalDate.class)),
        time(row.get("starts_at", LocalTime.class)),
        time(row.get("ends_at", LocalTime.class)),
        row.get("teacher_name", String.class),
        row.get("room_name", String.class),
        row.get("topic", String.class),
        hours(row.get("hours", BigDecimal.class)),
        row.get("status", String.class),
        row.get("version", Long.class),
        row.get("attendance_count", Integer.class),
        row.get("teacher_id", UUID.class),
        row.get("room_id", UUID.class),
        makeupSourceId,
        makeupEnrollmentIds);
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
  }

  private BigDecimal nonNegativeAmount(String value) {
    if (value == null || value.isBlank()) return BigDecimal.ZERO.setScale(2);
    var text = value.strip();
    if (!HOURS.matcher(text).matches()) throw new IllegalArgumentException("金额须为最多两位小数的十进制数");
    var amount = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    if (amount.signum() < 0) throw new IllegalArgumentException("金额不能为负");
    return amount;
  }

  /** 报名累计优惠。不能用"价格 + 服务费 − 净应收"推：净应收里还混着退课减项。 */
  private BigDecimal discountTotal(EnrollmentAccess.EnrollmentRef enrollment) {
    var discount = enrollment.discount();
    return discount == null || discount.signum() < 0 ? BigDecimal.ZERO : discount;
  }

  private BigDecimal positiveHours(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    var text = value.strip();
    if (!HOURS.matcher(text).matches()) throw new IllegalArgumentException(label + "须为最多两位小数的十进制数");
    var hours = new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY);
    if (hours.signum() <= 0) throw new IllegalArgumentException(label + "必须大于零");
    return hours;
  }

  private LocalDate businessDate(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException(label + "须为 YYYY-MM-DD");
    }
  }

  private LocalTime time(String value, String label) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
    try {
      return LocalTime.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException(label + "须为 HH:mm");
    }
  }

  private String date(LocalDate value) {
    return value == null ? null : value.toString();
  }

  private String time(LocalTime value) {
    return value == null ? null : value.toString();
  }

  /** 报名级已退费用（模式 4 用它把应收降到"退课后实际费用"）。 */
  private BigDecimal cancelledAmount(UUID enrollmentId) {
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from enrollment.hours_cancellation"
                    + " where enrollment_id=?",
                enrollmentId)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal consumedHours(UUID enrollmentId) {
    return consumedHours(enrollmentId, null);
  }

  private BigDecimal consumedHours(UUID enrollmentId, UUID curriculumId) {
    var filter = curriculumId == null ? "" : " and curriculum_id=?";
    var args =
        curriculumId == null
            ? new Object[] {enrollmentId}
            : new Object[] {enrollmentId, curriculumId};
    var value =
        sql.fetchOne(
                "select coalesce(sum(hours),0) as total from teaching.consumption where enrollment_id=?"
                    + filter,
                args)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private BigDecimal consumedAmount(UUID enrollmentId, UUID curriculumId) {
    var filter = curriculumId == null ? "" : " and curriculum_id=?";
    var args =
        curriculumId == null
            ? new Object[] {enrollmentId}
            : new Object[] {enrollmentId, curriculumId};
    var value =
        sql.fetchOne(
                "select coalesce(sum(amount),0) as total from teaching.consumption where enrollment_id=?"
                    + filter,
                args)
            .get("total", BigDecimal.class);
    return value == null ? BigDecimal.ZERO : value;
  }

  private String hours(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  private UUID previousRequest(
      ResourceScope scope, UUID requestId, String kind, String fingerprint) {
    var row =
        sql.fetchOne(
            "select resource_id,fingerprint,kind from teaching.creation_request"
                + " where tenant_id=? and actor_id=? and request_id=?",
            scope.tenantId(),
            scope.accountId(),
            requestId);
    if (row == null) return null;
    if (!fingerprint.equals(row.get("fingerprint", String.class))
        || !kind.equals(row.get("kind", String.class)))
      throw new TeachingConflict("IDEMPOTENCY_MISMATCH");
    return row.get("resource_id", UUID.class);
  }

  private void rememberRequest(
      ResourceScope scope, UUID requestId, String kind, UUID resource, String fingerprint) {
    sql.execute(
        "insert into teaching.creation_request(tenant_id,actor_id,request_id,kind,resource_id,fingerprint)"
            + " values (?,?,?,?,?,?)",
        scope.tenantId(),
        scope.accountId(),
        requestId,
        kind,
        resource,
        fingerprint);
  }

  private boolean uniqueViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof java.sql.SQLException sqlError && "23505".equals(sqlError.getSQLState()))
        return true;
    return false;
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
        "insert into audit.teaching_event(tenant_id,actor_id,action,resource_id,school_id,changed_fields,trace_id)"
            + " values (?,?,?,?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        action,
        resource,
        school,
        fields,
        RequestTrace.id());
  }
}
