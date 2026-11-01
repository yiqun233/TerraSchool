package org.teraschool.teaching.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Time;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.teraschool.identity.api.Actor;
import org.teraschool.identity.api.CurrentActor;
import org.teraschool.organization.api.SchoolAccess;
import org.teraschool.platform.api.RequestTrace;
import org.teraschool.platform.api.TenantTransactions;
import org.teraschool.teaching.api.*;

/**
 * 自动排课：按「周几 + 固定时段」在日期范围内生成课次。
 *
 * <p>只实现旧系统证据里的硬约束：班级须在用、时段合法、同班/同教师/同教室不能时间重叠、同一时段不重复生成。 教师活动与休假、教室预订、学员距离等软约束尚未建模，因此不做降级，只按「可用/跳过
 * + 原因」给出预览。
 *
 * <p>预览不写库；确认只把可用时段写成 DRAFT 课次，发布与确认仍走既有状态机；重复确认是幂等的。
 */
@Service
public class SchedulingService {
  private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
  private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final int MAX_DAYS = 180;
  private static final int MAX_SLOTS = 400;

  private final CurrentActor actors;
  private final TenantTransactions tx;
  private final DSLContext sql;
  private final SchoolAccess schools;

  public SchedulingService(
      CurrentActor actors, TenantTransactions tx, DSLContext sql, SchoolAccess schools) {
    this.actors = actors;
    this.tx = tx;
    this.sql = sql;
    this.schools = schools;
  }

