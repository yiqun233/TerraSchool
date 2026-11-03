package org.teraschool.server;

import static org.assertj.core.api.Assertions.*;
import static org.teraschool.server.EngineeringTest.update;

import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.teraschool.teaching.api.*;

/** 教师/教室实体化：课次与排课引用真实资源，名称作快照，冲突按实体判定。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LessonResourceTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      OTHER_SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      TEACHER = UUID.randomUUID(),
      INACTIVE_TEACHER = UUID.randomUUID(),
      ROOM = UUID.randomUUID(),
      FOREIGN_TEACHER = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @Autowired TeachingCommands teaching;
  @Autowired TeachingQueries teachingQueries;
  @Autowired org.teraschool.teaching.api.SchedulingOperations scheduling;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "资源机构");
    for (var entry : Map.of(SCHOOL, "资源学校", OTHER_SCHOOL, "别的学校").entrySet())
      update(
          "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL',?)",
          entry.getKey(),
          TENANT,
          entry.getKey(),
          entry.getValue());
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'资源课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into organization.teacher(id,tenant_id,school_id,name,active) values (?,?,?,'张老师',true),(?,?,?,'停用老师',false),(?,?,?,'外校老师',true)",
        TEACHER,
        TENANT,
        SCHOOL,
        INACTIVE_TEACHER,
        TENANT,
        SCHOOL,
        FOREIGN_TEACHER,
        TENANT,
        OTHER_SCHOOL);
    update(
        "insert into organization.room(id,tenant_id,school_id,name,capacity) values (?,?,?,'A101',30)",
        ROOM,
        TENANT,
        SCHOOL);
    var encoder =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    update(
        "insert into identity.account(id,tenant_id,login,password_hash) values (?,?,?,?)",
        OPS,
        TENANT,
        "resource-ops",
        encoder.encode(UUID.randomUUID().toString()));
    for (var action : new String[] {"TEACHING_READ", "TEACHING_MAINTAIN"})
      update(
          "insert into identity.account_permission(tenant_id,account_id,school_id,action,effect)"
              + " values (?,?,?,?,'ALLOW')",
          TENANT,
          OPS,
          SCHOOL,
          action);
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
    return asActor(
            OPS,
            () ->
                teaching.createClass(
                    new CreateClass(
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "资源班" + UUID.randomUUID())))
        .id();
  }

  @Test
  void lessonStoresResourceIdsAndNameSnapshot() {
    var classId = newClass();
    var lesson =
        asActor(
            OPS,
            () ->
                teaching.createLesson(
                    new CreateLesson(
                        UUID.randomUUID(),
                        classId,
                        "2026-07-01",
                        "09:00",
                        "10:00",
                        null,
                        null,
                        "实体课",
                        "1.00",
                        TEACHER,
                        ROOM)));
    assertThat(lesson.teacherId()).isEqualTo(TEACHER);
    assertThat(lesson.roomId()).isEqualTo(ROOM);
    assertThat(lesson.teacherName()).isEqualTo("张老师");
    assertThat(lesson.roomName()).isEqualTo("A101");
  }

  @Test
  void rejectsTeacherFromAnotherSchool() {
    var classId = newClass();
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        teaching.createLesson(
                            new CreateLesson(
                                UUID.randomUUID(),
                                classId,
                                "2026-07-02",
                                "09:00",
                                "10:00",
                                null,
                                null,
                                null,
                                "1.00",
                                FOREIGN_TEACHER,
                                null))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("RESOURCE_NOT_IN_SCHOOL");
  }

  @Test
  void rejectsInactiveTeacher() {
    var classId = newClass();
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        teaching.createLesson(
                            new CreateLesson(
                                UUID.randomUUID(),
                                classId,
                                "2026-07-03",
                                "09:00",
                                "10:00",
                                null,
                                null,
                                null,
                                "1.00",
                                INACTIVE_TEACHER,
                                null))))
        .isInstanceOf(TeachingConflict.class)
        .hasMessage("RESOURCE_INACTIVE");
  }

  @Test
  void schedulingConflictsByTeacherEntity() {
    var first = newClass();
    var rule =
        new ScheduleRule(
            "2026-08-01",
            "2026-08-08",
            List.of(6),
            "09:00",
            "10:00",
            "1.00",
            null,
            null,
            "课",
            TEACHER,
            ROOM);
    assertThat(asActor(OPS, () -> scheduling.confirm(first, rule)).created()).isEqualTo(2);
    var second = newClass();
    var preview = asActor(OPS, () -> scheduling.preview(second, rule));
    assertThat(preview.available()).isZero();
    assertThat(preview.slots())
        .allSatisfy(slot -> assertThat(slot.reason()).isEqualTo("TEACHER_CLASH"));
    var lessons = asActor(OPS, () -> teachingQueries.lessons(first));
    assertThat(lessons).allSatisfy(lesson -> assertThat(lesson.teacherId()).isEqualTo(TEACHER));
  }

  @Test
  void schedulingRejectsForeignTeacher() {
    var classId = newClass();
    var rule =
        new ScheduleRule(
            "2026-09-05",
            "2026-09-05",
            List.of(6),
            "09:00",
            "10:00",
            "1.00",
            null,
            null,
            null,
            FOREIGN_TEACHER,
            null);
    assertThatThrownBy(() -> asActor(OPS, () -> scheduling.preview(classId, rule)))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("RESOURCE_NOT_IN_SCHOOL");
  }
}
