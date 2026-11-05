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

/**
 * 排课约束：资源不可用时间与学校级策略（最小间隔、是否只警告）。
 *
 * <p>对应旧系统的"教师参加活动/休假""教室预订"与 MinSpaceMinute、ignore 配置：默认阻断，可按学校放宽。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingConstraintTest {
  static final UUID TENANT = UUID.randomUUID(),
      SCHOOL = UUID.randomUUID(),
      CURRICULUM = UUID.randomUUID(),
      TEACHER = UUID.randomUUID(),
      ROOM = UUID.randomUUID(),
      OPS = UUID.randomUUID();

  @Autowired TeachingCommands teaching;
  @Autowired org.teraschool.teaching.api.SchedulingOperations scheduling;

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    EngineeringTest.config(r);
  }

  @BeforeAll
  static void seed() throws Exception {
    update("insert into identity.tenant(id,name) values (?,?)", TENANT, "约束机构");
    update(
        "insert into organization.unit(id,tenant_id,school_id,kind,name) values (?,?,?,'SCHOOL','约束学校')",
        SCHOOL,
        TENANT,
        SCHOOL);
    update(
        "insert into product.curriculum(id,tenant_id,school_id,name) values (?,?,?,'约束课程')",
        CURRICULUM,
        TENANT,
        SCHOOL);
    update(
        "insert into organization.teacher(id,tenant_id,school_id,name) values (?,?,?,'约束老师')",
        TEACHER,
        TENANT,
        SCHOOL);
    update(
        "insert into organization.room(id,tenant_id,school_id,name,capacity) values (?,?,?,'约束教室',20)",
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
        "constraint-ops",
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
                        UUID.randomUUID(), SCHOOL, CURRICULUM, "约束班" + UUID.randomUUID())))
        .id();
  }

  static ScheduleRule rule(String from, String to) {
    return new ScheduleRule(
        from, to, List.of(6), "09:00", "10:00", "1.00", null, null, "课", TEACHER, ROOM);
  }

  @Test
  void teacherUnavailableBlocksByDay() {
    var classId = newClass();
    var block =
        asActor(
            OPS,
            () ->
                scheduling.createBlock(
                    new ResourceBlock(
                        SCHOOL, TEACHER, null, "2026-10-03", "08:00", "12:00", "教师休假")));
    assertThat(block.teacherName()).isEqualTo("约束老师");
    var preview = asActor(OPS, () -> scheduling.preview(classId, rule("2026-10-03", "2026-10-10")));
    assertThat(preview.total()).isEqualTo(2);
    assertThat(preview.available()).isEqualTo(1);
    assertThat(preview.slots())
        .filteredOn(slot -> slot.lessonDate().equals("2026-10-03"))
        .allSatisfy(
            slot -> {
              assertThat(slot.available()).isFalse();
              assertThat(slot.reason()).isEqualTo("TEACHER_UNAVAILABLE");
              assertThat(slot.severity()).isEqualTo("HARD");
            });
  }

  @Test
  void schoolClosureSkipsAllLessonsAndCanBeCancelledWithVersion() {
    var classId = newClass();
    var closure =
        asActor(
            OPS,
            () ->
                scheduling.createSchoolClosure(
                    new CreateSchoolClosure(SCHOOL, "2026-11-14", "学校校历休息日")));
    assertThat(closure.active()).isTrue();
    var repeated =
        asActor(
            OPS,
            () ->
                scheduling.createSchoolClosure(
                    new CreateSchoolClosure(SCHOOL, "2026-11-14", "学校校历休息日")));
    assertThat(repeated.id()).isEqualTo(closure.id());
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        scheduling.createSchoolClosure(
                            new CreateSchoolClosure(SCHOOL, "2026-11-14", "其他原因"))))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("SCHOOL_CLOSURE_EXISTS");

    var preview = asActor(OPS, () -> scheduling.preview(classId, rule("2026-11-14", "2026-11-14")));
    assertThat(preview.slots())
        .singleElement()
        .satisfies(
            slot -> {
              assertThat(slot.available()).isFalse();
              assertThat(slot.reason()).isEqualTo("SCHOOL_CLOSED");
              assertThat(slot.severity()).isEqualTo("HARD");
            });
    assertThatThrownBy(() -> asActor(OPS, () -> scheduling.cancelSchoolClosure(closure.id(), 1L)))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("VERSION_CONFLICT");
    var cancelled =
        asActor(OPS, () -> scheduling.cancelSchoolClosure(closure.id(), closure.version()));
    assertThat(cancelled.active()).isFalse();
    assertThat(cancelled.version()).isEqualTo(1L);
    var available =
        asActor(OPS, () -> scheduling.preview(classId, rule("2026-11-14", "2026-11-14")));
    assertThat(available.slots())
        .singleElement()
        .satisfies(slot -> assertThat(slot.available()).isTrue());
  }

  @Test
  void roomUnavailableBlocks() {
    var classId = newClass();
    asActor(
        OPS,
        () ->
            scheduling.createBlock(
                new ResourceBlock(SCHOOL, null, ROOM, "2026-10-17", "09:00", "10:00", "教室预订")));
    var preview = asActor(OPS, () -> scheduling.preview(classId, rule("2026-10-17", "2026-10-17")));
    assertThat(preview.slots())
        .allSatisfy(slot -> assertThat(slot.reason()).isEqualTo("ROOM_UNAVAILABLE"));
  }

  @Test
  void policyCanDowngradeUnavailableToWarning() {
    var classId = newClass();
    asActor(OPS, () -> scheduling.savePolicy(SCHOOL, new SchedulePolicy(false, true, 0, 0L)));
    asActor(
        OPS,
        () ->
            scheduling.createBlock(
                new ResourceBlock(SCHOOL, TEACHER, null, "2026-10-24", "08:00", "12:00", "教师活动")));
    var preview = asActor(OPS, () -> scheduling.preview(classId, rule("2026-10-24", "2026-10-24")));
    var slot =
        preview.slots().stream()
            .filter(item -> item.lessonDate().equals("2026-10-24"))
            .findFirst()
            .orElseThrow();
    assertThat(slot.reason()).isEqualTo("TEACHER_UNAVAILABLE");
    assertThat(slot.severity()).isEqualTo("WARN");
    assertThat(slot.available()).isTrue();
  }

  @Test
  void minGapBetweenTeacherLessonsIsEnforced() {
    var first = newClass();
    // 08:00-09:00 已有课；策略要求最小间隔 30 分钟，09:00 的课应被拒。
    asActor(
        OPS,
        () ->
            teaching.createLesson(
                new CreateLesson(
                    UUID.randomUUID(),
                    first,
                    "2026-11-07",
                    "08:00",
                    "09:00",
                    null,
                    null,
                    null,
                    "1.00",
                    TEACHER,
                    ROOM)));
    asActor(OPS, () -> scheduling.savePolicy(SCHOOL, new SchedulePolicy(true, true, 30, null)));
    var second = newClass();
    var preview = asActor(OPS, () -> scheduling.preview(second, rule("2026-11-07", "2026-11-07")));
    assertThat(preview.slots())
        .filteredOn(slot -> "TEACHER_MIN_GAP".equals(slot.reason()))
        .isNotEmpty();
  }

  @Test
  void multiClassReservationsEnforceTeacherMinimumGap() {
    asActor(OPS, () -> scheduling.savePolicy(SCHOOL, new SchedulePolicy(true, true, 30, null)));
    var first = newClass();
    var second = newClass();
    var firstRule =
        new ScheduleRule(
            "2027-01-03",
            "2027-01-03",
            List.of(7),
            "08:00",
            "09:00",
            "1",
            null,
            null,
            "课",
            TEACHER,
            ROOM);
    var secondRule =
        new ScheduleRule(
            "2027-01-03",
            "2027-01-03",
            List.of(7),
            "09:00",
            "10:00",
            "1",
            null,
            null,
            "课",
            TEACHER,
            ROOM);

    var preview =
        asActor(
            OPS,
            () ->
                scheduling.previewBatch(
                    new ScheduleBatchRequest(
                        List.of(
                            new ScheduleBatchItem(first, firstRule),
                            new ScheduleBatchItem(second, secondRule)))));

    assertThat(preview.classes().get(0).available()).isEqualTo(1);
    assertThat(preview.classes().get(1).slots())
        .allSatisfy(slot -> assertThat(slot.reason()).isEqualTo("TEACHER_MIN_GAP"));
  }

  @Test
  void blockRequiresExactlyOneResource() {
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        scheduling.createBlock(
                            new ResourceBlock(
                                SCHOOL, TEACHER, ROOM, "2026-10-03", "09:00", "10:00", null))))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("RESOURCE_REQUIRED");
  }

  @Test
  void policyRequiresVersionOnUpdate() {
    var saved =
        asActor(OPS, () -> scheduling.savePolicy(SCHOOL, new SchedulePolicy(true, true, 10, null)));
    assertThat(saved.minGapMinutes()).isEqualTo(10);
    assertThatThrownBy(
            () ->
                asActor(
                    OPS,
                    () ->
                        scheduling.savePolicy(
                            SCHOOL, new SchedulePolicy(true, true, 20, saved.version() + 5))))
        .isInstanceOf(ScheduleConflict.class)
        .hasMessage("VERSION_CONFLICT");
    var updated =
        asActor(
            OPS,
            () ->
                scheduling.savePolicy(SCHOOL, new SchedulePolicy(true, true, 20, saved.version())));
    assertThat(updated.minGapMinutes()).isEqualTo(20);
    assertThat(updated.version()).isEqualTo(saved.version() + 1);
  }

  @Test
  void blockCanBeDeletedAndDeleteIsIdempotent() {
    var block =
        asActor(
            OPS,
            () ->
                scheduling.createBlock(
                    new ResourceBlock(
                        SCHOOL, TEACHER, null, "2026-12-05", "09:00", "12:00", "临时安排")));
    assertThat(asActor(OPS, () -> scheduling.deleteBlock(block.id()))).isTrue();
    assertThat(asActor(OPS, () -> scheduling.blocks(SCHOOL, "2026-12-05", "2026-12-05"))).isEmpty();
    // 再删一次仍按成功处理，便于重复点击。
    assertThat(asActor(OPS, () -> scheduling.deleteBlock(block.id()))).isTrue();
  }

  @Test
  void deletingUnknownBlockIsTreatedAsDone() {
    assertThat(asActor(OPS, () -> scheduling.deleteBlock(UUID.randomUUID()))).isTrue();
  }
}