  public SchedulePreview preview(UUID classId, ScheduleRule rule) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(scope.tenantId(), () -> previewInTx(classId, rule, scope));
    }
  }

  public ScheduleResult confirm(UUID classId, ScheduleRule rule) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var plan = plan(classId, rule, verified);
            var created = 0;
            var skipped = new ArrayList<String>();
            for (var slot : plan.slots()) {
              if (!slot.available()) {
                skipped.add(slot.lessonDate() + ":" + slot.reason());
                continue;
              }
              sql.execute(
                  "insert into teaching.lesson(tenant_id,class_id,lesson_date,starts_at,ends_at,"
                      + "teacher_name,room_name,topic,hours) values (?,?,?,?,?,?,?,?,?)",
                  verified.tenantId(),
                  classId,
                  LocalDate.parse(slot.lessonDate()),
                  Time.valueOf(LocalTime.parse(slot.startsAt())),
                  Time.valueOf(LocalTime.parse(slot.endsAt())),
                  plan.teacher(),
                  plan.room(),
                  plan.topic(),
                  plan.hours());
              created++;
            }
            audit(verified, classId, plan.schoolId(), created, skipped.size());
            return new ScheduleResult(created, skipped.size(), skipped);
          });
    }
  }

  // ---------------------------------------------------------------- 内部

  private record Plan(
      UUID schoolId,
      String teacher,
      String room,
      String topic,
      BigDecimal hours,
      List<ScheduleSlot> slots) {}

  private SchedulePreview previewInTx(UUID classId, ScheduleRule rule, Actor actor) {
    var plan = plan(classId, rule, actor);
    var available = plan.slots().stream().filter(ScheduleSlot::available).count();
    return new SchedulePreview(
        classId,
        plan.slots().size(),
        (int) available,
        plan.slots().size() - (int) available,
        plan.slots());
  }

  private Plan plan(UUID classId, ScheduleRule rule, Actor actor) {
    if (classId == null || rule == null) throw new IllegalArgumentException("需要班级与排课规则");
    var start = date(rule.startDate(), "开始日期");
    var end = date(rule.endDate(), "结束日期");
    if (end.isBefore(start)) throw new ScheduleConflict("SCHEDULE_RANGE_INVALID");
    if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS)
      throw new ScheduleConflict("SCHEDULE_RANGE_TOO_LONG");
    var weekdays = rule.weekdays() == null ? List.<Integer>of() : rule.weekdays();
    if (weekdays.isEmpty()) throw new ScheduleConflict("SCHEDULE_WEEKDAY_REQUIRED");
    for (var day : weekdays)
      if (day == null || day < 1 || day > 7) throw new ScheduleConflict("SCHEDULE_WEEKDAY_INVALID");
    var startsAt = time(rule.startsAt(), "开始时间");
    var endsAt = time(rule.endsAt(), "结束时间");
    if (!endsAt.isAfter(startsAt)) throw new ScheduleConflict("SCHEDULE_TIME_INVALID");
    var hours = hours(rule.hours());
    var teacher = trim(rule.teacherName(), 50);
    var room = trim(rule.roomName(), 50);
    var topic = trim(rule.topic(), 100);

    var row = sql.fetchOne("select id,school_id,status from teaching.class where id=?", classId);
    if (row == null) throw new AccessDeniedException("班级不存在或不可访问");
    var schoolId = row.get("school_id", UUID.class);
    if (!actor.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的排课权限");
    if (!"ACTIVE".equals(row.get("status", String.class)))
      throw new ScheduleConflict("CLASS_NOT_ACTIVE");
    if (!schools.require(schoolId).active()) throw new AccessDeniedException("学校已停用，不能排课");

    var dates = new ArrayList<LocalDate>();
    for (var date = start; !date.isAfter(end); date = date.plusDays(1))
      if (weekdays.contains(date.getDayOfWeek().getValue())) dates.add(date);
    if (dates.isEmpty()) throw new ScheduleConflict("SCHEDULE_NO_DATE");
    if (dates.size() > MAX_SLOTS) throw new ScheduleConflict("SCHEDULE_TOO_MANY_SLOTS");

    var slots = new ArrayList<ScheduleSlot>();
    for (var date : dates) {
      var reason = conflict(classId, date, startsAt, endsAt, teacher, room);
      slots.add(
          new ScheduleSlot(
              date.toString(), startsAt.toString(), endsAt.toString(), reason == null, reason));
    }
    return new Plan(schoolId, teacher, room, topic, hours, slots);
  }

  /** 冲突判定：同班/同教师/同教室任一重叠即跳过；完全相同的时段视为重复。 */
  private String conflict(
      UUID classId,
      LocalDate date,
      LocalTime startsAt,
      LocalTime endsAt,
      String teacher,
      String room) {
    var starts = Time.valueOf(startsAt);
    var ends = Time.valueOf(endsAt);
    var duplicate =
        sql.fetchOne(
            "select id from teaching.lesson where class_id=? and lesson_date=? and starts_at=?"
                + " and ends_at=? limit 1",
            classId,
            date,
            starts,
            ends);
    if (duplicate != null) return "DUPLICATE";
    var classClash =
        sql.fetchOne(
            "select id from teaching.lesson where class_id=? and lesson_date=? and starts_at<?"
                + " and ends_at>? limit 1",
            classId,
            date,
            ends,
            starts);
    if (classClash != null) return "LESSON_OVERLAP";
    if (teacher != null) {
      var teacherClash =
          sql.fetchOne(
              "select id from teaching.lesson where teacher_name=? and lesson_date=? and"
                  + " starts_at<? and ends_at>? limit 1",
              teacher,
              date,
              ends,
              starts);
      if (teacherClash != null) return "TEACHER_CLASH";
    }
    if (room != null) {
      var roomClash =
          sql.fetchOne(
              "select id from teaching.lesson where room_name=? and lesson_date=? and starts_at<?"
                  + " and ends_at>? limit 1",
              room,
              date,
              ends,
              starts);
      if (roomClash != null) return "ROOM_CLASH";
    }
    return null;
  }

  private BigDecimal hours(String value) {
    if (value == null || value.isBlank()) throw new ScheduleConflict("SCHEDULE_HOURS_INVALID");
    BigDecimal hours;
    try {
      hours = new BigDecimal(value.strip());
    } catch (NumberFormatException invalid) {
      throw new ScheduleConflict("SCHEDULE_HOURS_INVALID");
    }
    if (hours.signum() <= 0 || hours.scale() > 2)
      throw new ScheduleConflict("SCHEDULE_HOURS_INVALID");
    return hours.setScale(2, RoundingMode.UNNECESSARY);
  }

  private LocalDate date(String value, String label) {
    if (value == null || !DAY.matcher(value.strip()).matches())
      throw new IllegalArgumentException(label + "须为 YYYY-MM-DD");
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new IllegalArgumentException(label + "须为有效日期");
    }
  }

  private LocalTime time(String value, String label) {
    if (value == null || !TIME.matcher(value.strip()).matches())
      throw new IllegalArgumentException(label + "须为 HH:mm");
    return LocalTime.parse(value.strip());
  }

  private String trim(String value, int max) {
    if (value == null || value.isBlank()) return null;
    var text = value.strip();
    if (text.length() > max || text.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("文本超出长度或含控制字符");
    return text;
  }

  private void audit(Actor actor, UUID classId, UUID schoolId, int created, int skipped) {
    sql.execute(
        "insert into audit.teaching_event(tenant_id,actor_id,action,resource_id,school_id,"
            + "changed_fields,trace_id) values (?,?,'LESSON_CREATED',?,?,?,?)",
        actor.tenantId(),
        actor.accountId(),
        classId,
        schoolId,
        "scheduled:" + created + "/skipped:" + skipped,
        RequestTrace.id());
  }
}
