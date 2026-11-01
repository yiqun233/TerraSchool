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
import org.teraschool.archive.api.StudentAccess;
import org.teraschool.enrollment.api.EnrollmentAccess;
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
  private static final Set<String> ATTENDANCE = Set.of("PRESENT", "ABSENT", "LEAVE");

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;
  private final EnrollmentAccess enrollments;
  private final CurriculumAccess curricula;
  private final StudentAccess students;

  public TeachingService(
      CurrentActor actors,
      TenantTransactions tx,
      DSLContext sql,
      SchoolAccess schools,
      EnrollmentAccess enrollments,
      CurriculumAccess curricula,
      StudentAccess students) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
    this.enrollments = enrollments;
    this.curricula = curricula;
    this.students = students;
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
                          new TeachingCapabilities.SchoolOption(
                              key,
                              names.get(key),
                              scope.maintainableSchoolIds().contains(key),
                              attendanceSchools.contains(key),
                              closeSchools.contains(key)))
                  .toList();
          return new TeachingCapabilities(
              true,
              options.stream().anyMatch(TeachingCapabilities.SchoolOption::maintainable),
              options.stream().anyMatch(TeachingCapabilities.SchoolOption::canRecordAttendance),
              options.stream().anyMatch(TeachingCapabilities.SchoolOption::canCloseLesson),
              options);
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
                  "select c.id,c.school_id,c.curriculum_id,c.name,c.status,c.version,"
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
                      + "l.room_name,l.topic,l.hours,l.status,l.version,"
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
                  "select enrollment_id,status from teaching.attendance where lesson_id=?"
                      + " order by enrollment_id",
                  lessonId)
              .map(
                  row ->
                      new AttendanceInput(
                          row.get("enrollment_id", UUID.class), row.get("status", String.class)));
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
                          + " from teaching.consumption c join teaching.lesson l on l.id=c.lesson_id"
                          + " where c.enrollment_id=? order by l.lesson_date, c.id",
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

  private ClassView createClassWithTrace(CreateClass command) {
    if (command == null || command.requestId() == null || command.schoolId() == null)
      throw new IllegalArgumentException("需要请求身份和学校");
    var name = text(command.name(), 100, "班级名称");
    if (name == null) throw new IllegalArgumentException("班级名称必填");
    var scope = actors.requireResourceScope("TEACHING");
    requireMaintainable(scope, command.schoolId());
    var fingerprint = fingerprint("CLASS", name, String.valueOf(command.curriculumId()));
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
                "insert into teaching.class(id,tenant_id,school_id,curriculum_id,name) values (?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                command.curriculumId(),
                name);
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
          if (!schools.require(schoolId).active())
            throw new AccessDeniedException("学校已停用，不能调整班级成员");
          if (!"ACTIVE".equals(row.get("status", String.class)))
            throw new TeachingConflict("CLASS_NOT_ACTIVE");
          var enrollment = enrollments.require(command.enrollmentId());
          if (!enrollment.schoolId().equals(schoolId)) throw new AccessDeniedException("报名不属于该学校");
          if (!"ACTIVE".equals(enrollment.status()))
            throw new TeachingConflict("ENROLLMENT_NOT_ACTIVE");
          var current =
              sql.fetchOne(
                  "select class_id from teaching.enrollment_class where enrollment_id=? and active",
                  command.enrollmentId());
          if (current != null) {
            if (current.get("class_id", UUID.class).equals(classId))
              return classView(requireClass(verified, classId), schools.require(schoolId).name());
            throw new TeachingConflict("ENROLLMENT_ALREADY_IN_CLASS");
          }
          sql.execute(
              "insert into teaching.enrollment_class(tenant_id,enrollment_id,class_id,joined_on)"
                  + " values (?,?,?,CURRENT_DATE)",
              scope.tenantId(),
              command.enrollmentId(),
              classId);
          audit(scope, "CLASS_MEMBER_ADDED", classId, schoolId, "members");
          return classView(requireClass(verified, classId), schools.require(schoolId).name());
        });
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
          var id = UUID.randomUUID();
          sql.execute(
              "insert into teaching.lesson(id,tenant_id,class_id,lesson_date,starts_at,ends_at,"
                  + "teacher_name,room_name,topic,hours) values (?,?,?,?,?,?,?,?,?,?)",
              id,
              scope.tenantId(),
              command.classId(),
              date,
              startsAt,
              endsAt,
              text(command.teacherName(), 50, "教师"),
              text(command.roomName(), 50, "教室"),
              text(command.topic(), 100, "课题"),
              lessonHours);
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
          var provided = new HashMap<UUID, String>();
          for (var input : command.records()) {
            if (input == null
                || input.enrollmentId() == null
                || !ATTENDANCE.contains(input.status()))
              throw new IllegalArgumentException("考勤状态无效");
            if (provided.put(input.enrollmentId(), input.status()) != null)
              throw new IllegalArgumentException("同一次考勤出现重复报名");
          }
          if (!provided.keySet().equals(new HashSet<>(members)))
            throw new TeachingConflict("ATTENDANCE_MUST_COVER_MEMBERS");
          for (var entry : provided.entrySet())
            sql.execute(
                "insert into teaching.attendance(tenant_id,lesson_id,enrollment_id,status)"
                    + " values (?,?,?,?) on conflict (lesson_id,enrollment_id)"
                    + " do update set status=excluded.status,recorded_at=CURRENT_TIMESTAMP",
                record.tenantId(),
                id,
                entry.getKey(),
                entry.getValue());
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
          if (!"CONFIRMED".equals(row.get("status", String.class)))
            throw new TeachingConflict("LESSON_NOT_CONFIRMED");
          if (!row.get("version", Long.class).equals(command.expectedVersion()))
            throw new TeachingConflict("VERSION_CONFLICT");
          var lessonHours = row.get("hours", BigDecimal.class);
          var lessonDate = row.get("lesson_date", LocalDate.class);
          var present =
              sql.fetch(
                  "select a.enrollment_id from teaching.attendance a where a.lesson_id=? and a.status='PRESENT'",
                  id);
          for (var item : present) {
            var enrollmentId = item.get("enrollment_id", UUID.class);
            var enrollment = enrollments.require(enrollmentId);
            // 模式 1 平均价格：单价 = 报名价格 ÷ 购买课时；本次消费 = 单价 × 本次授课课时。
            var unit = enrollment.price().divide(enrollment.hours(), 6, RoundingMode.HALF_UP);
            var amount = unit.multiply(lessonHours).setScale(2, RoundingMode.HALF_UP);
            sql.execute(
                "insert into teaching.consumption(tenant_id,enrollment_id,lesson_id,hours,amount,"
                    + "settlement_mode,price_snapshot,hours_snapshot) values (?,?,?,?,?,?,?,?)"
                    + " on conflict (enrollment_id,lesson_id) do nothing",
                close.tenantId(),
                enrollmentId,
                id,
                lessonHours,
                amount,
                enrollment.settlementMode(),
                enrollment.price(),
                enrollment.hours());
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
            "select c.id,c.school_id,c.curriculum_id,c.name,c.status,c.version,"
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
                + "l.topic,l.hours,l.status,l.version,"
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
                + "l.topic,l.hours,l.status,l.version,"
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
        row.get("member_count", Integer.class),
        row.get("version", Long.class));
  }

  private LessonView lessonView(Record row) {
    return new LessonView(
        row.get("id", UUID.class),
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
        row.get("attendance_count", Integer.class));
  }

  private String text(String value, int max, String label) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(label + "超出长度或含控制字符");
    return text;
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
