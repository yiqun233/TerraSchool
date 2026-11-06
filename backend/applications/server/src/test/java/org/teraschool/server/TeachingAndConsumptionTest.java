package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.*;
import tools.jackson.databind.json.JsonMapper;

/** 排课、考勤与课次消费：状态机、考勤覆盖、消费固化与对账、并发与第二入口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeachingAndConsumptionTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      SECOND_CURRICULUM = UUID.randomUUID(),
      OTHER_CURRICULUM = UUID.randomUUID(),
      PRODUCT = UUID.randomUUID(),
      PRICE = UUID.randomUUID(),
      TEACHER_EMPLOYEE = UUID.randomUUID(),
      TEACHER_RESOURCE = UUID.randomUUID(),
      TEACHER = UUID.randomUUID(),
      ASSISTANT = UUID.randomUUID(),
      READER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired TeachingCommands commands;
  @Autowired TeachingQueries queries;
  @Autowired org.jooq.DSLContext sql;
  @Autowired TenantTransactions tx;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update(
        "insert into identity.tenant(id,name) values (?,?),(?,?)",
        TENANT,
        "教务机构甲",
        OTHER_TENANT,
        "教务机构乙");
    for (var id : new UUID[] {SCHOOL, OTHER_SCHOOL})
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
          id,
          TENANT,
          id,
          id.equals(SCHOOL) ? "教务学校甲" : "教务学校乙");
    for (var pair :
        new Object[][] {
          {CURRICULUM, SCHOOL},
          {SECOND_CURRICULUM, SCHOOL},
          {OTHER_CURRICULUM, OTHER_SCHOOL}
        })
      update(
          "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,?)",
          pair[0],
          TENANT,
          pair[1],
          "教务课程" + ((UUID) pair[0]).toString().substring(0, 4));
    update(
        "insert into product.product(id,tenant_id,school_id,curriculum_id,name,settlement_mode)"
            + " values (?,?,?,?,'教务产品',1)",
        PRODUCT,
        TENANT,
        SCHOOL,
        CURRICULUM);
    update(
        "insert into product.price_version(id,tenant_id,product_id,version_no,status,price,hours,"
            + " service_fee,settlement_mode,effective_from)"
            + " values (?,?,?,1,'PUBLISHED',1000.00,10.00,0,1,'2026-01-01')",
        PRICE,
        TENANT,
        PRODUCT);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry :
        Map.of(
                TEACHER,
                "teaching-teacher",
                ASSISTANT,
                "teaching-assistant",
                READER,
                "teaching-reader")
            .entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    update(
        "insert into organization.employee(id,tenant_id,school_id,name,account_id) values (?,?,?,'授课教师',?)",
        TEACHER_EMPLOYEE,
        TENANT,
        SCHOOL,
        TEACHER);
    update(
        "insert into organization.teacher(id,tenant_id,school_id,employee_id,name) values (?,?,?,?,?)",
        TEACHER_RESOURCE,
        TENANT,
        SCHOOL,
        TEACHER_EMPLOYEE,
        "授课教师");
    for (var action :
        new String[] {"TEACHING_READ", "TEACHING_MAINTAIN", "ATTENDANCE_RECORD", "LESSON_CLOSE"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          TEACHER,
          SCHOOL,
          action);
    for (var action : new String[] {"TEACHING_READ", "ATTENDANCE_RECORD"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          ASSISTANT,
          SCHOOL,
          action);
    update(
        "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
            + " values (?,?,?,'TEACHING_READ','ALLOW')",
        TENANT,
        READER,
        SCHOOL);
  }

  <T> T asActor(UUID account, Supplier<T> work) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            EngineeringTest.principal(account), null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  /** 直接构造一条已生效报名：本类只测教务，报名本身由 EnrollmentAndReceiptTest 覆盖。 */
  UUID activeEnrollment() {
    var id = UUID.randomUUID();
    var student = UUID.randomUUID();
    try {
      update(
          "insert into archive.student(id,tenant_id,student_no,name,sex,school_id) values (?,?,?,?,?,?)",
          student,
          TENANT,
          "S8" + id.toString().replace("-", "").substring(0, 6),
          "教务学员" + id.toString().substring(0, 4),
          "FEMALE",
          SCHOOL);
      update(
          "insert into enrollment.enrollment(id,tenant_id,school_id,student_id,product_id,"
              + "price_version_id,curriculum_name,product_name,price,hours,service_fee,settlement_mode,status)"
              + " values (?,?,?,?,?,?,'教务课程','教务产品',1000.00,10.00,0,1,'ACTIVE')",
          id,
          TENANT,
          SCHOOL,
          student,
          PRODUCT,
          PRICE);
      update(
          "insert into enrollment.charge(tenant_id,enrollment_id,kind,amount) values (?,?,'ORIGINAL',1000.00)",
          TENANT,
          id);
      update(
          "insert into enrollment.enrollment_course(tenant_id,enrollment_id,curriculum_id,hours,amount)"
              + " values (?,?,?,10.00,1000.00)",
          TENANT,
          id,
          CURRICULUM);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    return id;
  }

  ClassView newClass(String name) {
    return newClass(name, CURRICULUM);
  }

  ClassView newClass(String name, UUID curriculumId) {
    return newClass(name, curriculumId, "NORMAL");
  }

  ClassView newClass(String name, UUID curriculumId, String classType) {
    return asActor(
        TEACHER,
        () ->
            commands.createClass(
                new CreateClass(UUID.randomUUID(), SCHOOL, curriculumId, name, classType)));
  }

  ClassView classWithMember(String name) {
    var created = newClass(name);
    var enrollment = activeEnrollment();
    return asActor(TEACHER, () -> commands.addMember(created.id(), new AddClassMember(enrollment)));
  }

  LessonView newLesson(UUID classId, String date) {
    return asActor(
        TEACHER,
        () ->
            commands.createLesson(
                new CreateLesson(
                    UUID.randomUUID(),
                    classId,
                    date,
                    "09:00",
                    "10:00",
                    "教师甲",
                    "教室甲",
                    "第一课",
                    "1.00",
                    TEACHER_RESOURCE,
                    null)));
  }

  @Test
  void publishedLessonCanBeRescheduledBeforeFactsAndKeepsVersionedHistory() throws Exception {
    var sourceClass = newClass("待调课班级");
    var occupiedClass = newClass("目标时段班级");
    var draft = newLesson(sourceClass.id(), "2026-11-12");
    var lesson = asActor(TEACHER, () -> commands.publishLesson(draft.id(), new LessonVersion(0L)));
    var occupied = newLesson(occupiedClass.id(), "2026-11-20");
    asActor(TEACHER, () -> commands.publishLesson(occupied.id(), new LessonVersion(0L)));

    var conflict =
        new RescheduleLesson(
            UUID.randomUUID(), lesson.version(), "2026-11-20", "09:00", "10:00", "校区临时安排");
    assertThatThrownBy(
            () -> asActor(TEACHER, () -> commands.rescheduleLesson(lesson.id(), conflict)))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("TEACHER_CLASH");
    assertThat(asActor(TEACHER, () -> queries.lessons(sourceClass.id())).get(0).lessonDate())
        .isEqualTo("2026-11-12");

    var requestId = UUID.randomUUID();
    var request =
        new RescheduleLesson(requestId, lesson.version(), "2026-11-20", "11:00", "12:00", "校区临时安排");
    var moved = asActor(TEACHER, () -> commands.rescheduleLesson(lesson.id(), request));
    assertThat(moved.lessonDate()).isEqualTo("2026-11-20");
    assertThat(moved.startsAt()).isEqualTo("11:00");
    assertThat(moved.status()).isEqualTo("PUBLISHED");
    assertThat(asActor(TEACHER, () -> commands.rescheduleLesson(lesson.id(), request)))
        .isEqualTo(moved);
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.rescheduleLesson(
                            lesson.id(),
                            new RescheduleLesson(
                                requestId,
                                lesson.version(),
                                "2026-11-21",
                                "11:00",
                                "12:00",
                                "改动参数复用身份"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.lesson_reschedule where tenant_id=? and lesson_id=?",
                            TENANT,
                            lesson.id())
                        .get(0, Long.class)))
        .isEqualTo(1L);
  }

  @Test
  void swappingPublishedLessonTimesIsAtomicAndIdempotent() {
    var classView = newClass("同班换课");
    var first = newLesson(classView.id(), "2026-11-17");
    var second = newLesson(classView.id(), "2026-11-24");
    var publishedFirst =
        asActor(TEACHER, () -> commands.publishLesson(first.id(), new LessonVersion(0L)));
    var publishedSecond =
        asActor(TEACHER, () -> commands.publishLesson(second.id(), new LessonVersion(0L)));
    var command =
        new SwapLessons(
            UUID.randomUUID(),
            second.id(),
            publishedFirst.version(),
            publishedSecond.version(),
            "教師調整課表");

    var swapped = asActor(TEACHER, () -> commands.swapLessons(first.id(), command));
    var other =
        asActor(TEACHER, () -> queries.lessons(classView.id())).stream()
            .filter(item -> item.id().equals(second.id()))
            .findFirst()
            .orElseThrow();
    assertThat(swapped.lessonDate()).isEqualTo("2026-11-24");
    assertThat(other.lessonDate()).isEqualTo("2026-11-17");
    assertThat(swapped.status()).isEqualTo("PUBLISHED");
    assertThat(asActor(TEACHER, () -> commands.swapLessons(first.id(), command)))
        .isEqualTo(swapped);
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.lesson_reschedule where tenant_id=? and request_id=?",
                            TENANT,
                            command.requestId())
                        .get(0, Long.class)))
        .isEqualTo(2L);
  }

  @Test
  void makeupForClosedAbsenceCreatesLinkedDraftAndReplaysSafely() {
    var classView = classWithMember("缺勤后补课");
    var member = membershipOf(classView.id());
    var otherEnrollment = activeEnrollment();
    asActor(TEACHER, () -> commands.addMember(classView.id(), new AddClassMember(otherEnrollment)));
    var source = published(classView.id(), "2026-03-05");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    source.id(),
                    new ConfirmAttendance(
                        source.version(),
                        List.of(
                            new AttendanceInput(member, "ABSENT"),
                            new AttendanceInput(otherEnrollment, "PRESENT")))));
    var closed =
        asActor(
            TEACHER,
            () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
    var command =
        new CreateMakeupLesson(UUID.randomUUID(), "2026-03-12", "11:00", "12:00", "学员病假补课");

    var makeup = asActor(TEACHER, () -> commands.createMakeupLesson(closed.id(), command));
    assertThat(makeup.status()).isEqualTo("DRAFT");
    assertThat(makeup.lessonDate()).isEqualTo("2026-03-12");
    assertThat(makeup.hours()).isEqualTo(closed.hours());
    assertThat(makeup.makeupSourceLessonId()).isEqualTo(closed.id());
    assertThat(makeup.makeupEnrollmentIds()).containsExactly(member);
    assertThat(asActor(TEACHER, () -> commands.createMakeupLesson(closed.id(), command)))
        .isEqualTo(makeup);
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.createMakeupLesson(
                            closed.id(),
                            new CreateMakeupLesson(
                                command.requestId(), "2026-03-13", "11:00", "12:00", "改参数重放"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
    var publishedMakeup =
        asActor(
            TEACHER,
            () -> commands.publishLesson(makeup.id(), new LessonVersion(makeup.version())));
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.confirmAttendance(
                            publishedMakeup.id(),
                            new ConfirmAttendance(
                                publishedMakeup.version(),
                                List.of(
                                    new AttendanceInput(member, "PRESENT"),
                                    new AttendanceInput(otherEnrollment, "PRESENT"))))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("MAKEUP_ATTENDANCE_NOT_ELIGIBLE");
    var makeupAttendance =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    publishedMakeup.id(),
                    new ConfirmAttendance(
                        publishedMakeup.version(),
                        List.of(
                            new AttendanceInput(member, "PRESENT"),
                            new AttendanceInput(otherEnrollment, "ABSENT")))));
    asActor(
        TEACHER,
        () ->
            commands.closeLesson(
                makeupAttendance.id(), new LessonVersion(makeupAttendance.version())));
    assertThat(asActor(READER, () -> queries.reconciliation(member)).consumedHours())
        .isEqualTo("1.00");
    assertThat(asActor(READER, () -> queries.reconciliation(otherEnrollment)).consumedHours())
        .isEqualTo("1.00");
    assertThat(
            tx.execute(
                TENANT,
                () ->
                    sql.fetchOne(
                            "select count(*) from teaching.lesson_makeup where tenant_id=? and source_lesson_id=?",
                            TENANT,
                            closed.id())
                        .get(0, Long.class)))
        .isEqualTo(1L);
  }

  @Test
  void teacherWorkbenchShowsOnlyAccountBoundLessonsInsideDateRange() {
    var classView = newClass("本人课表班级");
    var inRange = newLesson(classView.id(), "2026-11-12");
    newLesson(classView.id(), "2026-12-15");
    var workbench = asActor(TEACHER, () -> queries.myWorkbench("2026-11-12", "2026-11-12"));
    assertThat(workbench.lessons())
        .extracting(TeachingQueries.TeacherLessonView::id)
        .containsExactly(inRange.id());
    assertThat(workbench.classes())
        .extracting(TeachingQueries.TeacherClassView::id)
        .containsExactly(classView.id());
    assertThat(asActor(READER, () -> queries.myWorkbench("2026-11-12", "2026-11-12")).lessons())
        .isEmpty();
    assertThatThrownBy(
            () -> asActor(TEACHER, () -> queries.myWorkbench("2026-11-30", "2026-11-01")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void teacherClassWorkbenchReturnsRosterAndOnlyOwnLessons() {
    var classView = classWithMember("教师班级工作台");
    var lesson = newLesson(classView.id(), "2026-11-14");

    var workbench = asActor(TEACHER, () -> queries.myClass(classView.id()));

    assertThat(workbench.classInfo().id()).isEqualTo(classView.id());
    assertThat(workbench.members())
        .singleElement()
        .satisfies(member -> assertThat(member.status()).isEqualTo("ACTIVE"));
    assertThat(workbench.lessons())
        .extracting(TeachingQueries.TeacherLessonView::id)
        .containsExactly(lesson.id());
    assertThatThrownBy(() -> asActor(READER, () -> queries.myClass(classView.id())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void dailyResourceScheduleIsScopedAndIncludesMissingResourceCounts() {
    var classView = newClass("资源日报班级");
    var lesson = newLesson(classView.id(), "2026-11-13");
    var report = asActor(TEACHER, () -> queries.dailyResourceSchedule(SCHOOL, "2026-11-13", 0, 10));
    assertThat(report.day()).isEqualTo("2026-11-13");
    assertThat(report.total()).isEqualTo(1);
    assertThat(report.pageSize()).isEqualTo(10);
    assertThat(report.lessons())
        .extracting(TeachingQueries.ResourceScheduleSlot::lessonId)
        .contains(lesson.id());
    assertThat(report.missingRoom()).isGreaterThanOrEqualTo(1);
    assertThatThrownBy(
            () -> asActor(TEACHER, () -> queries.dailyResourceSchedule(SCHOOL, "invalid", 0, 10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void classCloseRequiresNoOpenLessonsAndEndsActiveMemberships() {
    var classView = classWithMember("结班状态机");
    var openLesson = newLesson(classView.id(), "2026-11-20");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.closeClass(
                            classView.id(), new LessonVersion(classView.version()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("CLASS_HAS_OPEN_LESSONS");
    var closed =
        asActor(
            TEACHER,
            () -> {
              var lesson =
                  commands.publishLesson(openLesson.id(), new LessonVersion(openLesson.version()));
              var confirmed =
                  commands.confirmAttendance(
                      lesson.id(),
                      new ConfirmAttendance(
                          lesson.version(),
                          List.of(new AttendanceInput(membershipOf(classView.id()), "PRESENT"))));
              commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version()));
              return commands.closeClass(classView.id(), new LessonVersion(classView.version()));
            });
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.version()).isEqualTo(classView.version() + 1);
    assertThat(asActor(TEACHER, () -> queries.members(classView.id())))
        .extracting(ClassMemberView::status)
        .containsExactly("LEFT");
  }

  @Test
  void emptyClassCancellationIsAuditedAndIdempotent() throws Exception {
    var classView = newClass("空班取消");
    var requestId = UUID.randomUUID();
    var command = new CancelClass(requestId, classView.version(), "招生计划调整");

    var cancelled = asActor(TEACHER, () -> commands.cancelClass(classView.id(), command));
    var retried = asActor(TEACHER, () -> commands.cancelClass(classView.id(), command));

    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.version()).isEqualTo(classView.version() + 1);
    assertThat(retried).isEqualTo(cancelled);
    assertThat(countAudit("audit.teaching_event", classView.id())).isEqualTo(2);
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.cancelClass(
                            classView.id(),
                            new CancelClass(requestId, classView.version(), "改用其他安排"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void classCancellationRejectsLessonsAndActiveMembers() {
    var withMember = classWithMember("有学员班不可取消");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.cancelClass(
                            withMember.id(),
                            new CancelClass(UUID.randomUUID(), withMember.version(), "测试"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("CLASS_HAS_ACTIVE_MEMBERS");

    var withLesson = newClass("有课次班不可取消");
    newLesson(withLesson.id(), "2026-11-21");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.cancelClass(
                            withLesson.id(),
                            new CancelClass(UUID.randomUUID(), withLesson.version(), "测试"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("CLASS_HAS_LESSONS");
  }

  LessonView published(UUID classId, String date) {
    var draft = newLesson(classId, date);
    return asActor(
        TEACHER, () -> commands.publishLesson(draft.id(), new LessonVersion(draft.version())));
  }

  LessonView confirmedEnrollmentLesson(String date) {
    var classView = classWithMember("班级" + date);
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), date);
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
    return asActor(
        TEACHER,
        () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
  }

  long countAudit(String table, UUID resource) throws Exception {
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement("select count(*) from " + table + " where resource_id=?")) {
      s.setObject(1, resource);
      var r = s.executeQuery();
      r.next();
      return r.getLong(1);
    }
  }

  UUID membershipOf(UUID classId) {
    try (var c = EngineeringTest.admin();
        var s =
            c.prepareStatement(
                "select enrollment_id from teaching.enrollment_class where class_id=?")) {
      s.setObject(1, classId);
      var r = s.executeQuery();
      r.next();
      return r.getObject(1, UUID.class);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @Test
  void classCreationValidatesNameAndCurriculum() throws Exception {
    var input = new CreateClass(UUID.randomUUID(), SCHOOL, CURRICULUM, "少儿英语A班");
    var first = asActor(TEACHER, () -> commands.createClass(input));
    assertThat(first.memberCount()).isZero();
    assertThat(asActor(TEACHER, () -> commands.createClass(input)).id()).isEqualTo(first.id());
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.createClass(
                            new CreateClass(UUID.randomUUID(), SCHOOL, CURRICULUM, "少儿英语A班"))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("DUPLICATE_CLASS_NAME");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.createClass(
                            new CreateClass(UUID.randomUUID(), SCHOOL, OTHER_CURRICULUM, "跨校班"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThat(countAudit("audit.teaching_event", first.id())).isEqualTo(1);
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.createClass(
                            new CreateClass(
                                input.requestId(),
                                SCHOOL,
                                CURRICULUM,
                                input.name(),
                                "NORMAL",
                                false,
                                1))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("IDEMPOTENCY_MISMATCH");
  }

  @Test
  void teachingOverviewCountsCurrentClassesAndLessonDateFactsWithinSchoolScope() {
    String from = "2026-11-01";
    String to = "2026-11-30";
    var before = asActor(READER, () -> queries.teachingOverviewReport(SCHOOL, from, to));
    var createdClass = newClass("综合汇总班" + UUID.randomUUID().toString().substring(0, 6));
    newLesson(createdClass.id(), "2026-11-20");

    var report = asActor(READER, () -> queries.teachingOverviewReport(SCHOOL, from, to));
    assertThat(report.from()).isEqualTo(from);
    assertThat(report.to()).isEqualTo(to);
    assertThat(report.activeClasses()).isEqualTo(before.activeClasses() + 1);
    assertThat(report.lessons()).isEqualTo(before.lessons() + 1);
    assertThat(report.draftLessons()).isEqualTo(before.draftLessons() + 1);
    assertThat(report.recorded()).isEqualTo(0);
    assertThatThrownBy(
            () -> asActor(READER, () -> queries.teachingOverviewReport(OTHER_SCHOOL, from, to)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(
            () -> asActor(READER, () -> queries.teachingOverviewReport(SCHOOL, from, "2026-12-02")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("31 天");
  }

  @Test
  void memberIsIdempotentPerClassAndAllowedAcrossClasses() throws Exception {
    var classView = newClass("成员班");
    var other = newClass("另一个班");
    var enrollment = activeEnrollment();
    var added =
        asActor(TEACHER, () -> commands.addMember(classView.id(), new AddClassMember(enrollment)));
    assertThat(added.memberCount()).isEqualTo(1);
    // 重复加入同一班级保持幂等。
    assertThat(
            asActor(
                    TEACHER,
                    () -> commands.addMember(classView.id(), new AddClassMember(enrollment)))
                .memberCount())
        .isEqualTo(1);
    // 一条报名可以同时上多门课程（结算模式 2/3 的前提），因此允许加入不同班级。
    assertThat(
            asActor(TEACHER, () -> commands.addMember(other.id(), new AddClassMember(enrollment)))
                .memberCount())
        .isEqualTo(1);
  }

  @Test
  void classCapacityBlocksNewMembersButKeepsExistingMembershipRetryIdempotent() {
    var limited =
        asActor(
            TEACHER,
            () ->
                commands.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "限额班", "NORMAL", false, 1)));
    assertThat(limited.capacity()).isEqualTo(1);
    var first = activeEnrollment();
    assertThat(
            asActor(TEACHER, () -> commands.addMember(limited.id(), new AddClassMember(first)))
                .memberCount())
        .isEqualTo(1);
    // 已在班的相同请求即使班级已满仍返回原事实。
    assertThat(
            asActor(TEACHER, () -> commands.addMember(limited.id(), new AddClassMember(first)))
                .memberCount())
        .isEqualTo(1);
    var second = activeEnrollment();
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER, () -> commands.addMember(limited.id(), new AddClassMember(second))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("CLASS_CAPACITY_REACHED");
    assertThat(asActor(TEACHER, () -> queries.members(limited.id())))
        .extracting(ClassMemberView::enrollmentId)
        .containsExactly(first);
  }

  @Test
  void multiClassCapacityFailureRollsBackEarlierClassInsertions() {
    var available =
        asActor(
            TEACHER,
            () ->
                commands.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "批次可用班", "NORMAL", false, 1)));
    var full =
        asActor(
            TEACHER,
            () ->
                commands.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "批次满额班", "NORMAL", false, 0)));
    var targetStudent = activeEnrollment();
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.addMembers(
                            new AddClassMembers(
                                targetStudent, List.of(available.id(), full.id())))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("CLASS_CAPACITY_REACHED");
    assertThat(asActor(TEACHER, () -> queries.members(available.id()))).isEmpty();
    assertThat(asActor(TEACHER, () -> queries.members(full.id()))).isEmpty();
  }

  @Test
  void multiClassEnrollmentIsAtomicCourseMatchedAndIdempotent() throws Exception {
    var first = newClass("批量入班甲");
    var second = newClass("批量入班乙");
    var enrollment = activeEnrollment();
    var firstAuditCount = countAudit("audit.teaching_event", first.id());
    var secondAuditCount = countAudit("audit.teaching_event", second.id());
    var command = new AddClassMembers(enrollment, List.of(first.id(), second.id()));

    assertThat(asActor(TEACHER, () -> commands.addMembers(command)))
        .extracting(ClassView::id)
        .containsExactly(first.id(), second.id());
    assertThat(asActor(TEACHER, () -> queries.members(first.id())))
        .extracting(ClassMemberView::enrollmentId)
        .containsExactly(enrollment);
    assertThat(asActor(TEACHER, () -> commands.addMembers(command)))
        .extracting(ClassView::id)
        .containsExactly(first.id(), second.id());
    assertThat(countAudit("audit.teaching_event", first.id())).isEqualTo(firstAuditCount + 1);
    assertThat(countAudit("audit.teaching_event", second.id())).isEqualTo(secondAuditCount + 1);

    var mismatch = newClass("不匹配课程班", SECOND_CURRICULUM);
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.addMembers(
                            new AddClassMembers(enrollment, List.of(first.id(), mismatch.id())))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("ENROLLMENT_COURSE_MISMATCH");
    assertThat(asActor(TEACHER, () -> queries.members(mismatch.id()))).isEmpty();
  }

  @Test
  void autoSettlementBatchRejectsClassesThatResolveToDifferentModes() throws Exception {
    var vip = newClass("自动模式 VIP 班", CURRICULUM, "VIP");
    var normal = newClass("自动模式普通班", CURRICULUM, "NORMAL");
    var enrollment = activeEnrollment();
    update("update enrollment.enrollment set settlement_mode=0 where id=?", enrollment);

    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.addMembers(
                            new AddClassMembers(enrollment, List.of(vip.id(), normal.id())))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("AUTO_SETTLEMENT_MODE_CLASS_TYPE_MISMATCH");
    assertThat(asActor(TEACHER, () -> queries.members(vip.id()))).isEmpty();
    assertThat(asActor(TEACHER, () -> queries.members(normal.id()))).isEmpty();
  }

  @Test
  void lessonTransitionsAreOrdered() {
    var classView = newClass("状态班");
    var member = activeEnrollment();
    asActor(TEACHER, () -> commands.addMember(classView.id(), new AddClassMember(member)));
    var draft = newLesson(classView.id(), "2026-03-02");
    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThat(draft.hours()).isEqualTo("1.00");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () -> commands.closeLesson(draft.id(), new LessonVersion(draft.version()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("LESSON_NOT_CONFIRMED");
    var published =
        asActor(TEACHER, () -> commands.publishLesson(draft.id(), new LessonVersion(0L)));
    assertThat(published.status()).isEqualTo("PUBLISHED");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.publishLesson(
                            published.id(), new LessonVersion(published.version()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("LESSON_NOT_DRAFT");
  }

  @Test
  void attendanceMustCoverEveryActiveMember() {
    var classView = classWithMember("考勤班");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-03");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.confirmAttendance(
                            lesson.id(),
                            new ConfirmAttendance(
                                lesson.version(),
                                List.of(new AttendanceInput(UUID.randomUUID(), "PRESENT"))))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("ATTENDANCE_MUST_COVER_MEMBERS");
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.confirmAttendance(
                            lesson.id(),
                            new ConfirmAttendance(
                                lesson.version(),
                                List.of(
                                    new AttendanceInput(member, "PRESENT"),
                                    new AttendanceInput(member, "ABSENT"))))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.confirmAttendance(
                            lesson.id(),
                            new ConfirmAttendance(
                                lesson.version(),
                                List.of(new AttendanceInput(member, "UNKNOWN"))))))
        .isInstanceOf(IllegalArgumentException.class);
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(confirmed.attendanceCount()).isEqualTo(1);
    assertThat(asActor(READER, () -> queries.attendance(confirmed.id())))
        .extracting(AttendanceInput::status)
        .containsExactly("PRESENT");
  }

  @Test
  void closingPostsConsumptionAtFrozenUnitPrice() throws Exception {
    var classView = classWithMember("消费班");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-04");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
    var closed =
        asActor(
            TEACHER,
            () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
    assertThat(closed.status()).isEqualTo("CLOSED");
    var reconciliation = asActor(READER, () -> queries.reconciliation(member));
    assertThat(reconciliation.price()).isEqualTo("1000.00");
    assertThat(reconciliation.hours()).isEqualTo("10.00");
    assertThat(reconciliation.consumedHours()).isEqualTo("1.00");
    assertThat(reconciliation.consumedAmount()).isEqualTo("100.00");
    assertThat(reconciliation.remainingHours()).isEqualTo("9.00");
    assertThat(reconciliation.consumptions()).hasSize(1);
    assertThat(reconciliation.consumptions().getFirst().priceSnapshot()).isEqualTo("1000.00");
    // 同一课次留下创建、发布、考勤确认与关闭四条审计，可完整还原生命周期。
    assertThat(countAudit("audit.teaching_event", closed.id())).isEqualTo(4);
  }

  @Test
  void absenceDoesNotConsumeHours() {
    var classView = classWithMember("缺勤班");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-05");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "ABSENT")))));
    asActor(
        TEACHER,
        () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
    var reconciliation = asActor(READER, () -> queries.reconciliation(member));
    assertThat(reconciliation.consumedHours()).isEqualTo("0.00");
    assertThat(reconciliation.remainingHours()).isEqualTo("10.00");
  }

  @Test
  void partialAttendanceConsumesProportionalHoursAndAmount() {
    var classView = classWithMember("部分缺勤按分钟折算");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-05");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PARTIAL", 30)))));
    asActor(
        TEACHER,
        () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));

    var reconciliation = asActor(READER, () -> queries.reconciliation(member));
    assertThat(reconciliation.consumedHours()).isEqualTo("0.50");
    assertThat(reconciliation.consumedAmount()).isEqualTo("50.00");
    assertThat(reconciliation.remainingHours()).isEqualTo("9.50");
    assertThat(reconciliation.consumptions())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.hours()).isEqualTo("0.50");
              assertThat(item.amount()).isEqualTo("50.00");
            });
  }

  @Test
  void enrollmentExpiredOnLessonDateCannotPostConsumption() throws Exception {
    var classView = newClass("有效期后课次拒绝消费");
    var enrollment = activeEnrollment();
    update("update enrollment.enrollment set valid_until='2026-03-04' where id=?", enrollment);
    asActor(TEACHER, () -> commands.addMember(classView.id(), new AddClassMember(enrollment)));
    var lesson = published(classView.id(), "2026-03-05");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(enrollment, "PRESENT")))));

    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.closeLesson(
                            confirmed.id(), new LessonVersion(confirmed.version()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("ENROLLMENT_TERM_EXPIRED");
    assertThat(asActor(READER, () -> queries.reconciliation(enrollment)).consumedHours())
        .isEqualTo("0.00");
    assertThat(asActor(TEACHER, () -> queries.lessons(classView.id())))
        .filteredOn(item -> item.id().equals(confirmed.id()))
        .singleElement()
        .extracting(LessonView::status)
        .isEqualTo("CONFIRMED");
  }

  @Test
  void partialAttendanceRequiresPositiveMinutesBelowLessonDuration() {
    var classView = classWithMember("部分缺勤分钟校验");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-07");
    for (var invalidMinutes : new Integer[] {null, 0, 60}) {
      assertThatThrownBy(
              () ->
                  asActor(
                      TEACHER,
                      () ->
                          commands.confirmAttendance(
                              lesson.id(),
                              new ConfirmAttendance(
                                  lesson.version(),
                                  List.of(
                                      new AttendanceInput(member, "PARTIAL", invalidMinutes))))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("部分缺勤分钟必须大于 0 且少于课次时长");
    }
  }

  @Test
  void unbillablyShortPartialAttendanceDoesNotConfirmLesson() {
    var classView = classWithMember("计费精度下不可确认");
    var member = membershipOf(classView.id());
    var draft =
        asActor(
            TEACHER,
            () ->
                commands.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classView.id(),
                        "2026-03-08",
                        "09:00",
                        "19:00",
                        "教师甲",
                        "教室甲",
                        "长课次",
                        "0.01",
                        TEACHER_RESOURCE,
                        null)));
    var lesson =
        asActor(
            TEACHER, () -> commands.publishLesson(draft.id(), new LessonVersion(draft.version())));

    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () ->
                        commands.confirmAttendance(
                            lesson.id(),
                            new ConfirmAttendance(
                                lesson.version(),
                                List.of(new AttendanceInput(member, "PARTIAL", 1))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("部分缺勤时间过短，按当前课时精度无法计费");
    assertThat(asActor(TEACHER, () -> queries.lessons(classView.id())))
        .filteredOn(item -> item.id().equals(lesson.id()))
        .singleElement()
        .extracting(LessonView::status)
        .isEqualTo("PUBLISHED");
  }

  @Test
  void closingTwiceKeepsSingleConsumption() throws Exception {
    var classView = classWithMember("重复关闭班");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-06");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
    var closed =
        asActor(
            TEACHER,
            () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
    assertThatThrownBy(
            () ->
                asActor(
                    TEACHER,
                    () -> commands.closeLesson(closed.id(), new LessonVersion(closed.version()))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("LESSON_NOT_CONFIRMED");
    try (var c = EngineeringTest.admin();
        var s = c.prepareStatement("select count(*) from teaching.consumption where lesson_id=?")) {
      s.setObject(1, closed.id());
      var r = s.executeQuery();
      r.next();
      assertThat(r.getLong(1)).isEqualTo(1);
    }
    assertThat(asActor(READER, () -> queries.reconciliation(member)).consumedHours())
        .isEqualTo("1.00");
  }

  @Test
  void secondLessonAccumulatesConsumption() {
    var classView = classWithMember("累计班");
    var member = membershipOf(classView.id());
    for (var date : List.of("2026-03-09", "2026-03-10")) {
      var lesson = published(classView.id(), date);
      var confirmed =
          asActor(
              TEACHER,
              () ->
                  commands.confirmAttendance(
                      lesson.id(),
                      new ConfirmAttendance(
                          lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
      asActor(
          TEACHER,
          () -> commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
    }
    var reconciliation = asActor(READER, () -> queries.reconciliation(member));
    assertThat(reconciliation.consumedHours()).isEqualTo("2.00");
    assertThat(reconciliation.consumedAmount()).isEqualTo("200.00");
    assertThat(reconciliation.remainingHours()).isEqualTo("8.00");
  }

  @Test
  void closingNeedsSeparatePermission() {
    var classView = classWithMember("权限班");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-11");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
    assertThatThrownBy(
            () ->
                asActor(
                    ASSISTANT,
                    () ->
                        commands.closeLesson(
                            confirmed.id(), new LessonVersion(confirmed.version()))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void readerCannotScheduleOrRecord() {
    var classView = newClass("只读班");
    assertThatThrownBy(
            () ->
                asActor(
                    READER,
                    () ->
                        commands.createLesson(
                            new CreateLesson(
                                UUID.randomUUID(),
                                classView.id(),
                                "2026-03-12",
                                "09:00",
                                "10:00",
                                null,
                                null,
                                null,
                                "1.00"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> asActor(READER, () -> queries.reconciliation(UUID.randomUUID())))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void crossTenantWriteRejectedByRls() {
    assertThatThrownBy(
            () ->
                tx.execute(
                    OTHER_TENANT,
                    () ->
                        sql.execute(
                            "insert into teaching.class(tenant_id,school_id,curriculum_id,name)"
                                + " values (?,?,?,'越租户班级')",
                            TENANT,
                            SCHOOL,
                            CURRICULUM)))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }

  @Test
  void concurrentCloseProducesSingleConsumption() throws Exception {
    var classView = classWithMember("并发关闭班");
    var member = membershipOf(classView.id());
    var lesson = published(classView.id(), "2026-03-13");
    var confirmed =
        asActor(
            TEACHER,
            () ->
                commands.confirmAttendance(
                    lesson.id(),
                    new ConfirmAttendance(
                        lesson.version(), List.of(new AttendanceInput(member, "PRESENT")))));
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<String> job =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              asActor(
                  TEACHER,
                  () ->
                      commands.closeLesson(confirmed.id(), new LessonVersion(confirmed.version())));
              return "OK";
            } catch (TeachingConflict conflict) {
              return conflict.code();
            }
          };
      var a = pool.submit(job);
      var b = pool.submit(job);
      var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
      assertThat(results).contains("OK");
      assertThat(results.stream().filter(value -> !value.equals("OK")).findFirst().orElseThrow())
          .isIn("LESSON_NOT_CONFIRMED", "VERSION_CONFLICT");
      assertThat(asActor(READER, () -> queries.reconciliation(member)).consumedHours())
          .isEqualTo("1.00");
    }
  }

  HttpResponse<String> get(HttpClient client, String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient client, String path, String body, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(
                "Content-Type",
                path.endsWith("login") ? "application/x-www-form-urlencoded" : "application/json");
    if (token != null) builder.header("X-CSRF-TOKEN", token);
    return client.send(
        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void httpSecondEntryWalksTheLessonLifecycle() throws Exception {
    var classView = classWithMember("接口班");
    var member = membershipOf(classView.id());
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    var csrf = json.readTree(get(client, "/api/session/csrf").body());
    assertThat(
            post(
                    client,
                    "/api/session/login",
                    "username=teaching-teacher&password=" + PASSWORD,
                    csrf.get("token").asText())
                .statusCode())
        .isEqualTo(204);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var lessonBody =
        json.writeValueAsString(
            new CreateLesson(
                UUID.randomUUID(),
                classView.id(),
                "2026-03-16",
                "09:00",
                "10:00",
                "教师甲",
                "教室甲",
                "接口课",
                "1.00"));
    var created = post(client, "/api/teaching/lessons", lessonBody, token);
    assertThat(created.statusCode()).isEqualTo(201);
    var lesson = json.readTree(created.body());
    var id = lesson.get("id").asText();
    assertThat(lesson.get("hours").asText()).isEqualTo("1.00");
    assertThat(
            post(
                    client,
                    "/api/teaching/lessons/" + id + "/publish",
                    "{\"expectedVersion\":0}",
                    token)
                .statusCode())
        .isEqualTo(200);
    var attendance =
        "{\"expectedVersion\":1,\"records\":[{\"enrollmentId\":\""
            + member
            + "\",\"status\":\"PRESENT\"}]}";
    assertThat(
            post(client, "/api/teaching/lessons/" + id + "/attendance", attendance, token)
                .statusCode())
        .isEqualTo(200);
    assertThat(
            post(client, "/api/teaching/lessons/" + id + "/close", "{\"expectedVersion\":2}", token)
                .statusCode())
        .isEqualTo(200);
    var reconciliation = get(client, "/api/teaching/enrollments/" + member + "/reconciliation");
    assertThat(reconciliation.statusCode()).isEqualTo(200);
    var body = json.readTree(reconciliation.body());
    assertThat(body.get("consumedHours").asText()).isEqualTo("1.00");
    assertThat(body.get("consumedAmount").asText()).isEqualTo("100.00");
    assertThat(body.get("remainingHours").isTextual()).isTrue();
  }

  @Test
  void httpBatchMemberRouteAddsOneEnrollmentToMultipleClasses() throws Exception {
    var first = newClass("HTTP批量甲");
    var second = newClass("HTTP批量乙");
    var enrollmentId = activeEnrollment();
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    var json = JsonMapper.builder().build();
    var csrf = json.readTree(get(client, "/api/session/csrf").body());
    assertThat(
            post(
                    client,
                    "/api/session/login",
                    "username=teaching-teacher&password=" + PASSWORD,
                    csrf.get("token").asText())
                .statusCode())
        .isEqualTo(204);
    var token = json.readTree(get(client, "/api/session/csrf").body()).get("token").asText();
    var result =
        post(
            client,
            "/api/teaching/classes/members/batch",
            json.writeValueAsString(
                new AddClassMembers(enrollmentId, List.of(first.id(), second.id()))),
            token);

    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(json.readTree(result.body()).size()).isEqualTo(2);
    assertThat(asActor(TEACHER, () -> queries.members(first.id()))).hasSize(1);
    assertThat(asActor(TEACHER, () -> queries.members(second.id()))).hasSize(1);
  }
}
