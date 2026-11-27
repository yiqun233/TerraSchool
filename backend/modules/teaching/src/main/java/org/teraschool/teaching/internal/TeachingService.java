package org.teraschool.teaching.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
 * * 班级、课次、考勤与课次消费的完整用例。 * *
 *
 * <p>课次生命周期：草稿 → 发布 → 授课确认（含考勤）→ 关闭。关闭是消费与课时的唯一固化点：同一报名×课次只 *
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
  private final TeachingLookups lookups;
  private final TeachingReporting reporting;

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
      PeriodGate periods,
      TeachingLookups lookups,
      TeachingReporting reporting) {
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
    this.lookups = lookups;
    this.reporting = reporting;
  } // ---------------------------------------------------------------- 查询

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
    return reporting.myWorkbench(fromText, toText);
  }

  @Override
  public TeacherClassWorkbench myClass(UUID classId) {
    return reporting.myClass(classId);
  }

  @Override
  public DailyResourceSchedule dailyResourceSchedule(
      UUID schoolId, String dayText, int page, int pageSize) {
    return reporting.dailyResourceSchedule(schoolId, dayText, page, pageSize);
  }

  @Override
  public java.util.List<TimetableGroupRow> timetableRows(
      UUID schoolId, String fromText, String toText, String view) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireResourceScope("TEACHING");
      if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
        throw new AccessDeniedException("无该学校的课表读取权限");
      var from = businessDate(fromText, "开始日期");
      var to = businessDate(toText, "结束日期");
      if (to.isBefore(from) || from.plusDays(31).isBefore(to))
        throw new IllegalArgumentException("一次最多查看 31 天");
      var mode =
          view == null || view.isBlank()
              ? "teacher"
              : view.strip().toLowerCase(java.util.Locale.ROOT);
      if (!java.util.Set.of("teacher", "student", "day").contains(mode))
        throw new IllegalArgumentException("视图须为 teacher、student 或 day");
      return tx.execute(
          scope.tenantId(),
          () -> {
            var groups = new java.util.LinkedHashMap<String, java.util.List<TimetableCellView>>();
            var labels = new java.util.LinkedHashMap<String, String>();
            var sublabels = new java.util.LinkedHashMap<String, String>();
            if ("teacher".equals(mode)) {
              var rows =
                  sql.fetch(
                      "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,c.name as class_name,"
                          + "cur.name as curriculum_name,l.teacher_name,l.room_name,l.topic,l.status,"
                          + "l.teacher_id from teaching.lesson l"
                          + " join teaching.class c on c.id=l.class_id"
                          + " left join product.curriculum cur on cur.id=l.curriculum_id"
                          + " where c.school_id=? and l.lesson_date between ? and ?"
                          + " order by l.teacher_name nulls last,l.lesson_date,l.starts_at",
                      schoolId,
                      from,
                      to);
              for (var row : rows) {
                var teacherId = row.get("teacher_id", UUID.class);
                var key = teacherId == null ? "unassigned" : teacherId.toString();
                labels.putIfAbsent(
                    key, teacherId == null ? "未排教师" : row.get("teacher_name", String.class));
                sublabels.putIfAbsent(key, "教师");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cellView(row));
              }
            } else if ("student".equals(mode)) {
              var rows =
                  sql.fetch(
                      "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,c.name as class_name,"
                          + "cur.name as curriculum_name,l.teacher_name,l.room_name,l.topic,l.status,"
                          + "s.id as student_id,s.name as student_name"
                          + " from teaching.lesson l"
                          + " join teaching.class c on c.id=l.class_id"
                          + " left join product.curriculum cur on cur.id=l.curriculum_id"
                          + " join teaching.enrollment_class ec on ec.class_id=c.id and ec.active"
                          + " join enrollment.enrollment e on e.id=ec.enrollment_id"
                          + " join archive.student s on s.id=e.student_id"
                          + " where c.school_id=? and l.lesson_date between ? and ?"
                          + " order by s.name,l.lesson_date,l.starts_at",
                      schoolId,
                      from,
                      to);
              for (var row : rows) {
                var key = row.get("student_id", UUID.class).toString();
                labels.putIfAbsent(key, row.get("student_name", String.class));
                sublabels.putIfAbsent(key, "学员");
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cellView(row));
              }
            } else { // 月视图：按日期聚合成行，前端再铺成 7 列日历。
              var rows =
                  sql.fetch(
                      "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,c.name as class_name,"
                          + "cur.name as curriculum_name,l.teacher_name,l.room_name,l.topic,l.status"
                          + " from teaching.lesson l"
                          + " join teaching.class c on c.id=l.class_id"
                          + " left join product.curriculum cur on cur.id=l.curriculum_id"
                          + " where c.school_id=? and l.lesson_date between ? and ?"
                          + " order by l.lesson_date,l.starts_at",
                      schoolId,
                      from,
                      to);
              for (var row : rows) {
                var day = row.get("lesson_date", LocalDate.class);
                var key = day.toString();
                labels.putIfAbsent(key, key);
                sublabels.putIfAbsent(
                    key,
                    day.getDayOfWeek()
                        .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.CHINA));
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cellView(row));
              }
            }
            var result = new ArrayList<TimetableGroupRow>();
            groups.forEach(
                (key, cells) ->
                    result.add(
                        new TimetableGroupRow(key, labels.get(key), sublabels.get(key), cells)));
            return result;
          });
    }
  }

  private TimetableCellView cellView(org.jooq.Record row) {
    return new TimetableCellView(
        row.get("id", UUID.class),
        row.get("class_id", UUID.class),
        date(row.get("lesson_date", LocalDate.class)),
        time(row.get("starts_at", LocalTime.class)),
        time(row.get("ends_at", LocalTime.class)),
        row.get("class_name", String.class),
        row.get("curriculum_name", String.class),
        row.get("teacher_name", String.class),
        row.get("room_name", String.class),
        row.get("topic", String.class),
        row.get("status", String.class));
  }

  @Override
  public StudentConsumptionPage studentConsumption(UUID schoolId, int page, int size) {
    return reporting.studentConsumption(schoolId, page, size);
  }

  @Override
  public ClassConsumptionReport classConsumptionReport(
      UUID schoolId, String fromText, String toText) {
    return reporting.classConsumptionReport(schoolId, fromText, toText);
  }

  @Override
  public AttendanceReport attendanceReport(UUID schoolId, String fromText, String toText) {
    return reporting.attendanceReport(schoolId, fromText, toText);
  }

  @Override
  public TeachingOverviewReport teachingOverviewReport(
      UUID schoolId, String fromText, String toText) {
    return reporting.teachingOverviewReport(schoolId, fromText, toText);
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
                  "select c.id,c.school_id,c.curriculum_id,c.name,c.status,c.version,c.group_training,c.capacity,"
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
  public List<ClassCurriculumView> classCurricula(UUID classId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var classRow = requireClass(scope, classId);
          var primary = classRow.get("curriculum_id", UUID.class);
          return sql.fetch(
                  "select cc.curriculum_id,cur.name as curriculum_name,cc.hours,cc.price"
                      + " from teaching.class_curriculum cc"
                      + " join product.curriculum cur on cur.id=cc.curriculum_id"
                      + " where cc.class_id=? order by cur.name, cc.curriculum_id",
                  classId)
              .map(
                  row -> {
                    var curriculumId = row.get("curriculum_id", UUID.class);
                    return new ClassCurriculumView(
                        curriculumId,
                        row.get("curriculum_name", String.class),
                        row.get("hours", BigDecimal.class),
                        row.get("price", BigDecimal.class),
                        curriculumId.equals(primary));
                  });
        });
  }

  @Override
  public List<TeacherOption> teacherOptions(
      UUID classId, String dayText, String startsText, String endsText, UUID curriculumId) {
    var scope = actors.requireResourceScope("TEACHING");
    var day = businessDate(dayText, "课次日期");
    var startsAt = time(startsText, "开始时间");
    var endsAt = time(endsText, "结束时间");
    if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var classRow = sql.fetchOne("select school_id from teaching.class where id=?", classId);
          if (classRow == null) throw new AccessDeniedException("班级不存在或不可访问");
          var schoolId = classRow.get("school_id", UUID.class);
          if (!scope.readableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校的教务读取权限");
          var policy =
              sql.fetchOne(
                  "select block_teacher_unavailable from teaching.schedule_policy where school_id=?",
                  schoolId);
          boolean block =
              policy == null
                  || Boolean.TRUE.equals(policy.get("block_teacher_unavailable", Boolean.class));
          var busy =
              sql.fetch(
                      "select distinct teacher_id from teaching.lesson"
                          + " where lesson_date=? and starts_at<? and ends_at>? and teacher_id is not null",
                      day,
                      endsAt,
                      startsAt)
                  .intoSet(row -> row.get("teacher_id", UUID.class));
          var blocked =
              block
                  ? sql.fetch(
                          "select distinct teacher_id from teaching.resource_block"
                              + " where block_date=? and starts_at<? and ends_at>?",
                          day,
                          endsAt,
                          startsAt)
                      .intoSet(row -> row.get("teacher_id", UUID.class))
                  : Set.<UUID>of();
          var mapped =
              sql.fetch(
                      "select tc.teacher_id,tc.curriculum_id from organization.teacher_curriculum tc"
                          + " join organization.teacher t on t.id=tc.teacher_id"
                          + " where t.school_id=?",
                      schoolId)
                  .intoSet(
                      row ->
                          row.get("teacher_id", UUID.class)
                              + ":"
                              + row.get("curriculum_id", UUID.class));
          var rows =
              sql.fetch(
                  "select id,name from organization.teacher where school_id=? and active"
                      + " order by name, id limit 200",
                  schoolId);
          boolean restrict =
              curriculumId != null
                  && rows.stream()
                      .anyMatch(
                          row -> mapped.contains(row.get("id", UUID.class) + ":" + curriculumId));
          var options = new ArrayList<TeacherOption>();
          for (var row : rows) {
            var id = row.get("id", UUID.class);
            boolean matches = mapped.contains(id + ":" + curriculumId);
            if (restrict && !matches) continue;
            boolean unavailable = busy.contains(id) || blocked.contains(id);
            var reason = busy.contains(id) ? "该时段已有课" : blocked.contains(id) ? "该时段不可用" : "";
            options.add(
                new TeacherOption(
                    id,
                    row.get("name", String.class),
                    matches,
                    busy.contains(id) ? 1 : 0,
                    !unavailable,
                    reason));
          }
          return options;
        });
  }

  @Override
  public List<RoomOption> roomOptions(
      UUID classId, String dayText, String startsText, String endsText) {
    var scope = actors.requireResourceScope("TEACHING");
    var day = businessDate(dayText, "课次日期");
    var startsAt = time(startsText, "开始时间");
    var endsAt = time(endsText, "结束时间");
    if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var classRow =
              sql.fetchOne(
                  "select school_id,campus_id,capacity from teaching.class where id=?", classId);
          if (classRow == null) throw new AccessDeniedException("班级不存在或不可访问");
          var schoolId = classRow.get("school_id", UUID.class);
          if (!scope.readableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无该学校的教务读取权限");
          var campusId = classRow.get("campus_id", UUID.class);
          var policy =
              sql.fetchOne(
                  "select block_room_unavailable from teaching.schedule_policy where school_id=?",
                  schoolId);
          boolean block =
              policy == null
                  || Boolean.TRUE.equals(policy.get("block_room_unavailable", Boolean.class));
          var busy =
              sql.fetch(
                      "select distinct room_id from teaching.lesson"
                          + " where lesson_date=? and starts_at<? and ends_at>? and room_id is not null",
                      day,
                      endsAt,
                      startsAt)
                  .intoSet(row -> row.get("room_id", UUID.class));
          var blocked =
              block
                  ? sql.fetch(
                          "select distinct room_id from teaching.resource_block"
                              + " where block_date=? and starts_at<? and ends_at>?",
                          day,
                          endsAt,
                          startsAt)
                      .intoSet(row -> row.get("room_id", UUID.class))
                  : Set.<UUID>of();
          var rows =
              sql.fetch(
                  "select r.id,r.name,r.capacity,u.name as campus_name from organization.room r"
                      + " left join organization.unit u on u.id=r.campus_id and u.kind='CAMPUS'"
                      + " where r.school_id=? and r.active and (?::uuid is null or r.campus_id=?)"
                      + " order by u.name nulls last, r.name, r.id limit 200",
                  schoolId,
                  campusId,
                  campusId);
          var options = new ArrayList<RoomOption>();
          for (var row : rows) {
            var id = row.get("id", UUID.class);
            var reason = busy.contains(id) ? "该时段已有课" : blocked.contains(id) ? "该时段不可用" : "";
            options.add(
                new RoomOption(
                    id,
                    row.get("name", String.class),
                    row.get("campus_name", String.class),
                    row.get("capacity", Integer.class),
                    busy.contains(id) ? 1 : 0,
                    reason.isEmpty(),
                    reason));
          }
          return options;
        });
  }

  @Override
  public List<MinuteSpace> schoolMinuteSpaces(UUID schoolId) {
    var scope = actors.requireResourceScope("TEACHING");
    requireReadable(scope, schoolId);
    return tx.execute(
        scope.tenantId(),
        () ->
            sql.fetch(
                    "select begin_minute,end_minute from teaching.school_minute_space"
                        + " where school_id=? order by begin_minute, end_minute",
                    schoolId)
                .map(
                    row ->
                        new MinuteSpace(
                            row.get("begin_minute", Integer.class),
                            row.get("end_minute", Integer.class))));
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
                      + "l.actual_teacher_id,l.actual_room_id,l.actual_teacher_name,l.actual_room_name,l.memo,"
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
                          + "c.settlement_mode,c.price_snapshot,c.hours_snapshot" // 期初消耗没有课次（lesson_id 为空），必须用左连接，否则会被过滤掉。
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
  } // ---------------------------------------------------------------- 班级

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
  public ClassView updateClassCapacity(UUID classId, UpdateClassCapacity command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (classId == null
          || command == null
          || command.requestId() == null
          || command.expectedVersion() == null) throw new IllegalArgumentException("需要班级、请求身份和版本");
      if (command.capacity() != null && (command.capacity() < 0 || command.capacity() > 100000))
        throw new IllegalArgumentException("班级名额上限须为 0–100000 或不限");
      var reason = text(command.reason(), 200, "修改名额原因");
      if (reason == null) throw new IllegalArgumentException("修改名额原因必填");
      var scope = actors.requireResourceScope("TEACHING");
      var capacityText = Objects.toString(command.capacity(), "UNLIMITED");
      var fingerprint =
          fingerprint(
              "CLASS_CAPACITY",
              classId.toString(),
              command.expectedVersion().toString(),
              capacityText,
              reason);
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("TEACHING");
            var current =
                sql.fetchOne(
                    "select c.school_id,c.status,c.version,c.capacity,"
                        + "(select count(*) from teaching.enrollment_class m"
                        + " where m.class_id=c.id and m.active) as member_count"
                        + " from teaching.class c where c.id=? for update",
                    classId);
            if (current == null) throw new AccessDeniedException("班级不存在或不可访问");
            var schoolId = current.get("school_id", UUID.class);
            if (!verified.maintainableSchoolIds().contains(schoolId))
              throw new AccessDeniedException("无该学校班级维护权限");
            var school = schools.require(schoolId);
            if (!school.active()) throw new AccessDeniedException("学校已停用，不能修改班级名额");
            var previous =
                previousRequest(scope, command.requestId(), "CLASS_CAPACITY", fingerprint);
            if (previous != null) return classView(requireClass(verified, previous), school.name());
            if (!"ACTIVE".equals(current.get("status", String.class)))
              throw new TeachingConflict("CLASS_NOT_ACTIVE");
            if (!current.get("version", Long.class).equals(command.expectedVersion()))
              throw new TeachingConflict("VERSION_CONFLICT");
            var members = current.get("member_count", Long.class).intValue();
            if (command.capacity() != null && command.capacity() < members)
              throw new TeachingConflict("CAPACITY_BELOW_MEMBER_COUNT");
            if (Objects.equals(command.capacity(), current.get("capacity", Integer.class)))
              throw new TeachingConflict("CLASS_CAPACITY_UNCHANGED");
            int changed =
                sql.execute(
                    "update teaching.class set capacity=?,version=version+1"
                        + " where id=? and version=? and status='ACTIVE'",
                    command.capacity(),
                    classId,
                    command.expectedVersion());
            if (changed != 1) throw new TeachingConflict("VERSION_CONFLICT");
            rememberRequest(scope, command.requestId(), "CLASS_CAPACITY", classId, fingerprint);
            audit(
                verified,
                "CLASS_CAPACITY_UPDATED",
                classId,
                schoolId,
                "capacity=" + capacityText + ";reason=" + reason);
            return classView(requireClass(verified, classId), school.name());
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
    var capacity = command.capacity();
    if (capacity != null && (capacity < 0 || capacity > 100000))
      throw new IllegalArgumentException("班级名额上限须为 0–100000");
    var scope = actors.requireResourceScope("TEACHING");
    requireMaintainable(scope, command.schoolId());
    var fingerprint =
        fingerprint(
            "CLASS",
            name,
            String.valueOf(command.curriculumId()),
            classType(command.classType()),
            Boolean.toString(command.groupTraining()),
            Objects.toString(capacity, ""));
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
                "insert into teaching.class(id,tenant_id,school_id,curriculum_id,name,class_type,group_training,capacity)"
                    + " values (?,?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                command.curriculumId(),
                name,
                classType(command.classType()),
                command.groupTraining(),
                capacity);
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
              var capacity = target.get("capacity", Integer.class);
              var memberCount = target.get("member_count", Integer.class);
              if (capacity != null && memberCount >= capacity)
                throw new TeachingConflict("CLASS_CAPACITY_REACHED");
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
            var targetCapacity = target.get("capacity", Integer.class);
            var targetMembers = target.get("member_count", Integer.class);
            if (targetCapacity != null && targetMembers >= targetCapacity)
              throw new TeachingConflict("CLASS_CAPACITY_REACHED");
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
  } // ---------------------------------------------------------------- 课次

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
          var room =
              resource(
                  command.roomId(),
                  "ROOM",
                  schoolId,
                  command.roomName()); // 单条新建也必须走与改时间/批量建课次相同的时段校验，否则会建出同班/同教师/同教室重叠的课次。
          assertSlotFree(
              command.classId(),
              schoolId,
              date,
              startsAt,
              endsAt,
              teacher.id(),
              teacher.name(),
              room.id(),
              room.name(),
              null);
          var id = UUID.randomUUID();
          assertCurriculumHoursAvailable(
              command.classId(), row.get("curriculum_id", UUID.class), lessonHours);
          sql.execute(
              "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,"
                  + "teacher_name,room_name,topic,hours,teacher_id,room_id,curriculum_id)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
              room.id(),
              row.get("curriculum_id", UUID.class));
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
            notifySchool(
                verified,
                schoolId,
                "LESSON_RESCHEDULED",
                "课次调课",
                "课次 " + oldDate + " " + oldStartsAt + " 已调整到 " + date + " " + startsAt,
                id);
            return lessonView(requireLessonForUpdate(id));
          });
    }
  }

  @Override
  public LessonResourceView assignLessonResources(UUID id, AssignLessonResources command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null
          || command == null
          || command.requestId() == null
          || command.expectedVersion() == null) throw new IllegalArgumentException("需要课次、请求身份和版本");
      var actor = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var row = requireLessonForUpdate(id);
            var classId = row.get("class_id", UUID.class);
            var schoolId = classSchool(classId);
            if (!verified.schools().contains(schoolId))
              throw new AccessDeniedException("无该学校的教务维护权限");
            if (!schools.require(schoolId).active())
              throw new AccessDeniedException("学校已停用，不能修改课次");
            var status = row.get("status", String.class);
            if (!Set.of("DRAFT", "PUBLISHED").contains(status))
              throw new TeachingConflict("LESSON_NOT_ASSIGNABLE");
            var teacher =
                command.teacherId() == null
                    ? null
                    : resource(command.teacherId(), "TEACHER", schoolId, null);
            var room =
                command.roomId() == null
                    ? null
                    : resource(command.roomId(), "ROOM", schoolId, null);
            if (command.curriculumId() != null
                && !sql.fetchExists(
                    sql.selectOne()
                        .from("teaching.class_curriculum")
                        .where("class_id=? and curriculum_id=?", classId, command.curriculumId())))
              throw new TeachingConflict("CURRICULUM_NOT_IN_CLASS");
            var teacherId = teacher == null ? null : teacher.id();
            var roomId = room == null ? null : room.id();
            var currentTeacher = row.get("teacher_id", UUID.class);
            var currentRoom = row.get("room_id", UUID.class);
            var currentCurriculum = row.get("curriculum_id", UUID.class);
            if (Objects.equals(teacherId, currentTeacher)
                && Objects.equals(roomId, currentRoom)
                && Objects.equals(command.curriculumId(), currentCurriculum)) {
              return resourceView(id);
            }
            if (!command.expectedVersion().equals(row.get("version", Long.class)))
              throw new TeachingConflict("VERSION_CONFLICT");
            var lessonDate = row.get("lesson_date", LocalDate.class);
            var startsAt = row.get("starts_at", LocalTime.class);
            var endsAt = row.get("ends_at", LocalTime.class);
            if (teacherId != null
                && sql.fetchExists(
                    sql.selectOne()
                        .from("teaching.lesson")
                        .where(
                            "id<>? and lesson_date=? and starts_at<? and ends_at>? and teacher_id=?",
                            id,
                            lessonDate,
                            endsAt,
                            startsAt,
                            teacherId))) throw new TeachingConflict("TEACHER_CLASH");
            if (roomId != null
                && sql.fetchExists(
                    sql.selectOne()
                        .from("teaching.lesson")
                        .where(
                            "id<>? and lesson_date=? and starts_at<? and ends_at>? and room_id=?",
                            id,
                            lessonDate,
                            endsAt,
                            startsAt,
                            roomId))) throw new TeachingConflict("ROOM_CLASH");
            int updated =
                sql.execute(
                    "update teaching.lesson set teacher_id=?,teacher_name=?,room_id=?,room_name=?,"
                        + "curriculum_id=?,version=version+1 where id=? and version=?",
                    teacherId,
                    teacher == null ? null : teacher.name(),
                    roomId,
                    room == null ? null : room.name(),
                    command.curriculumId(),
                    id,
                    command.expectedVersion());
            if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
            audit(verified, "LESSON_RESOURCES_ASSIGNED", id, schoolId, "schedule");
            return resourceView(id);
          });
    }
  }

  private LessonResourceView resourceView(UUID id) {
    var row =
        sql.fetchOne(
            "select l.id,l.curriculum_id,cur.name as curriculum_name,l.teacher_id,l.teacher_name,"
                + "l.room_id,l.room_name,l.version from teaching.lesson l"
                + " left join product.curriculum cur on cur.id=l.curriculum_id where l.id=?",
            id);
    if (row == null) throw new AccessDeniedException("课次不存在或不可访问");
    return new LessonResourceView(
        row.get("id", UUID.class),
        row.get("curriculum_id", UUID.class),
        row.get("curriculum_name", String.class),
        row.get("teacher_id", UUID.class),
        row.get("teacher_name", String.class),
        row.get("room_id", UUID.class),
        row.get("room_name", String.class),
        row.get("version", Long.class));
  }

  @Override
  public BatchLessonResult createLessons(BatchLessons command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return createLessonsWithTrace(command);
    }
  }

  private BatchLessonResult createLessonsWithTrace(BatchLessons command) {
    if (command == null || command.classId() == null) throw new IllegalArgumentException("需要班级");
    var start = businessDate(command.startText(), "起始日期");
    var end = businessDate(command.endText(), "结束日期");
    if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于起始日期");
    if (start.plusDays(90).isBefore(end)) throw new IllegalArgumentException("一次最多生成 90 天");
    var startsAt = time(command.startsAt(), "开始时间");
    var endsAt = time(command.endsAt(), "结束时间");
    if (!endsAt.isAfter(startsAt)) throw new IllegalArgumentException("结束时间必须晚于开始时间");
    var weekdays =
        command.weekdays() == null ? new HashSet<Integer>() : new HashSet<>(command.weekdays());
    if (weekdays.isEmpty() || weekdays.stream().anyMatch(day -> day == null || day < 1 || day > 7))
      throw new IllegalArgumentException("请选择周几（1=周一 … 7=周日）");
    var minutes = java.time.Duration.between(startsAt, endsAt).toMinutes();
    var hours = BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var row = requireClass(verified, command.classId());
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无课次维护权限");
          if (!"ACTIVE".equals(row.get("status", String.class)))
            throw new TeachingConflict("CLASS_NOT_ACTIVE");
          var curriculumId = command.curriculumId();
          if (curriculumId == null) curriculumId = row.get("curriculum_id", UUID.class);
          else if (sql.fetchOne(
                  "select 1 from teaching.class_curriculum where class_id=? and curriculum_id=?",
                  command.classId(),
                  curriculumId)
              == null) throw new TeachingConflict("CURRICULUM_NOT_IN_CLASS");
          var teacher = resource(command.teacherId(), "TEACHER", schoolId, null);
          var room = resource(command.roomId(), "ROOM", schoolId, null);
          var created = new ArrayList<UUID>();
          var skipped = new ArrayList<String>();
          for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!weekdays.contains(date.getDayOfWeek().getValue())) continue;
            if (!command.includeHolidays()
                && sql.fetchOne(
                        "select 1 from teaching.school_closure where school_id=? and closed_on=? and active",
                        schoolId,
                        date)
                    != null) {
              skipped.add(date + "（停课日）");
              continue;
            }
            if (sql.fetchOne(
                    "select 1 from teaching.lesson where class_id=? and lesson_date=?"
                        + " and starts_at<? and ends_at>?",
                    command.classId(),
                    date,
                    endsAt,
                    startsAt)
                != null) {
              skipped.add(date + "（本班该时段已有课次）");
              continue;
            }
            try {
              assertSlotFree(
                  command.classId(),
                  schoolId,
                  date,
                  startsAt,
                  endsAt,
                  teacher.id(),
                  teacher.name(),
                  room.id(),
                  room.name(),
                  null);
            } catch (TeachingConflict conflict) {
              skipped.add(date + "（" + slotConflictLabel(conflict.code()) + "）");
              continue;
            }
            var id = UUID.randomUUID();
            assertCurriculumHoursAvailable(command.classId(), curriculumId, hours);
            sql.execute(
                "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,"
                    + "teacher_name,room_name,topic,hours,teacher_id,room_id,curriculum_id)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.classId(),
                date,
                startsAt,
                endsAt,
                teacher.name(),
                room.name(),
                text(command.topic(), 100, "课题"),
                hours,
                teacher.id(),
                room.id(),
                curriculumId);
            created.add(id);
          }
          if (!created.isEmpty())
            audit(
                verified,
                "LESSONS_BATCH_CREATED",
                command.classId(),
                schoolId,
                "lessons=" + created.size());
          return new BatchLessonResult(created.size(), created, skipped);
        });
  }

  @Override
  public BatchLessonOutcome shiftLessons(UUID classId, ShiftLessons command) {
    if (command == null || command.requestId() == null) throw new IllegalArgumentException("需要请求号");
    if (classId == null) throw new IllegalArgumentException("需要班级");
    if (command.minutes() == 0) throw new IllegalArgumentException("平移分钟不能为 0");
    if (Math.abs(command.minutes()) > 720) throw new IllegalArgumentException("一次平移不能超过 12 小时");
    var from = businessDate(command.fromDate(), "起始日期");
    var to = businessDate(command.toDate(), "结束日期");
    if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于起始日期");
    if (from.plusDays(31).isBefore(to)) throw new IllegalArgumentException("一次最多平移 31 天");
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var row = requireClass(verified, classId);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无课次维护权限");
          var today = LocalDate.now();
          var done = new ArrayList<UUID>();
          var skipped = new ArrayList<BatchLessonOutcome.Skipped>();
          for (var lesson :
              sql.fetch(
                  "select id,status,version,lesson_date,starts_at,ends_at,teacher_id,teacher_name,"
                      + "room_id,room_name from teaching.lesson where class_id=?"
                      + " and lesson_date>=? and lesson_date<=? order by lesson_date,starts_at",
                  classId,
                  from,
                  to)) {
            var id = lesson.get("id", UUID.class);
            var status = lesson.get("status", String.class);
            if (!"DRAFT".equals(status) && !"PUBLISHED".equals(status)) {
              skipped.add(new BatchLessonOutcome.Skipped(id, "LESSON_NOT_RESCHEDULABLE"));
              continue;
            }
            var date = lesson.get("lesson_date", LocalDate.class);
            if (date.isBefore(today)) {
              skipped.add(new BatchLessonOutcome.Skipped(id, "LESSON_IN_PAST"));
              continue;
            }
            var startMinute = lesson.get("starts_at", LocalTime.class).toSecondOfDay() / 60;
            var endMinute = lesson.get("ends_at", LocalTime.class).toSecondOfDay() / 60;
            var newStartMinute = startMinute + command.minutes();
            var newEndMinute = endMinute + command.minutes();
            if (newStartMinute < 0 || newEndMinute > 1439) {
              skipped.add(new BatchLessonOutcome.Skipped(id, "LESSON_TIME_OUT_OF_RANGE"));
              continue;
            }
            var newStart = LocalTime.of(newStartMinute / 60, newStartMinute % 60);
            var newEnd = LocalTime.of(newEndMinute / 60, newEndMinute % 60);
            try {
              assertSlotFree(
                  classId,
                  schoolId,
                  date,
                  newStart,
                  newEnd,
                  lesson.get("teacher_id", UUID.class),
                  lesson.get("teacher_name", String.class),
                  lesson.get("room_id", UUID.class),
                  lesson.get("room_name", String.class),
                  id);
            } catch (TeachingConflict conflict) {
              skipped.add(new BatchLessonOutcome.Skipped(id, conflict.code()));
              continue;
            }
            var changed =
                sql.execute(
                    "update teaching.lesson set starts_at=?,ends_at=?,version=version+1"
                        + " where id=? and version=? and status in ('DRAFT','PUBLISHED')",
                    newStart,
                    newEnd,
                    id,
                    lesson.get("version", Long.class));
            if (changed != 1) {
              skipped.add(new BatchLessonOutcome.Skipped(id, "VERSION_CONFLICT"));
              continue;
            }
            audit(verified, "LESSON_RESCHEDULED", id, schoolId, "shift=" + command.minutes() + "m");
            done.add(id);
          }
          return new BatchLessonOutcome(done.size(), List.copyOf(done), List.copyOf(skipped));
        });
  }

  @Override
  public BatchLessonResult copyWeek(UUID classId, CopyWeek command) {
    if (command == null || command.requestId() == null) throw new IllegalArgumentException("需要请求号");
    if (classId == null) throw new IllegalArgumentException("需要班级");
    var source = businessDate(command.sourceWeekStart(), "起始周");
    var sourceEnd = source.plusDays(6);
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          schools.lockTenant(scope.tenantId());
          var verified = actors.requireResourceScopeInTransaction("TEACHING");
          var row = requireClass(verified, classId);
          var schoolId = row.get("school_id", UUID.class);
          if (!verified.maintainableSchoolIds().contains(schoolId))
            throw new AccessDeniedException("无课次维护权限");
          var bounds =
              sql.fetchOne("select begins_on,ends_on from teaching.class where id=?", classId);
          var beginsOn = bounds == null ? null : bounds.get("begins_on", LocalDate.class);
          var endsOn = bounds == null ? null : bounds.get("ends_on", LocalDate.class);
          var today = LocalDate.now();
          var created = new ArrayList<UUID>();
          var skipped = new ArrayList<String>();
          for (var lesson :
              sql.fetch(
                  "select lesson_date,starts_at,ends_at,teacher_id,teacher_name,room_id,room_name,"
                      + "topic,hours,curriculum_id from teaching.lesson where class_id=?"
                      + " and lesson_date>=? and lesson_date<=? order by lesson_date,starts_at",
                  classId,
                  source,
                  sourceEnd)) {
            var date = lesson.get("lesson_date", LocalDate.class).plusDays(7);
            var startsAt = lesson.get("starts_at", LocalTime.class);
            var endsAt = lesson.get("ends_at", LocalTime.class);
            if (date.isBefore(today)) {
              skipped.add(date + "（已过去）");
              continue;
            }
            if ((beginsOn != null && date.isBefore(beginsOn))
                || (endsOn != null && date.isAfter(endsOn))) {
              skipped.add(date + "（超出班级起止日期）");
              continue;
            }
            if (sql.fetchOne(
                    "select 1 from teaching.lesson where class_id=? and lesson_date=?"
                        + " and starts_at<? and ends_at>?",
                    classId,
                    date,
                    endsAt,
                    startsAt)
                != null) {
              skipped.add(date + "（本班该时段已有课次）");
              continue;
            }
            try {
              assertSlotFree(
                  classId,
                  schoolId,
                  date,
                  startsAt,
                  endsAt,
                  lesson.get("teacher_id", UUID.class),
                  lesson.get("teacher_name", String.class),
                  lesson.get("room_id", UUID.class),
                  lesson.get("room_name", String.class),
                  null);
            } catch (TeachingConflict conflict) {
              skipped.add(date + "（" + slotConflictLabel(conflict.code()) + "）");
              continue;
            }
            var id = UUID.randomUUID();
            sql.execute(
                "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,"
                    + "teacher_name,room_name,topic,hours,teacher_id,room_id,curriculum_id)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                classId,
                date,
                startsAt,
                endsAt,
                lesson.get("teacher_name", String.class),
                lesson.get("room_name", String.class),
                lesson.get("topic", String.class),
                lesson.get("hours", BigDecimal.class),
                lesson.get("teacher_id", UUID.class),
                lesson.get("room_id", UUID.class),
                lesson.get("curriculum_id", UUID.class));
            created.add(id);
          }
          if (!created.isEmpty())
            audit(
                verified,
                "LESSONS_BATCH_CREATED",
                classId,
                schoolId,
                "copy-week=" + created.size());
          return new BatchLessonResult(created.size(), List.copyOf(created), List.copyOf(skipped));
        });
  }

  private static String slotConflictLabel(String code) {
    return switch (code) {
      case "TEACHER_CLASH" -> "教师已有课";
      case "ROOM_CLASH" -> "教室已被占用";
      case "TEACHER_MIN_GAP" -> "与该教师上一节课间隔不足";
      case "TEACHER_UNAVAILABLE" -> "教师该时段不可用";
      case "ROOM_UNAVAILABLE" -> "教室该时段不可用";
      default -> "时段冲突";
    };
  }

  @Override
  public void deleteLesson(UUID id, long expectedVersion) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (id == null) throw new IllegalArgumentException("需要课次");
      var scope = actors.requireResourceScope("TEACHING");
      tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireResourceScopeInTransaction("TEACHING");
            var row = requireLessonForUpdate(id);
            var schoolId = classSchool(row.get("class_id", UUID.class));
            if (!verified.maintainableSchoolIds().contains(schoolId))
              throw new AccessDeniedException("无课次维护权限");
            if (!"DRAFT".equals(row.get("status", String.class)))
              throw new TeachingConflict("LESSON_NOT_DRAFT");
            var changed =
                sql.execute(
                    "delete from teaching.lesson where id=? and version=? and status='DRAFT'",
                    id,
                    expectedVersion);
            if (changed != 1) throw new TeachingConflict("VERSION_CONFLICT");
            audit(verified, "LESSON_DELETED", id, schoolId, "");
            return null;
          });
    }
  }

  @Override
  public List<LessonConflict> lessonConflicts(UUID lessonId) {
    var scope = actors.requireResourceScope("TEACHING");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var row = requireLesson(scope, lessonId);
          var classId = row.get("class_id", UUID.class);
          var schoolId = classSchool(classId);
          requireReadable(scope, schoolId);
          var date = row.get("lesson_date", LocalDate.class);
          var startsAt = row.get("starts_at", LocalTime.class);
          var endsAt = row.get("ends_at", LocalTime.class);
          var teacherId = row.get("teacher_id", UUID.class);
          var roomId = row.get("room_id", UUID.class);
          var out = new ArrayList<LessonConflict>();
          for (var other :
              sql.fetch(
                  "select l.id,c.name as class_name,l.lesson_date,l.starts_at from teaching.lesson l"
                      + " join teaching.class c on c.id=l.class_id"
                      + " where l.class_id=? and l.id<>? and l.lesson_date=?"
                      + " and l.starts_at<? and l.ends_at>? order by l.starts_at",
                  classId,
                  lessonId,
                  date,
                  endsAt,
                  startsAt)) {
            out.add(conflict("CLASS_OVERLAP", "本班同一时段还有一节课", other));
          }
          if (teacherId != null) {
            for (var other :
                sql.fetch(
                    "select l.id,c.name as class_name,l.lesson_date,l.starts_at from teaching.lesson l"
                        + " join teaching.class c on c.id=l.class_id"
                        + " where l.teacher_id=? and l.id<>? and l.lesson_date=?"
                        + " and l.starts_at<? and l.ends_at>? order by l.starts_at",
                    teacherId,
                    lessonId,
                    date,
                    endsAt,
                    startsAt)) {
              out.add(conflict("TEACHER_CLASH", "该教师在同一时段已有课", other));
            }
          }
          if (roomId != null) {
            for (var other :
                sql.fetch(
                    "select l.id,c.name as class_name,l.lesson_date,l.starts_at from teaching.lesson l"
                        + " join teaching.class c on c.id=l.class_id"
                        + " where l.room_id=? and l.id<>? and l.lesson_date=?"
                        + " and l.starts_at<? and l.ends_at>? order by l.starts_at",
                    roomId,
                    lessonId,
                    date,
                    endsAt,
                    startsAt)) {
              out.add(conflict("ROOM_CLASH", "该教室在同一时段已被占用", other));
            }
          }
          for (var other :
              sql.fetch(
                  "select distinct l.id,c.name as class_name,l.lesson_date,l.starts_at"
                      + " from teaching.enrollment_class mine"
                      + " join teaching.enrollment_class theirs on theirs.enrollment_id=mine.enrollment_id"
                      + "   and theirs.active and theirs.class_id<>mine.class_id"
                      + " join teaching.lesson l on l.class_id=theirs.class_id"
                      + "   and l.lesson_date=? and l.starts_at<? and l.ends_at>? and l.id<>?"
                      + " join teaching.class c on c.id=l.class_id"
                      + " where mine.class_id=? and mine.active order by l.starts_at",
                  date,
                  endsAt,
                  startsAt,
                  lessonId,
                  classId)) {
            out.add(conflict("STUDENT_CLASH", "有学员在另一班同一时段有课", other));
          }
          if (sql.fetchOne(
                  "select 1 from teaching.school_closure where school_id=? and closed_on=? and active",
                  schoolId,
                  date)
              != null)
            out.add(new LessonConflict("SCHOOL_CLOSED", "该日期学校已停课", null, null, null, null));
          if (teacherId != null
              && sql.fetchOne(
                      "select 1 from teaching.resource_block where teacher_id=? and block_date=?"
                          + " and starts_at<? and ends_at>?",
                      teacherId,
                      date,
                      endsAt,
                      startsAt)
                  != null)
            out.add(
                new LessonConflict("TEACHER_UNAVAILABLE", "该教师此时间段被设为不可用", null, null, null, null));
          if (roomId != null
              && sql.fetchOne(
                      "select 1 from teaching.resource_block where room_id=? and block_date=?"
                          + " and starts_at<? and ends_at>?",
                      roomId,
                      date,
                      endsAt,
                      startsAt)
                  != null)
            out.add(
                new LessonConflict("ROOM_UNAVAILABLE", "该教室此时间段被设为不可用", null, null, null, null));
          return out;
        });
  }

  private static LessonConflict conflict(String type, String message, Record other) {
    return new LessonConflict(
        type,
        message,
        other.get("id", UUID.class),
        other.get("class_name", String.class),
        String.valueOf(other.get("lesson_date", LocalDate.class)),
        String.valueOf(other.get("starts_at", LocalTime.class)));
  }

  @Override
  public List<SchoolConflict> schoolConflicts(UUID schoolId, String from, String to, String type) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var begin = businessDate(from, "开始日期");
    var end = businessDate(to, "结束日期");
    if (end.isBefore(begin)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    if (ChronoUnit.DAYS.between(begin, end) > 31) throw new IllegalArgumentException("一次最多查看 31 天");
    var filter = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireReadable(scope, schoolId);
          var out = new ArrayList<SchoolConflict>();
          var seen = new HashSet<String>(); // 两两重叠：同班、同教师、同教室
          for (var row :
              sql.fetch(
                  "select a.id aid,a.class_id aclass,a.teacher_id ateacher,a.room_id aroom,"
                      + " a.teacher_name atname,a.room_name arname,a.lesson_date adate,"
                      + " a.starts_at astart,a.ends_at aend,ac.name aname,"
                      + " b.id bid,b.class_id bclass,b.lesson_date bdate,b.starts_at bstart,"
                      + " b.teacher_id bteacher,b.room_id broom,bc.name bname"
                      + " from teaching.lesson a"
                      + " join teaching.class ac on ac.id=a.class_id"
                      + " join teaching.lesson b on b.id<>a.id and b.lesson_date=a.lesson_date"
                      + "   and b.starts_at<a.ends_at and b.ends_at>a.starts_at"
                      + " join teaching.class bc on bc.id=b.class_id"
                      + " where ac.school_id=? and a.lesson_date between ? and ? and a.id<b.id"
                      + " order by a.lesson_date,a.starts_at,a.id limit 5000",
                  schoolId,
                  begin,
                  end)) {
            var aclass = row.get("aclass", UUID.class);
            var ateacher = row.get("ateacher", UUID.class);
            var aroom = row.get("aroom", UUID.class);
            if (aclass.equals(row.get("bclass", UUID.class)))
              addPairConflict(out, seen, "CLASS_OVERLAP", "本班同一时段还有一节课", row);
            if (ateacher != null && ateacher.equals(row.get("bteacher", UUID.class)))
              addPairConflict(out, seen, "TEACHER_CLASH", "该教师在同一时段已有课", row);
            if (aroom != null && aroom.equals(row.get("broom", UUID.class)))
              addPairConflict(out, seen, "ROOM_CLASH", "该教室在同一时段已被占用", row);
          } // 学员跨班重叠
          for (var row :
              sql.fetch(
                  "select a.id aid,a.class_id aclass,a.teacher_name atname,a.room_name arname,"
                      + " a.lesson_date adate,a.starts_at astart,a.ends_at aend,ac.name aname,"
                      + " b.id bid,b.class_id bclass,b.lesson_date bdate,b.starts_at bstart,"
                      + " bc.name bname,s.name student_name"
                      + " from teaching.lesson a"
                      + " join teaching.class ac on ac.id=a.class_id"
                      + " join teaching.enrollment_class ea on ea.class_id=a.class_id and ea.active"
                      + " join teaching.enrollment_class eb on eb.enrollment_id=ea.enrollment_id"
                      + "   and eb.active and eb.class_id<>a.class_id"
                      + " join teaching.lesson b on b.class_id=eb.class_id and b.lesson_date=a.lesson_date"
                      + "   and b.starts_at<a.ends_at and b.ends_at>a.starts_at"
                      + " join teaching.class bc on bc.id=b.class_id"
                      + " join enrollment.enrollment en on en.id=ea.enrollment_id"
                      + " join archive.student s on s.id=en.student_id"
                      + " where ac.school_id=? and a.lesson_date between ? and ?"
                      + " order by a.lesson_date,a.starts_at limit 2000",
                  schoolId,
                  begin,
                  end)) {
            addPairConflict(
                out,
                seen,
                "STUDENT_CLASH",
                "有学员在另一班同一时段有课：" + row.get("student_name", String.class),
                row);
          } // 停课日
          for (var row :
              sql.fetch(
                  "select l.id aid,l.class_id aclass,l.teacher_name atname,l.room_name arname,"
                      + " l.lesson_date adate,l.starts_at astart,l.ends_at aend,c.name aname,sc.reason"
                      + " from teaching.lesson l"
                      + " join teaching.class c on c.id=l.class_id"
                      + " join teaching.school_closure sc"
                      + "   on sc.school_id=c.school_id and sc.closed_on=l.lesson_date and sc.active"
                      + " where c.school_id=? and l.lesson_date between ? and ?"
                      + " order by l.lesson_date,l.starts_at limit 2000",
                  schoolId,
                  begin,
                  end)) {
            addPairConflict(
                out, seen, "SCHOOL_CLOSED", "该日期学校已停课：" + row.get("reason", String.class), row);
          } // 教师/教室被设为不可用
          for (var row :
              sql.fetch(
                  "select l.id aid,l.class_id aclass,l.teacher_name atname,l.room_name arname,"
                      + " l.lesson_date adate,l.starts_at astart,l.ends_at aend,c.name aname,rb.reason,"
                      + " case when rb.teacher_id is not null then 'TEACHER_UNAVAILABLE'"
                      + "      else 'ROOM_UNAVAILABLE' end as type"
                      + " from teaching.lesson l"
                      + " join teaching.class c on c.id=l.class_id"
                      + " join teaching.resource_block rb on rb.block_date=l.lesson_date"
                      + "   and rb.starts_at<l.ends_at and rb.ends_at>l.starts_at"
                      + "   and (rb.teacher_id=l.teacher_id or rb.room_id=l.room_id)"
                      + " where c.school_id=? and l.lesson_date between ? and ?"
                      + " order by l.lesson_date,l.starts_at limit 2000",
                  schoolId,
                  begin,
                  end)) {
            var kind = row.get("type", String.class);
            addPairConflict(
                out,
                seen,
                kind,
                ("TEACHER_UNAVAILABLE".equals(kind) ? "该教师此时间段被设为不可用：" : "该教室此时间段被设为不可用：")
                    + row.get("reason", String.class),
                row);
          }
          if (filter.isEmpty()) return out;
          return out.stream().filter(item -> filter.equals(item.type())).toList();
        });
  }

  @Override
  public List<TeacherBrief> teachers(UUID schoolId) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireReadable(scope, schoolId);
          return sql.fetch(
                  "select id,name from organization.teacher where school_id=? and active order by name limit 200",
                  schoolId)
              .map(r -> new TeacherBrief(r.get("id", UUID.class), r.get("name", String.class)));
        });
  }

  @Override
  public List<RevenueRow> revenue(UUID schoolId, String from, String to) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    var begin = businessDate(from, "开始日期");
    var end = businessDate(to, "结束日期");
    if (end.isBefore(begin)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    if (ChronoUnit.DAYS.between(begin, end) > 31) throw new IllegalArgumentException("一次最多查看 31 天");
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireReadable(scope, schoolId);
          return sql.fetch(
                  "select c.id class_id,c.name class_name,cur.id curriculum_id,cur.name curriculum_name,"
                      + " count(*) lessons,coalesce(sum(cs.hours),0) hours,coalesce(sum(cs.amount),0) amount"
                      + " from teaching.consumption cs"
                      + " join teaching.lesson l on l.id=cs.lesson_id"
                      + " join teaching.class c on c.id=l.class_id"
                      + " left join product.curriculum cur on cur.id=cs.curriculum_id"
                      + " where c.school_id=? and l.lesson_date between ? and ?"
                      + " group by c.id,c.name,cur.id,cur.name"
                      + " order by amount desc,c.name limit 200",
                  schoolId,
                  begin,
                  end)
              .map(
                  r ->
                      new RevenueRow(
                          r.get("class_id", UUID.class),
                          r.get("class_name", String.class),
                          r.get("curriculum_id", UUID.class),
                          r.get("curriculum_name", String.class),
                          r.get("lessons", Integer.class),
                          r.get("hours", BigDecimal.class),
                          r.get("amount", BigDecimal.class)))
;
        });
  }

  @Override
  public List<RevenueDetail> revenueDetails(UUID schoolId, UUID classId, String from, String to) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null || classId == null) throw new IllegalArgumentException("需要学校和班级");
    var begin = businessDate(from, "开始日期");
    var end = businessDate(to, "结束日期");
    if (end.isBefore(begin)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    if (ChronoUnit.DAYS.between(begin, end) > 31) throw new IllegalArgumentException("一次最多查看 31 天");
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireReadable(scope, schoolId);
          return sql.fetch(
                  "select cs.lesson_id,l.lesson_date,c.name class_name,s.name student_name,"
                      + " cur.name curriculum_name,cs.hours,cs.amount"
                      + " from teaching.consumption cs"
                      + " join teaching.lesson l on l.id=cs.lesson_id"
                      + " join teaching.class c on c.id=l.class_id"
                      + " join enrollment.enrollment e on e.id=cs.enrollment_id"
                      + " join archive.student s on s.id=e.student_id"
                      + " left join product.curriculum cur on cur.id=cs.curriculum_id"
                      + " where c.school_id=? and c.id=? and l.lesson_date between ? and ?"
                      + " order by l.lesson_date,l.starts_at,s.name limit 500",
                  schoolId,
                  classId,
                  begin,
                  end)
              .map(
                  r ->
                      new RevenueDetail(
                          r.get("lesson_id", UUID.class),
                          String.valueOf(r.get("lesson_date", LocalDate.class)),
                          r.get("class_name", String.class),
                          r.get("student_name", String.class),
                          r.get("curriculum_name", String.class),
                          r.get("hours", BigDecimal.class),
                          r.get("amount", BigDecimal.class)))
;
        });
  }

  @Override
  public List<NotificationView> notifications(UUID schoolId, boolean unreadOnly) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    return tx.execute(
        scope.tenantId(),
        () -> {
          requireReadable(scope, schoolId);
          return sql.fetch(
                  "select id,kind,title,body,created_at,read_at from teaching.notification"
                      + " where school_id=?"
                      + (unreadOnly ? " and read_at is null" : "")
                      + " order by created_at desc limit 100",
                  schoolId)
              .map(
                  r ->
                      new NotificationView(
                          r.get("id", UUID.class),
                          r.get("kind", String.class),
                          r.get("title", String.class),
                          r.get("body", String.class),
                          String.valueOf(r.get("created_at", java.time.OffsetDateTime.class)),
                          r.get("read_at", java.time.OffsetDateTime.class) != null))
;
        });
  }

  @Override
  public void markNotificationRead(UUID id) {
    var scope = actors.requireResourceScope("TEACHING");
    if (id == null) throw new IllegalArgumentException("需要通知");
    tx.execute(
        scope.tenantId(),
        () -> {
          var changed =
              sql.execute(
                  "update teaching.notification set read_at=coalesce(read_at,now()) where id=?", id);
          if (changed != 1) throw new TeachingConflict("NOTIFICATION_NOT_FOUND");
          return null;
        });
  }

  @Override
  public BatchLessonOutcome reassignTeacher(UUID teacherId, ReassignTeacher command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (teacherId == null
          || command == null
          || command.requestId() == null
          || command.newTeacherId() == null)
        throw new IllegalArgumentException("需要教师、新教师和请求身份");
      var begin = businessDate(command.from(), "开始日期");
      var end = businessDate(command.to(), "结束日期");
      if (end.isBefore(begin)) throw new IllegalArgumentException("结束日期不能早于开始日期");
      if (ChronoUnit.DAYS.between(begin, end) > 31) throw new IllegalArgumentException("一次最多改派 31 天");
      var actor = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          actor.tenantId(),
          () -> {
            schools.lockTenant(actor.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var school =
                sql.fetchOne(
                        "select school_id from organization.teacher where id=?", teacherId)
                    .get("school_id", UUID.class);
            if (!verified.schools().contains(school))
              throw new AccessDeniedException("无该学校的教务维护权限");
            var target = resource(command.newTeacherId(), "TEACHER", school, null);
            var done = new ArrayList<UUID>();
            var skipped = new ArrayList<BatchLessonOutcome.Skipped>();
            for (var row :
                sql.fetch(
                    "select l.id,l.status,l.version,l.lesson_date,l.starts_at,l.ends_at,"
                        + " l.room_id,l.room_name"
                        + " from teaching.lesson l join teaching.class c on c.id=l.class_id"
                        + " where l.teacher_id=? and l.lesson_date between ? and ? and c.school_id=?"
                        + " order by l.lesson_date,l.starts_at limit 200",
                    teacherId,
                    begin,
                    end,
                    school)) {
              var id = row.get("id", UUID.class);
              var status = row.get("status", String.class);
              if (!Set.of("DRAFT", "PUBLISHED").contains(status)) {
                skipped.add(new BatchLessonOutcome.Skipped(id, "LESSON_NOT_RESCHEDULABLE"));
                continue;
              }
              try {
                assertSlotFree(
                    sql.fetchOne("select class_id from teaching.lesson where id=?", id)
                        .get("class_id", UUID.class),
                    school,
                    row.get("lesson_date", LocalDate.class),
                    row.get("starts_at", LocalTime.class),
                    row.get("ends_at", LocalTime.class),
                    target.id(),
                    target.name(),
                    row.get("room_id", UUID.class),
                    row.get("room_name", String.class),
                    id);
              } catch (TeachingConflict conflict) {
                skipped.add(new BatchLessonOutcome.Skipped(id, conflict.code()));
                continue;
              }
              var changed =
                  sql.execute(
                      "update teaching.lesson set teacher_id=?,teacher_name=?,version=version+1"
                          + " where id=? and version=? and status in ('DRAFT','PUBLISHED')",
                      target.id(),
                      target.name(),
                      id,
                      row.get("version", Long.class));
              if (changed != 1) {
                skipped.add(new BatchLessonOutcome.Skipped(id, "VERSION_CONFLICT"));
                continue;
              }
              auditLessonsReassigned(verified, id, school, target.name());
              done.add(id);
            }
            if (!done.isEmpty())
              notifySchool(
                  verified,
                  school,
                  "TEACHER_REASSIGNED",
                  "教师课次改派",
                  "共 " + done.size() + " 节课改派给 " + target.name(),
                  done.get(0));
            return new BatchLessonOutcome(done.size(), List.copyOf(done), List.copyOf(skipped));
          });
    }
  }

  private void auditLessonsReassigned(Actor actor, UUID lessonId, UUID schoolId, String teacherName) {
    audit(actor, "LESSONS_REASSIGNED", lessonId, schoolId, "teacher=" + teacherName);
  }

  private void notifySchool(
      Actor actor, UUID schoolId, String kind, String title, String body, UUID refId) {
    sql.execute(
        "insert into teaching.notification(id,tenant_id,school_id,kind,title,body,ref_type,ref_id)"
            + " values (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        actor.tenantId(),
        schoolId,
        kind,
        title,
        body,
        "LESSON",
        refId);
    audit(actor, "NOTIFICATION_CREATED", refId, schoolId, kind);
  }

  /** 冲突中心按“类型 + 课次对”去重；两节课的先后不影响去重键。 */
  private static void addPairConflict(
      List<SchoolConflict> out, Set<String> seen, String type, String message, Record row) {
    var lessonId = row.get("aid", UUID.class);
    var otherId = row.get("bid", UUID.class);
    var first = lessonId.toString().compareTo(otherId.toString()) < 0 ? lessonId : otherId;
    var second = first.equals(lessonId) ? otherId : lessonId;
    if (!seen.add(type + "|" + first + "|" + second)) return;
    out.add(
        new SchoolConflict(
            lessonId,
            row.get("aname", String.class),
            String.valueOf(row.get("adate", LocalDate.class)),
            String.valueOf(row.get("astart", LocalTime.class)),
            String.valueOf(row.get("aend", LocalTime.class)),
            row.get("atname", String.class),
            row.get("arname", String.class),
            type,
            message,
            otherId,
            row.get("bname", String.class),
            String.valueOf(row.get("bdate", LocalDate.class)),
            String.valueOf(row.get("bstart", LocalTime.class))));
  }

  /** 单课次冲突（停课、教师/教室不可用），没有对照课次。 */
  private static void addSingleConflict(
      List<SchoolConflict> out, Set<String> seen, String type, String message, Record row) {
    var lessonId = row.get("aid", UUID.class);
    if (!seen.add(type + "|" + lessonId)) return;
    out.add(
        new SchoolConflict(
            lessonId,
            row.get("aname", String.class),
            String.valueOf(row.get("adate", LocalDate.class)),
            String.valueOf(row.get("astart", LocalTime.class)),
            String.valueOf(row.get("aend", LocalTime.class)),
            row.get("atname", String.class),
            row.get("arname", String.class),
            type,
            message,
            null,
            null,
            null,
            null));
  }

  @Override
  public MoveScheme moveScheme(UUID lessonId, MoveSchemeQuery query) {
    var scope = actors.requireResourceScope("TEACHING");
    var start = businessDate(query == null ? null : query.startDate(), "开始日期");
    var end = businessDate(query == null ? null : query.endDate(), "结束日期");
    if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 30)
      throw new IllegalArgumentException("一次最多查看 31 天");
    var formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var source = requireLesson(scope, lessonId);
          var classId = source.get("class_id", UUID.class);
          var schoolId = classSchool(classId);
          requireReadable(scope, schoolId);
          var teacherId = source.get("teacher_id", UUID.class);
          var roomId = source.get("room_id", UUID.class);
          var sourceStart = source.get("starts_at", LocalTime.class);
          var sourceEnd = source.get("ends_at", LocalTime.class);
          var duration = (int) java.time.Duration.between(sourceStart, sourceEnd).toMinutes();
          var spaces = new ArrayList<int[]>();
          for (var row :
              sql.fetch(
                  "select begin_minute,end_minute from teaching.school_minute_space"
                      + " where school_id=? order by begin_minute",
                  schoolId)) {
            spaces.add(
                new int[] {
                  row.get("begin_minute", Integer.class), row.get("end_minute", Integer.class)
                });
          }
          if (spaces.isEmpty()) {
            for (int b = 480; b + duration <= 1260 && spaces.size() < 12; b += duration) {
              spaces.add(new int[] {b, b + duration});
            }
          }
          var gapRow =
              sql.fetchOne(
                  "select min_gap_minutes from teaching.schedule_policy where school_id=?",
                  schoolId);
          var gap = gapRow == null ? 0 : gapRow.get("min_gap_minutes", Integer.class);
          var closures = new HashSet<LocalDate>();
          for (var row :
              sql.fetch(
                  "select closed_on from teaching.school_closure"
                      + " where school_id=? and active and closed_on between ? and ?",
                  schoolId,
                  start,
                  end)) {
            closures.add(row.get("closed_on", LocalDate.class));
          }
          var own = new HashMap<LocalDate, List<LocalTime[]>>();
          for (var row :
              sql.fetch(
                  "select lesson_date,starts_at,ends_at from teaching.lesson"
                      + " where class_id=? and id<>? and lesson_date between ? and ?",
                  classId,
                  lessonId,
                  start,
                  end)) {
            own.computeIfAbsent(row.get("lesson_date", LocalDate.class), k -> new ArrayList<>())
                .add(
                    new LocalTime[] {
                      row.get("starts_at", LocalTime.class), row.get("ends_at", LocalTime.class)
                    });
          }
          var teacherBusy =
              teacherId == null
                  ? Map.<LocalDate, List<LocalTime[]>>of()
                  : busyByDate(
                      "select lesson_date,starts_at,ends_at from teaching.lesson"
                          + " where teacher_id=? and id<>? and lesson_date between ? and ?",
                      teacherId,
                      lessonId,
                      start,
                      end);
          var roomBusy =
              roomId == null
                  ? Map.<LocalDate, List<LocalTime[]>>of()
                  : busyByDate(
                      "select lesson_date,starts_at,ends_at from teaching.lesson"
                          + " where room_id=? and id<>? and lesson_date between ? and ?",
                      roomId,
                      lessonId,
                      start,
                      end);
          var studentBusy =
              busyByDate(
                  "select distinct l.lesson_date,l.starts_at,l.ends_at"
                      + " from teaching.enrollment_class mine"
                      + " join teaching.enrollment_class theirs on theirs.enrollment_id=mine.enrollment_id"
                      + "   and theirs.active and theirs.class_id<>mine.class_id"
                      + " join teaching.lesson l on l.class_id=theirs.class_id"
                      + "   and l.lesson_date between ? and ? and l.id<>?"
                      + " where mine.class_id=? and mine.active",
                  start,
                  end,
                  lessonId,
                  classId);
          var today = LocalDate.now();
          var days = new ArrayList<MoveScheme.Day>();
          for (var date = start; !date.isAfter(end); date = date.plusDays(1)) {
            var cells = new ArrayList<MoveScheme.Cell>();
            for (var space : spaces) {
              var begin = LocalTime.of(space[0] / 60, space[0] % 60);
              var finish = LocalTime.of(space[1] / 60, space[1] % 60); // 落到该时段的起点，课次保持原时长；放不下才判不可用。
              var targetFinish = begin.plusMinutes(duration);
              String status = "FREE";
              String reason = null;
              if (date.isBefore(today)) {
                status = "OTHER";
                reason = "历史日期不可排";
              } else if (closures.contains(date)) {
                status = "STUDENT_CLASH";
                reason = "学校停课";
              } else if (targetFinish.isAfter(finish)) {
                status = "OTHER";
                reason = "课次时长超过该时段";
              } else if (overlaps(own.get(date), begin, targetFinish, gap)) {
                status = "CLASS_OCCUPIED";
                reason = "本班该时段已有课次";
              } else if (overlaps(studentBusy.get(date), begin, targetFinish, 0)) {
                status = "STUDENT_CLASH";
                reason = "有学员在另一班同一时段有课";
              } else if (overlaps(teacherBusy.get(date), begin, targetFinish, gap)) {
                status = "TEACHER_CLASH";
                reason = "该教师同一时段已有课";
              } else if (overlaps(roomBusy.get(date), begin, targetFinish, 0)) {
                status = "ROOM_CLASH";
                reason = "该教室同一时段已被占用";
              }
              cells.add(
                  new MoveScheme.Cell(
                      begin.format(formatter), targetFinish.format(formatter), status, reason));
            }
            days.add(new MoveScheme.Day(date.toString(), cells));
          }
          return new MoveScheme(lessonId, days);
        });
  }

  @Override
  public List<SwapSchemePlan> swapSchemes(UUID lessonId, SwapSchemeQuery query) {
    var scope = actors.requireResourceScope("TEACHING");
    var start = businessDate(query == null ? null : query.startDate(), "搜索起始日期");
    var end = businessDate(query == null ? null : query.endDate(), "搜索结束日期");
    if (end.isBefore(start)) throw new IllegalArgumentException("结束日期不能早于开始日期");
    if (java.time.temporal.ChronoUnit.DAYS.between(start, end) > 30)
      throw new IllegalArgumentException("一次最多查看 31 天");
    return tx.execute(
        scope.tenantId(),
        () -> {
          var source = requireLesson(scope, lessonId);
          var classId = source.get("class_id", UUID.class);
          var schoolId = classSchool(classId);
          requireReadable(scope, schoolId);
          var duration =
              (int)
                  java.time.Duration.between(
                          source.get("starts_at", LocalTime.class),
                          source.get("ends_at", LocalTime.class))
                      .toMinutes();
          var spaces = new ArrayList<int[]>();
          for (var row :
              sql.fetch(
                  "select begin_minute,end_minute from teaching.school_minute_space"
                      + " where school_id=? order by begin_minute",
                  schoolId)) {
            spaces.add(
                new int[] {
                  row.get("begin_minute", Integer.class), row.get("end_minute", Integer.class)
                });
          }
          if (spaces.isEmpty()) {
            for (int b = 480; b + duration <= 1260 && spaces.size() < 12; b += duration) {
              spaces.add(new int[] {b, b + duration});
            }
          }
          var gapRow =
              sql.fetchOne(
                  "select min_gap_minutes from teaching.schedule_policy where school_id=?",
                  schoolId);
          var gap = gapRow == null ? 0 : gapRow.get("min_gap_minutes", Integer.class);
          var closures = new HashSet<LocalDate>();
          for (var row :
              sql.fetch(
                  "select closed_on from teaching.school_closure"
                      + " where school_id=? and active and closed_on between ? and ?",
                  schoolId,
                  start,
                  end)) {
            closures.add(row.get("closed_on", LocalDate.class));
          }
          var roster = new HashMap<UUID, Set<UUID>>();
          for (var row :
              sql.fetch(
                  "select ec.class_id,ec.enrollment_id from teaching.enrollment_class ec"
                      + " join teaching.class c on c.id=ec.class_id"
                      + " where c.school_id=? and ec.active",
                  schoolId)) {
            roster
                .computeIfAbsent(row.get("class_id", UUID.class), k -> new HashSet<>())
                .add(row.get("enrollment_id", UUID.class));
          }
          var state = new ArrayList<LessonSlot>();
          for (var row :
              sql.fetch(
                  "select l.id,l.class_id,c.name as class_name,l.teacher_id,l.room_id,"
                      + "l.lesson_date,l.starts_at,l.ends_at"
                      + " from teaching.lesson l join teaching.class c on c.id=l.class_id"
                      + " where c.school_id=? and l.lesson_date between ? and ?",
                  schoolId,
                  start,
                  end)) {
            state.add(
                new LessonSlot(
                    row.get("id", UUID.class),
                    row.get("class_id", UUID.class),
                    row.get("class_name", String.class),
                    row.get("teacher_id", UUID.class),
                    row.get("room_id", UUID.class),
                    row.get("lesson_date", LocalDate.class),
                    row.get("starts_at", LocalTime.class),
                    row.get("ends_at", LocalTime.class)));
          }
          var root =
              state.stream()
                  .filter(item -> item.lessonId().equals(lessonId))
                  .findFirst()
                  .orElseThrow(() -> new TeachingConflict("LESSON_NOT_FOUND"));
          var search =
              new SwapSearch(
                  state, spaces, start, end, LocalDate.now(), closures, gap, duration, roster);
          var formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
          var plans = new ArrayList<SwapSchemePlan>();
          int index = 1;
          int singles = 0;
          var found = search.run(root);
          for (var chain : found) { // 简单方案最多列 10 个；剩下的名额留给链式方案，避免“一眼全是 1 步”把链式挤掉。
            if (chain.size() == 1 && singles >= 10) continue;
            if (plans.size() >= 20) break;
            var moves = new ArrayList<SwapSchemePlan.Move>();
            for (var move : chain) {
              var before =
                  state.stream()
                      .filter(item -> item.lessonId().equals(move.lessonId()))
                      .findFirst()
                      .orElse(move);
              moves.add(
                  new SwapSchemePlan.Move(
                      move.lessonId(),
                      move.className(),
                      null,
                      before.date().toString(),
                      before.start().format(formatter),
                      before.end().format(formatter),
                      move.date().toString(),
                      move.start().format(formatter),
                      move.end().format(formatter)));
            }
            if (chain.size() == 1) singles++;
            plans.add(new SwapSchemePlan("方案" + index++, moves.size(), moves));
          }
          return plans;
        });
  }

  /** 搜索用的课次占用快照。 */
  private record LessonSlot(
      UUID lessonId,
      UUID classId,
      String className,
      UUID teacherId,
      UUID roomId,
      LocalDate date,
      LocalTime start,
      LocalTime end) {}

  /**
   * * 链式调课搜索：从源头课次出发，优先找空闲落点；落点被别的课次挡住时，先把挡路的课次挪走（再挡再挪）， * 深度上限 5、方案上限 20，与旧系统 SwapProcessor 的上限一致。
   */
  private static final class SwapSearch {
    private final List<LessonSlot> base;
    private final List<int[]> spaces;
    private final LocalDate start;
    private final LocalDate end;
    private final LocalDate today;
    private final Set<LocalDate> closures;
    private final int gap;
    private final int duration;
    private final Map<UUID, Set<UUID>> roster;
    private final List<List<LessonSlot>> found = new ArrayList<>();
    private final Set<String> signatures = new HashSet<>();

    SwapSearch(
        List<LessonSlot> base,
        List<int[]> spaces,
        LocalDate start,
        LocalDate end,
        LocalDate today,
        Set<LocalDate> closures,
        int gap,
        int duration,
        Map<UUID, Set<UUID>> roster) {
      this.base = base;
      this.spaces = spaces;
      this.start = start;
      this.end = end;
      this.today = today;
      this.closures = closures;
      this.gap = gap;
      this.duration = duration;
      this.roster = roster;
    }

    List<List<LessonSlot>> run(LessonSlot root) {
      var visited = new HashSet<UUID>();
      visited.add(root.lessonId());
      collect(root, 1, base, visited);
      found.sort(
          (left, right) -> {
            int bySteps = Integer.compare(left.size(), right.size());
            if (bySteps != 0) return bySteps;
            return left.get(left.size() - 1).date().compareTo(right.get(right.size() - 1).date());
          });
      return found;
    }

    private void collect(LessonSlot lesson, int depth, List<LessonSlot> state, Set<UUID> visited) {
      if (found.size() >= 200 || depth > 5) return;
      for (var candidate : targets(lesson)) {
        if (found.size() >= 200) return;
        var placed =
            new LessonSlot(
                lesson.lessonId(),
                lesson.classId(),
                lesson.className(),
                lesson.teacherId(),
                lesson.roomId(),
                candidate.date(),
                candidate.start(),
                candidate.end());
        if (placed.date().equals(lesson.date()) && placed.start().equals(lesson.start())) continue;
        var blockers = blockers(placed, state);
        if (blockers.isEmpty()) {
          emit(List.of(placed));
          continue;
        }
        if (depth >= 5) continue;
        for (var blocker : blockers) {
          if (found.size() >= 200) return;
          if (!visited.add(blocker.lessonId())) continue;
          var before = found.size();
          collect(
              blocker, depth + 1, state,
              visited); // 先取出这一轮新产出的链；后续 emit 会向 found 追加，不能再用实时的 size 计算清理区间。
          var pending = new ArrayList<>(found.subList(before, found.size()));
          if (!pending.isEmpty()) {
            var after = apply(state, pending);
            if (blockers(placed, after).isEmpty()) {
              for (var sub : pending) {
                var chain = new ArrayList<>(sub);
                chain.add(placed);
                emit(chain);
              }
              found.subList(before, before + pending.size()).clear();
            }
          }
          visited.remove(blocker.lessonId());
        }
      }
    }

    private List<LessonSlot> apply(List<LessonSlot> state, List<List<LessonSlot>> chains) {
      var next = new ArrayList<>(state);
      for (var chain : chains) {
        for (var move : chain) {
          next.removeIf(item -> item.lessonId().equals(move.lessonId()));
          next.add(move);
        }
      }
      return next;
    }

    private void emit(List<LessonSlot> chain) {
      var signature =
          chain.stream()
              .map(item -> item.lessonId() + "@" + item.date() + "T" + item.start())
              .sorted()
              .reduce("", (left, right) -> left + "|" + right);
      if (!signatures.add(signature)) return;
      found.add(List.copyOf(chain));
    }

    private List<LocalDate> dates() {
      var out = new ArrayList<LocalDate>();
      for (var date = start; !date.isAfter(end); date = date.plusDays(1)) out.add(date);
      return out;
    }

    private List<LessonSlot> targets(LessonSlot lesson) {
      var out = new ArrayList<LessonSlot>();
      for (var date : dates()) {
        if (date.isBefore(today) || closures.contains(date)) continue;
        for (var space : spaces) {
          if (space[1] - space[0] < duration) continue;
          var begin = LocalTime.of(space[0] / 60, space[0] % 60);
          var finish = begin.plusMinutes(duration);
          out.add(
              new LessonSlot(
                  lesson.lessonId(),
                  lesson.classId(),
                  lesson.className(),
                  lesson.teacherId(),
                  lesson.roomId(),
                  date,
                  begin,
                  finish));
        }
      }
      return out;
    }

    private List<LessonSlot> blockers(LessonSlot placed, List<LessonSlot> state) {
      var out = new LinkedHashMap<UUID, LessonSlot>();
      var students = roster.getOrDefault(placed.classId(), Set.of());
      for (var other : state) {
        if (other.lessonId().equals(placed.lessonId())) continue;
        if (!other.date().equals(placed.date())) continue;
        boolean hitsClass =
            other.classId().equals(placed.classId())
                && overlaps(other.start(), other.end(), placed.start(), placed.end(), gap);
        boolean hitsTeacher =
            placed.teacherId() != null
                && placed.teacherId().equals(other.teacherId())
                && overlaps(other.start(), other.end(), placed.start(), placed.end(), gap);
        boolean hitsRoom =
            placed.roomId() != null
                && placed.roomId().equals(other.roomId())
                && overlaps(other.start(), other.end(), placed.start(), placed.end(), 0);
        boolean hitsStudent = false;
        if (!students.isEmpty()) {
          var theirs = roster.getOrDefault(other.classId(), Set.of());
          if (!java.util.Collections.disjoint(students, theirs)) {
            hitsStudent = overlaps(other.start(), other.end(), placed.start(), placed.end(), 0);
          }
        }
        if (hitsClass || hitsTeacher || hitsRoom || hitsStudent)
          out.putIfAbsent(other.lessonId(), other);
      }
      return new ArrayList<>(out.values());
    }

    private static boolean overlaps(
        LocalTime otherStart,
        LocalTime otherEnd,
        LocalTime begin,
        LocalTime ifEnd,
        int gapMinutes) {
      var paddedStart = begin.minusMinutes(gapMinutes);
      var paddedEnd = ifEnd.plusMinutes(gapMinutes);
      return otherStart.isBefore(paddedEnd) && otherEnd.isAfter(paddedStart);
    }
  }

  @Override
  public List<UUID> executeSwapScheme(UUID lessonId, ExecuteSwapScheme command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      if (lessonId == null
          || command == null
          || command.requestId() == null
          || command.moves() == null
          || command.moves().isEmpty()
          || command.moves().size() > 5)
        throw new IllegalArgumentException("需要源头课次、步骤（1–5 步）和请求身份");
      var reason = command.reason() == null ? "" : command.reason().strip();
      if (reason.isEmpty() || reason.length() > 200)
        throw new IllegalArgumentException("调课原因必填且不能超过 200 字");
      var actor = actors.requireAction("TEACHING_MAINTAIN");
      var digestBuilder = new StringBuilder("SWAP-SCHEME");
      for (var move : command.moves()) {
        digestBuilder
            .append('|')
            .append(move.lessonId())
            .append('@')
            .append(move.newLessonDate())
            .append('T')
            .append(move.newStartsAt());
      }
      var digest = fingerprint(digestBuilder.toString(), reason);
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
              if (!lessonId.equals(previous.get("lesson_id", UUID.class))
                  || !digest.equals(previous.get("fingerprint", String.class)))
                throw new TeachingConflict("IDEMPOTENCY_MISMATCH");
              return command.moves().stream().map(SwapSchemePlan.Move::lessonId).toList();
            }
            var moved = new ArrayList<UUID>();
            UUID schoolId = null;
            for (var move : command.moves()) {
              var row = requireLessonForUpdate(move.lessonId());
              var moveClassId = row.get("class_id", UUID.class);
              var moveSchoolId = classSchool(moveClassId);
              if (!moveSchoolId.equals(schoolId) && schoolId != null)
                throw new TeachingConflict("SWAP_CLASS_MISMATCH");
              schoolId = moveSchoolId;
              if (!verified.schools().contains(schoolId))
                throw new AccessDeniedException("无该学校的教务维护权限");
              if (!schools.require(schoolId).active())
                throw new AccessDeniedException("学校已停用，不能调课");
              if (!"PUBLISHED".equals(row.get("status", String.class)))
                throw new TeachingConflict("LESSON_NOT_RESCHEDULABLE");
              if (row.get("attendance_count", Integer.class) != 0
                  || sql.fetchExists(
                      sql.selectOne()
                          .from("teaching.consumption")
                          .where("lesson_id=?", move.lessonId())))
                throw new TeachingConflict("LESSON_HAS_BUSINESS_FACTS");
              var date = businessDate(move.newLessonDate(), "目标日期");
              var startsAt = time(move.newStartsAt(), "目标开始时间");
              var endsAt = time(move.newEndsAt(), "目标结束时间");
              int updated =
                  sql.execute(
                      "update teaching.lesson set lesson_date=?,starts_at=?,ends_at=?,version=version+1"
                          + " where id=? and version=? and status='PUBLISHED'",
                      date,
                      startsAt,
                      endsAt,
                      move.lessonId(),
                      row.get("version", Long.class));
              if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
              sql.execute(
                  "insert into teaching.lesson_reschedule(id,tenant_id,lesson_id,actor_id,request_id,"
                      + "old_lesson_date,old_starts_at,old_ends_at,new_lesson_date,new_starts_at,new_ends_at,reason)"
                      + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
                  UUID.randomUUID(),
                  actor.tenantId(),
                  move.lessonId(),
                  actor.accountId(),
                  command.requestId(),
                  row.get("lesson_date", LocalDate.class),
                  row.get("starts_at", LocalTime.class),
                  row.get("ends_at", LocalTime.class),
                  date,
                  startsAt,
                  endsAt,
                  reason);
              audit(verified, "LESSON_RESCHEDULED", move.lessonId(), schoolId, "swap-scheme");
              moved.add(move.lessonId());
            }
            for (var move : command.moves()) {
              var row = requireLessonForUpdate(move.lessonId());
              ensureLessonSlotAvailable(
                  row,
                  schoolId,
                  row.get("lesson_date", LocalDate.class),
                  row.get("starts_at", LocalTime.class),
                  row.get("ends_at", LocalTime.class));
            }
            sql.execute(
                "insert into teaching.lesson_reschedule_request(tenant_id,actor_id,request_id,lesson_id,fingerprint)"
                    + " values (?,?,?,?,?)",
                actor.tenantId(),
                actor.accountId(),
                command.requestId(),
                lessonId,
                digest);
            audit(verified, "SWAP_SCHEME_EXECUTED", lessonId, schoolId, "steps=" + moved.size());
            return moved;
          });
    }
  }

  private Map<LocalDate, List<LocalTime[]>> busyByDate(String sqlText, Object... args) {
    var out = new HashMap<LocalDate, List<LocalTime[]>>();
    for (var row : sql.fetch(sqlText, args)) {
      out.computeIfAbsent(row.get("lesson_date", LocalDate.class), k -> new ArrayList<>())
          .add(
              new LocalTime[] {
                row.get("starts_at", LocalTime.class), row.get("ends_at", LocalTime.class)
              });
    }
    return out;
  }

  private static boolean overlaps(
      List<LocalTime[]> busy, LocalTime begin, LocalTime end, int gapMinutes) {
    if (busy == null) return false;
    var paddedStart = begin.minusMinutes(gapMinutes);
    var paddedEnd = end.plusMinutes(gapMinutes);
    for (var slot : busy) {
      if (slot[0].isBefore(paddedEnd) && slot[1].isAfter(paddedStart)) return true;
    }
    return false;
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
                    + "teacher_name,room_name,topic,hours,teacher_id,room_id,curriculum_id)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
                source.get("room_id", UUID.class),
                source.get("curriculum_id", UUID.class));
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
    assertSlotFree(
        lesson.get("class_id", UUID.class),
        schoolId,
        date,
        startsAt,
        endsAt,
        lesson.get("teacher_id", UUID.class),
        lesson.get("teacher_name", String.class),
        lesson.get("room_id", UUID.class),
        lesson.get("room_name", String.class),
        lesson.get("id", UUID.class));
  }

  /**
   * * 班课程计划课时上限：class_curriculum.hours 为 0 表示不限制。 * *
   *
   * <p>建课次时校验，避免把超额课时留到关课才失败。已排课时按班+课程汇总（含草稿与已确认）。
   */
  private void assertCurriculumHoursAvailable(UUID classId, UUID curriculumId, BigDecimal adding) {
    if (curriculumId == null) return;
    var plan =
        sql.fetchOne(
            "select hours from teaching.class_curriculum where class_id=? and curriculum_id=?",
            classId,
            curriculumId);
    if (plan == null) return;
    var planned = plan.get("hours", BigDecimal.class);
    if (planned == null || planned.signum() <= 0) return;
    var used =
        sql.fetchOne(
                "select coalesce(sum(hours),0) as total from teaching.lesson"
                    + " where class_id=? and curriculum_id=?",
                classId,
                curriculumId)
            .get("total", BigDecimal.class);
    if (used.add(adding).compareTo(planned) > 0)
      throw new TeachingConflict("CURRICULUM_HOURS_EXCEEDED");
  }

  /** 同一套时段校验供"改时间"和"批量建课次"共用；excludeLessonId 为空表示新建。 */
  private void assertSlotFree(
      UUID classId,
      UUID schoolId,
      LocalDate date,
      LocalTime startsAt,
      LocalTime endsAt,
      UUID teacherId,
      String teacherName,
      UUID roomId,
      String roomName,
      UUID excludeLessonId) {
    var self =
        excludeLessonId == null
            ? UUID.fromString("00000000-0000-0000-0000-000000000000")
            : excludeLessonId;
    if (sql.fetchExists(
        sql.selectOne()
            .from("teaching.lesson")
            .where(
                "class_id=? and id<>? and lesson_date=? and starts_at<? and ends_at>?",
                classId,
                self,
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
                      self,
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
                        self,
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
                  self,
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
          } // 实际教师/教室只在确认时落一次；为空表示与排课值相同，用 coalesce 固化快照。
          var actualTeacher =
              command.actualTeacherId() == null
                  ? null
                  : resource(command.actualTeacherId(), "TEACHER", schoolId, null);
          var actualRoom =
              command.actualRoomId() == null
                  ? null
                  : resource(command.actualRoomId(), "ROOM", schoolId, null);
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
                      + "actual_teacher_id=coalesce(cast(? as uuid),teacher_id),"
                      + "actual_teacher_name=coalesce(cast(? as varchar),teacher_name),"
                      + "actual_room_id=coalesce(cast(? as uuid),room_id),"
                      + "actual_room_name=coalesce(cast(? as varchar),room_name),"
                      + "version=version+1 where id=? and version=? and status='PUBLISHED'",
                  actualTeacher == null ? null : actualTeacher.id(),
                  actualTeacher == null ? null : actualTeacher.name(),
                  actualRoom == null ? null : actualRoom.id(),
                  actualRoom == null ? null : actualRoom.name(),
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
          audit(record, "ATTENDANCE_CONFIRMED", id, schoolId, "attendance");
          return lessonView(requireLessonForUpdate(id));
        });
  }

  @Override
  public LessonView saveAttendance(UUID id, SaveAttendance command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return saveAttendanceWithTrace(id, command);
    }
  }

  /** 只落考勤，不确认；批量录考勤用。确认仍走 confirmAttendance。 */
  private LessonView saveAttendanceWithTrace(UUID id, SaveAttendance command) {
    if (id == null || command == null || command.requestId() == null)
      throw new IllegalArgumentException("需要课次和请求号");
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
          var members =
              new HashSet<>(
                  sql.fetch(
                          "select enrollment_id from teaching.enrollment_class"
                              + " where class_id=? and active",
                          row.get("class_id", UUID.class))
                      .map(item -> item.get("enrollment_id", UUID.class)));
          if (members.isEmpty()) throw new TeachingConflict("CLASS_HAS_NO_MEMBER");
          var inputs = command.records() == null ? List.<AttendanceInput>of() : command.records();
          if (inputs.isEmpty()) throw new IllegalArgumentException("需要至少一条考勤");
          var duration =
              java.time.Duration.between(
                      row.get("starts_at", LocalTime.class), row.get("ends_at", LocalTime.class))
                  .toMinutes();
          var provided = new HashMap<UUID, AttendanceInput>();
          for (var input : inputs) {
            if (input == null
                || input.enrollmentId() == null
                || !ATTENDANCE.contains(input.status()))
              throw new IllegalArgumentException("考勤状态无效");
            if (!members.contains(input.enrollmentId()))
              throw new TeachingConflict("ENROLLMENT_NOT_ACTIVE");
            if ("PARTIAL".equals(input.status())) {
              if (input.attendedMinutes() == null
                  || input.attendedMinutes() <= 0
                  || input.attendedMinutes() >= duration)
                throw new IllegalArgumentException("部分缺勤分钟必须大于 0 且少于课次时长");
            } else if (input.attendedMinutes() != null) {
              throw new IllegalArgumentException("仅部分缺勤可以填写实际出勤分钟");
            }
            if (provided.put(input.enrollmentId(), input) != null)
              throw new IllegalArgumentException("同一次考勤出现重复报名");
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
          audit(record, "ATTENDANCE_SAVED", id, schoolId, "records=" + provided.size());
          return lessonView(requireLessonForUpdate(id));
        });
  }

  @Override
  public LessonView updateLessonNote(UUID id, UpdateLessonNote command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      return updateLessonNoteWithTrace(id, command);
    }
  }

  /** 课次备注是可编辑的展示字段：只校验维护权限与状态，不触发冲突检测。 */
  private LessonView updateLessonNoteWithTrace(UUID id, UpdateLessonNote command) {
    if (id == null || command == null || command.requestId() == null)
      throw new IllegalArgumentException("需要课次和请求号");
    if (command.expectedVersion() == null) throw new IllegalArgumentException("需要课次版本");
    var note = command.memo() == null ? null : command.memo().strip();
    if (note != null && note.length() > 200) throw new IllegalArgumentException("备注最多 200 字");
    var maintain = actors.requireAction("TEACHING_MAINTAIN");
    return tx.execute(
        maintain.tenantId(),
        () -> {
          schools.lockTenant(maintain.tenantId());
          var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
          var row = requireLessonForUpdate(id);
          var schoolId = classSchool(row.get("class_id", UUID.class));
          if (!verified.schools().contains(schoolId))
            throw new AccessDeniedException("无该学校的教务维护权限");
          if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能修改课次");
          if (!Set.of("DRAFT", "PUBLISHED").contains(row.get("status", String.class)))
            throw new TeachingConflict("LESSON_NOT_ASSIGNABLE");
          if (Objects.equals(note, row.get("memo", String.class))) return lessonView(row);
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new TeachingConflict("VERSION_CONFLICT");
          int updated =
              sql.execute(
                  "update teaching.lesson set memo=?,version=version+1"
                      + " where id=? and version=? and status in ('DRAFT','PUBLISHED')",
                  note,
                  id,
                  command.expectedVersion());
          if (updated != 1) throw new TeachingConflict("VERSION_CONFLICT");
          audit(verified, "LESSON_NOTE_UPDATED", id, schoolId, note == null ? "" : note);
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
            throw new TeachingConflict(
                "LESSON_NOT_CONFIRMED"); // 结算模式防护：历史数据若是未实现的模式，宁可拒绝消费也不按模式 1 静默计算。
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
                  .toMinutes(); // 课次课程：多课程班里每节课可能属于不同课程，必须按课次自身取，不能回退到班主课程。
          var lessonCurriculum = row.get("curriculum_id", UUID.class);
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
            if (mode < 1 || mode > 5)
              throw new TeachingConflict(
                  "SETTLEMENT_MODE_UNSUPPORTED"); // 课程维度模式：2/3/5 只对「报名的课程」计价，课次课程不在明细内则本期不产生消耗。
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
            } // 模式 4/5 是"退课后实际费用"变体：先扣掉已退费用与已退课时，再算单价。
            // 模式 1（平均价格）在旧系统里就是"总价 − 优惠"再除以总课时
            // （FClassStudent#getConsumeEHM: fee = getTotalPrice() - getPriceDiscount()），这里对齐。
            if (mode == 1) {
              fee = fee.subtract(discountTotal(enrollment));
              if (fee.signum() < 0) fee = BigDecimal.ZERO;
            } // 模式 4/5 是"退课后实际费用"变体：先扣优惠，再扣已退费用与已退课时。
            // 优惠只减少应收，不摊进服务费（服务费按既有口径不摊销）。
            if (mode == 4) {
              fee = fee.subtract(discountTotal(enrollment)).subtract(cancelledAmount(enrollmentId));
              feeHours = feeHours.subtract(enrollment.cancelledHours());
            } else if (mode == 5
                && consumedCurriculum
                    != null) { // 旧系统模式 5 只减已退费用、不减优惠（FClassStudent#getConsumeEHM），先照旧系统。
              // 这会带来"课程模式消耗可能超过折后应收"的疑点，已登记为 Q35 待确认。
              for (var course : enrollment.courses())
                if (course.curriculumId().equals(consumedCurriculum)) {
                  fee = fee.subtract(course.cancelledAmount());
                  feeHours = feeHours.subtract(course.cancelledHours());
                  break;
                }
            } // 优惠可能把应收压到 0（甚至因数据问题变负）：不能算出负数单价。
            if (fee.signum() < 0) fee = BigDecimal.ZERO;
            if (feeHours.signum() <= 0) continue;
            var totalConsumed = consumedHours(enrollmentId);
            var courseConsumed =
                consumedHours(
                    enrollmentId, consumedCurriculum); // 封顶：模式 3/5 按（净）课程课时，模式 1/2/4 按（净）总课时。
            var byCourse = mode == 3 || mode == 5;
            var consumed =
                byCourse ? courseConsumed : totalConsumed; // 计价分母与封顶上限分别表达：模式 2 取课程单价，仍按报名净总课时封顶。
            var totalRemaining =
                enrollment.hours().subtract(enrollment.cancelledHours()).subtract(totalConsumed);
            var remaining = capHours.subtract(consumed).min(totalRemaining);
            if (remaining.signum() <= 0) continue; // 拼接/截断规则未确认前明确拒绝跨界，整个关课事务回滚，不能整节超耗。
            if (consumedLessonHours.compareTo(remaining) > 0)
              throw new TeachingConflict("LESSON_HOURS_EXCEED_REMAINING");
            var unit = fee.divide(feeHours, 6, RoundingMode.HALF_UP);
            var amount =
                unit.multiply(consumedLessonHours)
                    .setScale(2, RoundingMode.HALF_UP); // 尾差修正：最后一段课时消耗时补齐到该范围内的应收总额。
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
                    + " values (?,?,?,?,?,?,?,?,?)" // 唯一约束改成了"仅课次消费"的部分索引，冲突推断需要带同样的谓词。
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
  } // ---------------------------------------------------------------- 内部

  @Override
  public BatchLessonOutcome publishLessons(BatchLessonIds command) {
    return eachLesson(command, "TEACHING_MAINTAIN", this::publishLesson);
  }

  @Override
  public BatchLessonOutcome closeLessons(BatchLessonIds command) {
    return eachLesson(command, "LESSON_CLOSE", this::closeLesson);
  }

  @Override
  public BatchLessonOutcome confirmLessons(BatchLessonIds command) {
    return eachLesson(
        command,
        "ATTENDANCE_RECORD",
        (id, version) -> {
          var scope = actors.requireResourceScope("TEACHING");
          var records =
              tx.execute(
                  scope.tenantId(),
                  () -> {
                    var found = new ArrayList<AttendanceInput>();
                    for (var row :
                        sql.fetch(
                            "select enrollment_id,status,attended_minutes from teaching.attendance"
                                + " where lesson_id=? order by enrollment_id",
                            id)) {
                      found.add(
                          new AttendanceInput(
                              row.get("enrollment_id", UUID.class),
                              row.get("status", String.class),
                              row.get("attended_minutes", Integer.class)));
                    }
                    if (!found.isEmpty()) return found; // 还没录考勤时，批量确认按全勤处理；没有在班学员的课次不能确认。
                    for (var row :
                        sql.fetch(
                            "select ec.enrollment_id from teaching.enrollment_class ec"
                                + " join teaching.lesson l on l.class_id=ec.class_id"
                                + " where l.id=? and ec.active order by ec.enrollment_id",
                            id)) {
                      found.add(
                          new AttendanceInput(
                              row.get("enrollment_id", UUID.class), "PRESENT", null));
                    }
                    return found;
                  });
          if (records.isEmpty()) throw new TeachingConflict("ATTENDANCE_NOT_RECORDED");
          return confirmAttendance(id, new ConfirmAttendance(version.expectedVersion(), records));
        });
  }

  /** 批量入口逐个课次走各自的完整校验；失败只跳过该课次，不影响已成功的。 */
  private BatchLessonOutcome eachLesson(
      BatchLessonIds command,
      String permission,
      java.util.function.BiFunction<UUID, LessonVersion, LessonView> operation) {
    if (command == null || command.requestId() == null) throw new IllegalArgumentException("需要请求号");
    var ids = command.lessonIds() == null ? List.<UUID>of() : command.lessonIds();
    if (ids.isEmpty()) throw new IllegalArgumentException("需要至少一个课次");
    if (ids.size() > 200) throw new IllegalArgumentException("一次最多处理 200 个课次");
    var doneIds = new ArrayList<UUID>();
    var skipped = new ArrayList<BatchLessonOutcome.Skipped>();
    for (var id : new LinkedHashSet<>(ids)) {
      try {
        operation.apply(id, new LessonVersion(currentLessonVersion(id, permission)));
        doneIds.add(id);
      } catch (TeachingConflict conflict) {
        skipped.add(new BatchLessonOutcome.Skipped(id, conflict.code()));
      }
    }
    return new BatchLessonOutcome(doneIds.size(), List.copyOf(doneIds), List.copyOf(skipped));
  }

  private long currentLessonVersion(UUID id, String permission) {
    var actor = actors.requireAction(permission);
    return tx.execute(
        actor.tenantId(),
        () -> {
          var row = sql.fetchOne("select version from teaching.lesson where id=?", id);
          if (row == null) throw new TeachingConflict("LESSON_NOT_FOUND");
          return row.get("version", Long.class);
        });
  }

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
    lookups.requireReadable(scope, schoolId);
  }

  private void requireMaintainable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的教务读取权限");
    if (!scope.maintainableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的教务维护权限");
  }

  private Record requireClass(ResourceScope scope, UUID id) {
    return lookups.requireClass(scope, id);
  }

  private Record requireLesson(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select l.id,l.class_id,l.lesson_date,l.starts_at,l.ends_at,l.teacher_name,l.room_name,"
                + "l.topic,l.hours,l.status,l.version,l.teacher_id,l.room_id,"
                + "l.actual_teacher_id,l.actual_room_id,l.actual_teacher_name,l.actual_room_name,l.memo,"
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
                + "l.topic,l.hours,l.status,l.version,l.teacher_id,l.room_id,l.curriculum_id,"
                + "l.actual_teacher_id,l.actual_room_id,l.actual_teacher_name,l.actual_room_name,l.memo,"
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
        row.get("version", Long.class),
        row.get("capacity", Integer.class));
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
   * * 结算模式 0（自动）的解析：VIP 走平均价格(1)，其余（大班/普通）走课程匹配(2)。 * *
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
            var hours = positiveHours(command.hours(), "期初消耗课时"); // 旧库可能只有课时没有金额：允许 0，但不接受负数。
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
    return lookups.lessonView(row);
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
    return lookups.date(value);
  }

  private String time(LocalTime value) {
    return lookups.time(value);
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
    return lookups.consumedHours(enrollmentId);
  }

  private BigDecimal consumedHours(UUID enrollmentId, UUID curriculumId) {
    return lookups.consumedHours(enrollmentId, curriculumId);
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
    return lookups.hours(value);
  }

  private String money(BigDecimal value) {
    return lookups.money(value);
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
