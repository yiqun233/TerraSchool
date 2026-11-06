package org.teraschool.teaching.internal;

import static org.teraschool.teaching.api.TeachingQueries.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.enrollment.api.EnrollmentAccess;
import org.teraschool.enrollment.api.EnrollmentQueries;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.*;

// 报表返回类型是 TeachingQueries 的嵌套 record，静态导入后才可裸用。

/**
 * 教务只读报表：教师本人课表与班级工作台、资源日报、学员消费、班级消费、考勤统计、综合情况。
 *
 * <p>与 TeachingService 分开，是因为这些方法只读、不写业务状态。失败语义也有限：可读学校为空时
 * 返回空集（例如教师课表），其余情况与写路径一样按权限拒绝，不做静默降级。共享的行读取与格式化
 * 统一走 {@link TeachingLookups}。
 */
@Component
class TeachingReporting {
  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final EnrollmentAccess enrollments;
  private final EnrollmentQueries enrollmentQueries;
  private final StudentAccess students;
  private final TeachingLookups lookups;

  TeachingReporting(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      EnrollmentAccess enrollments,
      EnrollmentQueries enrollmentQueries,
      StudentAccess students,
      TeachingLookups lookups) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.enrollments = enrollments;
    this.enrollmentQueries = enrollmentQueries;
    this.students = students;
    this.lookups = lookups;
  }

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
                          lookups.date(row.get("lesson_date", LocalDate.class)),
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

  public TeacherClassWorkbench myClass(UUID classId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var classRow = lookups.requireClass(verified, classId);
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
                          lookups.date(row.get("lesson_date", LocalDate.class)),
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
          lookups.requireReadable(verified, schoolId);
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
                          lookups.date(row.get("lesson_date", LocalDate.class)),
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
                            lookups.hours(consumedHours),
                            lookups.money(amounts[1]),
                            base.cancelledHours(),
                            lookups.hours(remaining));
                      })
                  .toList();
          return new StudentConsumptionPage(items, bases.page(), bases.size(), bases.total());
        });
  }

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
                          lookups.hours(row.get("consumed_hours", BigDecimal.class)),
                          lookups.money(row.get("consumed_amount", BigDecimal.class)))));
        });
  }

  public AttendanceReport attendanceReport(UUID schoolId, String fromText, String toText) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var from = workbenchDate(fromText);
    var to = workbenchDate(toText);
    if (to.isBefore(from) || to.isAfter(from.plusDays(30)))
      throw new IllegalArgumentException("考勤报表日期范围须为 1 至 31 天");
    var scope = actors.requireResourceScope("TEACHING");
    lookups.requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          lookups.requireReadable(verified, schoolId);
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
                          lookups.date(row.get("lesson_date", LocalDate.class)),
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

  public TeachingOverviewReport teachingOverviewReport(
      UUID schoolId, String fromText, String toText) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var from = workbenchDate(fromText);
    var to = workbenchDate(toText);
    if (to.isBefore(from) || to.isAfter(from.plusDays(30)))
      throw new IllegalArgumentException("教务综合情况日期范围须为 1 至 31 天");
    var scope = actors.requireResourceScope("TEACHING");
    lookups.requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () -> {
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          lookups.requireReadable(verified, schoolId);
          var classes =
              sql.fetchOne(
                  "select count(*) filter (where status='ACTIVE') as active,"
                      + "count(*) filter (where status='CLOSED') as closed,"
                      + "count(*) filter (where status='CANCELLED') as cancelled "
                      + "from teaching.class where school_id=?",
                  schoolId);
          var lessons =
              sql.fetchOne(
                  "select count(l.id) as total,"
                      + "count(l.id) filter (where l.status='DRAFT') as draft,"
                      + "count(l.id) filter (where l.status='PUBLISHED') as published,"
                      + "count(l.id) filter (where l.status='CONFIRMED') as confirmed,"
                      + "count(l.id) filter (where l.status='CLOSED') as closed "
                      + "from teaching.lesson l join teaching.class c on c.id=l.class_id "
                      + "where c.school_id=? and l.lesson_date between ? and ?",
                  schoolId,
                  from,
                  to);
          var attendance =
              sql.fetchOne(
                  "select count(a.enrollment_id) filter (where a.status='PRESENT') as present,"
                      + "count(a.enrollment_id) filter (where a.status='PARTIAL') as partial,"
                      + "count(a.enrollment_id) filter (where a.status='ABSENT') as absent,"
                      + "count(a.enrollment_id) filter (where a.status='LEAVE') as leave,"
                      + "count(a.enrollment_id) as recorded "
                      + "from teaching.attendance a join teaching.lesson l on l.id=a.lesson_id "
                      + "join teaching.class c on c.id=l.class_id "
                      + "where c.school_id=? and l.lesson_date between ? and ?",
                  schoolId,
                  from,
                  to);
          return new TeachingOverviewReport(
              from.toString(),
              to.toString(),
              classes.get("active", Long.class),
              classes.get("closed", Long.class),
              classes.get("cancelled", Long.class),
              lessons.get("total", Long.class),
              lessons.get("draft", Long.class),
              lessons.get("published", Long.class),
              lessons.get("confirmed", Long.class),
              lessons.get("closed", Long.class),
              attendance.get("present", Long.class),
              attendance.get("partial", Long.class),
              attendance.get("absent", Long.class),
              attendance.get("leave", Long.class),
              attendance.get("recorded", Long.class));
        });
  }
}
