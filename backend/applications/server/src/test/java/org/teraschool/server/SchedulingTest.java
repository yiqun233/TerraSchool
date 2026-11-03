package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
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

/** 自动排课：按周几与固定时段生成课次，跳过同班/同教师/同教室冲突，确认幂等。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingTest {
  static final UUID TENANT = UUID.randomUUID(),
      OTHER_TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      OPS = UUID.randomUUID(),
      READER = UUID.randomUUID();
  static final String PASSWORD = UUID.randomUUID().toString();

  @LocalServerPort int port;
  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired org.teraschool.teaching.api.SchedulingOperations scheduling;
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
        "排课机构甲",
        OTHER_TENANT,
        "排课机构乙");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','排课学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'排课课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    for (var entry : Map.of(OPS, "schedule-ops", READER, "schedule-reader").entrySet())
      update(
          "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
          entry.getKey(),
          TENANT,
          entry.getValue(),
          encoder.encode(PASSWORD));
    for (var action : new String[] {"TEACHING_READ", "TEACHING_MAINTAIN"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
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

  UUID newClass() {
    var view =
        asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "排课班" + UUID.randomUUID())));
    return view.id();
  }

  /** 2026-05-02 是周六；再加 3 个周六。 */
  static ScheduleRule rule(String teacher, String room) {
    return new ScheduleRule(
        "2026-05-02", "2026-05-23", List.of(6), "09:00", "10:00", "1.00", teacher, room, "常规课");
  }

  @Test
  void generatesOneDraftLessonPerMatchingWeekday() {
    var classId = newClass();
    var preview = asActor(OPS, () -> scheduling.preview(classId, rule("张老师", "A101")));
    assertThat(preview.total()).isEqualTo(4);
    assertThat(preview.available()).isEqualTo(4);
    var result = asActor(OPS, () -> scheduling.confirm(classId, rule("张老师", "A101")));
    assertThat(result.created()).isEqualTo(4);
    var lessons = asActor(OPS, () -> teachingQueries.lessons(classId));
    assertThat(lessons).hasSize(4);
    assertThat(lessons).allSatisfy(lesson -> assertThat(lesson.status()).isEqualTo("DRAFT"));
  }

  @Test
  void confirmIsIdempotentOnRepeat() {
    var classId = newClass();
    assertThat(asActor(OPS, () -> scheduling.confirm(classId, rule("李老师", "A102"))).created())
        .isEqualTo(4);
    var again = asActor(OPS, () -> scheduling.confirm(classId, rule("李老师", "A102")));
    assertThat(again.created()).isZero();
    assertThat(again.skipped()).isEqualTo(4);
    assertThat(again.skippedDates()).allSatisfy(date -> assertThat(date).contains("DUPLICATE"));
  }

  @Test
  void skipsWhenTeacherIsBookedElsewhere() {
    var first = newClass();
    asActor(OPS, () -> scheduling.confirm(first, rule("王老师", "B201")));
    var second = newClass();
    var preview = asActor(OPS, () -> scheduling.preview(second, rule("王老师", "B202")));
    assertThat(preview.available()).isZero();
    assertThat(preview.slots())
        .allSatisfy(slot -> assertThat(slot.reason()).isEqualTo("TEACHER_CLASH"));
  }

  @Test
  void skipsWhenRoomIsTaken() {
    var first = newClass();
    asActor(OPS, () -> scheduling.confirm(first, rule("赵老师", "C301")));
    var second = newClass();
    var preview = asActor(OPS, () -> scheduling.preview(second, rule("钱老师", "C301")));
    assertThat(preview.slots())
        .allSatisfy(slot -> assertThat(slot.reason()).isEqualTo("ROOM_CLASH"));
  }

  @Test
  void previewDoesNotWriteLessons() {
    var classId = newClass();
    asActor(OPS, () -> scheduling.preview(classId, rule("孙老师", "D401")));
    assertThat(asActor(OPS, () -> teachingQueries.lessons(classId))).isEmpty();
  }

  @Test
  void rejectsInvalidRules() {
    var classId = newClass();
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        scheduling.preview(
                            classId,
                            new ScheduleRule(
                                "2026-05-02",
                                "2026-05-02",
                                List.of(),
                                "09:00",
                                "10:00",
                                "1.00",
                                null,
                                null,
                                null))))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("SCHEDULE_WEEKDAY_REQUIRED");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        scheduling.preview(
                            classId,
                            new ScheduleRule(
                                "2026-05-02",
                                "2026-05-02",
                                List.of(6),
                                "10:00",
                                "09:00",
                                "1.00",
                                null,
                                null,
                                null))))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("SCHEDULE_TIME_INVALID");
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        scheduling.preview(
                            classId,
                            new ScheduleRule(
                                "2026-01-01",
                                "2026-12-31",
                                List.of(6),
                                "09:00",
                                "10:00",
                                "1.00",
                                null,
                                null,
                                null))))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("SCHEDULE_RANGE_TOO_LONG");
  }

  @Test
  void requiresMaintainPermission() {
    var classId = newClass();
    assertThatThrownBy(
            () -> asActor(READER, () -> scheduling.preview(classId, rule("周老师", "E501"))))
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
                            "insert into teaching.lesson(tenant_id,class_id,lesson_date,starts_at,"
                                + "ends_at,hours) values (?,?,'2026-06-01','09:00','10:00',1.00)",
                            TENANT,
                            UUID.randomUUID())))
        .satisfies(
            error -> {
              Throwable cause = error;
              while (cause.getCause() != null) cause = cause.getCause();
              assertThat(cause).isInstanceOf(java.sql.SQLException.class);
              assertThat(((java.sql.SQLException) cause).getSQLState()).isEqualTo("42501");
            });
  }
}
