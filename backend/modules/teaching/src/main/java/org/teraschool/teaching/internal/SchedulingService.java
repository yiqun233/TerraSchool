package org.teraschool.teaching.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Time;
import java.time.*;
import java.time.format.DateTimeFormatter;
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
public class SchedulingService implements org.teraschool.teaching.api.SchedulingOperations {
  private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
  private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final int MAX_DAYS = 180;
  private static final int MAX_SLOTS = 400;
  private static final int MAX_BATCH_CLASSES = 20;
  private static final int MAX_BATCH_SLOTS = 400;

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
                      + "teacher_name,room_name,topic,hours,teacher_id,room_id)"
                      + " values (?,?,?,?,?,?,?,?,?,?,?)",
                  verified.tenantId(),
                  classId,
                  LocalDate.parse(slot.lessonDate()),
                  Time.valueOf(LocalTime.parse(slot.startsAt())),
                  Time.valueOf(LocalTime.parse(slot.endsAt())),
                  plan.teacherName(),
                  plan.roomName(),
                  plan.topic(),
                  plan.hours(),
                  plan.teacherId(),
                  plan.roomId());
              created++;
            }
            audit(verified, classId, plan.schoolId(), created, skipped.size());
            return new ScheduleResult(created, skipped.size(), skipped);
          });
    }
  }

  @Override
  public ScheduleBatchPreview previewBatch(ScheduleBatchRequest request) {
    if (request == null) throw new IllegalArgumentException("需要多班排课请求");
    validateBatch(request.items());
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          scope.tenantId(),
          () -> {
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var reservations = new ArrayList<Reservation>();
            var previews = new ArrayList<SchedulePreview>();
            var total = 0;
            for (var item : request.items()) {
              var plan = plan(item.classId(), item.rule(), verified, reservations);
              total += plan.slots().size();
              if (total > MAX_BATCH_SLOTS)
                throw new ScheduleConflict("SCHEDULE_BATCH_TOO_MANY_SLOTS");
              var preview = preview(plan, item.classId());
              previews.add(preview);
              reserve(item.classId(), plan, reservations);
            }
            return new ScheduleBatchPreview(previews);
          });
    }
  }

  @Override
  public ScheduleBatchResult confirmBatch(ScheduleBatchRequest request) {
    if (request == null) throw new IllegalArgumentException("需要多班排课请求");
    validateBatch(request.items());
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            var total = 0;
            var createdAll = 0;
            var skippedAll = 0;
            var results = new ArrayList<ScheduleBatchResult.ClassResult>();
            for (var item : request.items()) {
              var plan = plan(item.classId(), item.rule(), verified);
              total += plan.slots().size();
              if (total > MAX_BATCH_SLOTS)
                throw new ScheduleConflict("SCHEDULE_BATCH_TOO_MANY_SLOTS");
              var created = 0;
              var skippedDates = new ArrayList<String>();
              for (var slot : plan.slots()) {
                if (!slot.available()) {
                  skippedDates.add(slot.lessonDate() + ":" + slot.reason());
                  continue;
                }
                sql.execute(
                    "insert into teaching.lesson(tenant_id,class_id,lesson_date,starts_at,ends_at,"
                        + "teacher_name,room_name,topic,hours,teacher_id,room_id)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?)",
                    verified.tenantId(),
                    item.classId(),
                    LocalDate.parse(slot.lessonDate()),
                    Time.valueOf(LocalTime.parse(slot.startsAt())),
                    Time.valueOf(LocalTime.parse(slot.endsAt())),
                    plan.teacherName(),
                    plan.roomName(),
                    plan.topic(),
                    plan.hours(),
                    plan.teacherId(),
                    plan.roomId());
                created++;
              }
              audit(verified, item.classId(), plan.schoolId(), created, skippedDates.size());
              var result = new ScheduleResult(created, skippedDates.size(), skippedDates);
              results.add(new ScheduleBatchResult.ClassResult(item.classId(), result));
              createdAll += created;
              skippedAll += skippedDates.size();
            }
            return new ScheduleBatchResult(createdAll, skippedAll, results);
          });
    }
  }

  private void validateBatch(List<ScheduleBatchItem> items) {
    if (items == null || items.isEmpty() || items.size() > MAX_BATCH_CLASSES)
      throw new ScheduleConflict("SCHEDULE_BATCH_SIZE_INVALID");
    var ids = new HashSet<UUID>();
    for (var item : items) {
      if (item == null || item.classId() == null || item.rule() == null)
        throw new IllegalArgumentException("每个排课项都需要班级和规则");
      if (!ids.add(item.classId())) throw new ScheduleConflict("SCHEDULE_BATCH_DUPLICATE_CLASS");
    }
  }

  private SchedulePreview preview(Plan plan, UUID classId) {
    var available = plan.slots().stream().filter(ScheduleSlot::available).count();
    return new SchedulePreview(
        classId,
        plan.slots().size(),
        (int) available,
        plan.slots().size() - (int) available,
        plan.teacherName(),
        plan.roomName(),
        plan.slots());
  }

  private record Reservation(
      UUID classId,
      LocalDate date,
      LocalTime startsAt,
      LocalTime endsAt,
      UUID teacherId,
      String teacherName,
      UUID roomId,
      String roomName) {}

  private void reserve(UUID classId, Plan plan, List<Reservation> reservations) {
    for (var slot : plan.slots())
      if (slot.available())
        reservations.add(
            new Reservation(
                classId,
                LocalDate.parse(slot.lessonDate()),
                LocalTime.parse(slot.startsAt()),
                LocalTime.parse(slot.endsAt()),
                plan.teacherId(),
                plan.teacherName(),
                plan.roomId(),
                plan.roomName()));
  }

  // ---------------------------------------------------------------- 资源不可用时间与策略

  public ResourceBlockView createBlock(ResourceBlock command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          scope.tenantId(),
          () -> {
            if (command == null || command.schoolId() == null)
              throw new IllegalArgumentException("需要学校");
            if ((command.teacherId() == null) == (command.roomId() == null))
              throw new ScheduleConflict("RESOURCE_REQUIRED");
            if (!scope.schools().contains(command.schoolId()))
              throw new AccessDeniedException("无该学校的排课权限");
            if (!schools.require(command.schoolId()).active())
              throw new AccessDeniedException("学校已停用，不能维护不可用时间");
            var date = date(command.blockDate(), "日期");
            var startsAt = time(command.startsAt(), "开始时间");
            var endsAt = time(command.endsAt(), "结束时间");
            if (!endsAt.isAfter(startsAt)) throw new ScheduleConflict("SCHEDULE_TIME_INVALID");
            var id = UUID.randomUUID();
            sql.execute(
                "insert into teaching.resource_block(id,tenant_id,school_id,teacher_id,room_id,"
                    + "block_date,starts_at,ends_at,reason) values (?,?,?,?,?,?,?,?,?)",
                id,
                scope.tenantId(),
                command.schoolId(),
                command.teacherId(),
                command.roomId(),
                date,
                Time.valueOf(startsAt),
                Time.valueOf(endsAt),
                trim(command.reason(), 100));
            return blockView(id);
          });
    }
  }

  public List<ResourceBlockView> blocks(UUID schoolId, String from, String to) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的读取权限");
    var start = from == null || from.isBlank() ? LocalDate.now() : date(from, "开始日期");
    var end = to == null || to.isBlank() ? start.plusDays(60) : date(to, "结束日期");
    return tx.execute(
        scope.tenantId(),
        () ->
            sql.fetch(
                    "select b.id,b.teacher_id,b.room_id,b.block_date,b.starts_at,b.ends_at,b.reason,"
                        + "t.name as teacher_name,r.name as room_name from teaching.resource_block b"
                        + " left join organization.teacher t on t.id=b.teacher_id"
                        + " left join organization.room r on r.id=b.room_id"
                        + " where b.school_id=? and b.block_date between ? and ?"
                        + " order by b.block_date, b.starts_at limit 500",
                    schoolId,
                    start,
                    end)
                .map(this::blockViewOf));
  }

  public SchedulePolicyView policyOf(UUID schoolId) {
    var scope = actors.requireResourceScope("TEACHING");
    if (schoolId == null || !scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的读取权限");
    return tx.execute(scope.tenantId(), () -> policyView(schoolId));
  }

  public SchedulePolicyView savePolicy(UUID schoolId, SchedulePolicy command) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          scope.tenantId(),
          () -> {
            schools.lockTenant(scope.tenantId());
            var verified = actors.requireActionInTransaction("TEACHING_MAINTAIN");
            if (schoolId == null || !verified.schools().contains(schoolId))
              throw new AccessDeniedException("无该学校的排课权限");
            if (command == null || command.minGapMinutes() < 0 || command.minGapMinutes() > 240)
              throw new ScheduleConflict("SCHEDULE_GAP_INVALID");
            var existing =
                sql.fetchOne(
                    "select id,version from teaching.schedule_policy where school_id=? for update",
                    schoolId);
            if (existing == null) {
              if (command.expectedVersion() != null && command.expectedVersion() != 0)
                throw new ScheduleConflict("VERSION_CONFLICT");
              sql.execute(
                  "insert into teaching.schedule_policy(tenant_id,school_id,block_teacher_unavailable,"
                      + "block_room_unavailable,min_gap_minutes) values (?,?,?,?,?)",
                  scope.tenantId(),
                  schoolId,
                  command.blockTeacherUnavailable(),
                  command.blockRoomUnavailable(),
                  command.minGapMinutes());
            } else {
              if (command.expectedVersion() != null
                  && !existing.get("version", Long.class).equals(command.expectedVersion()))
                throw new ScheduleConflict("VERSION_CONFLICT");
              sql.execute(
                  "update teaching.schedule_policy set block_teacher_unavailable=?,"
                      + "block_room_unavailable=?,min_gap_minutes=?,version=version+1,"
                      + "updated_at=CURRENT_TIMESTAMP where id=?",
                  command.blockTeacherUnavailable(),
                  command.blockRoomUnavailable(),
                  command.minGapMinutes(),
                  existing.get("id", UUID.class));
            }
            return policyView(schoolId);
          });
    }
  }

  /** 撤销一条不可用时间；已不存在时按成功处理，便于重复点击。 */
  public boolean deleteBlock(UUID id) {
    try (var trace = RequestTrace.open(RequestTrace.Source.APPLICATION)) {
      var scope = actors.requireAction("TEACHING_MAINTAIN");
      return tx.execute(
          scope.tenantId(),
          () -> {
            var row =
                sql.fetchOne("select id,school_id from teaching.resource_block where id=?", id);
            if (row == null) return true;
            var schoolId = row.get("school_id", UUID.class);
            if (!scope.schools().contains(schoolId)) throw new AccessDeniedException("无该学校的排课权限");
            sql.execute("delete from teaching.resource_block where id=?", id);
            return true;
          });
    }
  }

  private SchedulePolicyView policyView(UUID schoolId) {
    var row =
        sql.fetchOne(
            "select block_teacher_unavailable,block_room_unavailable,min_gap_minutes,version"
                + " from teaching.schedule_policy where school_id=?",
            schoolId);
    if (row == null) return new SchedulePolicyView(schoolId, true, true, 0, 0);
    return new SchedulePolicyView(
        schoolId,
        row.get("block_teacher_unavailable", Boolean.class),
        row.get("block_room_unavailable", Boolean.class),
        row.get("min_gap_minutes", Integer.class),
        row.get("version", Long.class));
  }

  private ResourceBlockView blockViewOf(org.jooq.Record row) {
    return new ResourceBlockView(
        row.get("id", UUID.class),
        row.get("teacher_id", UUID.class),
        row.get("teacher_name", String.class),
        row.get("room_id", UUID.class),
        row.get("room_name", String.class),
        date(row.get("block_date", LocalDate.class)),
        time(row.get("starts_at", LocalTime.class)),
        time(row.get("ends_at", LocalTime.class)),
        row.get("reason", String.class));
  }

  private ResourceBlockView blockView(UUID id) {
    var row =
        sql.fetchOne(
            "select b.id,b.teacher_id,b.room_id,b.block_date,b.starts_at,b.ends_at,b.reason,"
                + "t.name as teacher_name,r.name as room_name from teaching.resource_block b"
                + " left join organization.teacher t on t.id=b.teacher_id"
                + " left join organization.room r on r.id=b.room_id where b.id=?",
            id);
    if (row == null) throw new IllegalStateException("不可用时间写入后不可见");
    return blockViewOf(row);
  }

  // ---------------------------------------------------------------- 内部

  private record Plan(
      UUID schoolId,
      UUID teacherId,
      String teacherName,
      UUID roomId,
      String roomName,
      String topic,
      BigDecimal hours,
      List<ScheduleSlot> slots) {}

  private SchedulePreview previewInTx(UUID classId, ScheduleRule rule, Actor actor) {
    var plan = plan(classId, rule, actor);
    return preview(plan, classId);
  }

  private Plan plan(UUID classId, ScheduleRule rule, Actor actor) {
    return plan(classId, rule, actor, List.of());
  }

  private Plan plan(UUID classId, ScheduleRule rule, Actor actor, List<Reservation> reservations) {
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

    var policy = policy(schoolId);
    var assignment =
        selectResources(classId, schoolId, rule, dates, startsAt, endsAt, policy, reservations);
    var slots = new ArrayList<ScheduleSlot>();
    for (var date : dates) {
      var clash =
          assignment.failureReason() == null
              ? conflict(
                  classId,
                  date,
                  startsAt,
                  endsAt,
                  assignment.teacher().id(),
                  assignment.teacher().name(),
                  assignment.room().id(),
                  assignment.room().name(),
                  policy,
                  reservations)
              : new Clash(assignment.failureReason(), "HARD");
      var hard = clash != null && "HARD".equals(clash.severity());
      slots.add(
          new ScheduleSlot(
              date.toString(),
              startsAt.toString(),
              endsAt.toString(),
              !hard,
              clash == null ? null : clash.code(),
              clash == null ? null : clash.severity()));
    }

    return new Plan(
        schoolId,
        assignment.teacher().id(),
        assignment.teacher().name(),
        assignment.room().id(),
        assignment.room().name(),
        topic,
        hours,
        slots);
  }

  private record ResourceAssignment(ResourceRef teacher, ResourceRef room, String failureReason) {}

  private ResourceAssignment selectResources(
      UUID classId,
      UUID schoolId,
      ScheduleRule rule,
      List<LocalDate> dates,
      LocalTime startsAt,
      LocalTime endsAt,
      Policy policy,
      List<Reservation> reservations) {
    var fixedTeacher = resource(rule.teacherId(), "TEACHER", schoolId, rule.teacherName());
    var fixedRoom = resource(rule.roomId(), "ROOM", schoolId, rule.roomName());
    if (!rule.autoAssignResources()) return new ResourceAssignment(fixedTeacher, fixedRoom, null);

    var teachers =
        fixedTeacher.id() != null || fixedTeacher.name() != null
            ? List.of(fixedTeacher)
            : sql.fetch(
                    "select id,name from organization.teacher where school_id=? and active order by name,id",
                    schoolId)
                .map(
                    row ->
                        new ResourceRef(row.get("id", UUID.class), row.get("name", String.class)));
    if (teachers.isEmpty())
      return new ResourceAssignment(fixedTeacher, fixedRoom, "NO_TEACHER_CANDIDATE");

    var memberCount =
        sql.fetchOne(
                "select count(*) as total from teaching.enrollment_class where class_id=? and active",
                classId)
            .get("total", Long.class);
    var rooms =
        fixedRoom.id() != null || fixedRoom.name() != null
            ? List.of(fixedRoom)
            : sql.fetch(
                    "select id,name from organization.room where school_id=? and active"
                        + " and (capacity is null or capacity>=?) order by name,id",
                    schoolId,
                    memberCount)
                .map(
                    row ->
                        new ResourceRef(row.get("id", UUID.class), row.get("name", String.class)));
    if (rooms.isEmpty())
      return new ResourceAssignment(fixedTeacher, fixedRoom, "NO_ROOM_CANDIDATE");

    ResourceAssignment best = null;
    int bestAvailable = -1;
    for (var teacher : teachers) {
      for (var room : rooms) {
        int available = 0;
        for (var date : dates) {
          var clash =
              conflict(
                  classId,
                  date,
                  startsAt,
                  endsAt,
                  teacher.id(),
                  teacher.name(),
                  room.id(),
                  room.name(),
                  policy,
                  reservations);
          if (clash == null || "WARN".equals(clash.severity())) available++;
        }
        if (available > bestAvailable) {
          bestAvailable = available;
          best = new ResourceAssignment(teacher, room, null);
        }
      }
    }
    if (bestAvailable <= 0)
      return new ResourceAssignment(fixedTeacher, fixedRoom, "NO_FEASIBLE_RESOURCE_PAIR");
    return best;
  }

  /**
   * 冲突判定（参考旧系统 ClashTypeDictionary 的分类）。
   *
   * <p>HARD = 阻断：重复、本班重叠、教师/教室课程冲突、资源在不可用时间。 只有学校策略显式放宽时，资源不可用才降为 WARN（对应旧系统的 ignore 配置）。
   */
  private Clash conflict(
      UUID classId,
      LocalDate date,
      LocalTime startsAt,
      LocalTime endsAt,
      UUID teacherId,
      String teacherName,
      UUID roomId,
      String roomName,
      Policy policy) {
    return conflict(
        classId,
        date,
        startsAt,
        endsAt,
        teacherId,
        teacherName,
        roomId,
        roomName,
        policy,
        List.of());
  }

  private Clash conflict(
      UUID classId,
      LocalDate date,
      LocalTime startsAt,
      LocalTime endsAt,
      UUID teacherId,
      String teacherName,
      UUID roomId,
      String roomName,
      Policy policy,
      List<Reservation> reservations) {
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
    if (duplicate != null) return new Clash("DUPLICATE", "HARD");
    for (var reservation : reservations) {
      if (!reservation.date().equals(date)) continue;
      var overlaps =
          startsAt.isBefore(reservation.endsAt()) && endsAt.isAfter(reservation.startsAt());
      if (overlaps && classId.equals(reservation.classId()))
        return new Clash("LESSON_OVERLAP", "HARD");
      var teacherMatches =
          sameResource(teacherId, teacherName, reservation.teacherId(), reservation.teacherName());
      if (overlaps && teacherMatches) return new Clash("TEACHER_CLASH", "HARD");
      if (teacherMatches && policy.minGapMinutes() > 0) {
        var gap = policy.minGapMinutes();
        if (startsAt.isBefore(reservation.endsAt().plusMinutes(gap))
            && endsAt.isAfter(reservation.startsAt().minusMinutes(gap)))
          return new Clash("TEACHER_MIN_GAP", "HARD");
      }
      if (overlaps && sameResource(roomId, roomName, reservation.roomId(), reservation.roomName()))
        return new Clash("ROOM_CLASH", "HARD");
    }
    var classClash =
        sql.fetchOne(
            "select id from teaching.lesson where class_id=? and lesson_date=? and starts_at<?"
                + " and ends_at>? limit 1",
            classId,
            date,
            ends,
            starts);
    if (classClash != null) return new Clash("LESSON_OVERLAP", "HARD");
    if (teacherId != null || teacherName != null) {
      var teacherClash =
          sql.fetchOne(
              "select id from teaching.lesson where lesson_date=? and starts_at<? and ends_at>?"
                  + " and (teacher_id=? or teacher_name=?) limit 1",
              date,
              ends,
              starts,
              teacherId,
              teacherName);
      if (teacherClash != null) return new Clash("TEACHER_CLASH", "HARD");
      // 最小间隔：旧系统的 MinSpaceMinute，默认 0 表示关闭。
      if (policy.minGapMinutes() > 0) {
        // 在 Java 里算好间隔边界，避免把分钟数绑成 interval 参数。
        var gapStarts = Time.valueOf(startsAt.minusMinutes(policy.minGapMinutes()));
        var gapEnds = Time.valueOf(endsAt.plusMinutes(policy.minGapMinutes()));
        var gapClash =
            sql.fetchOne(
                "select id from teaching.lesson where lesson_date=?"
                    + " and (teacher_id=? or teacher_name=?) and ends_at>? and starts_at<? limit 1",
                date,
                teacherId,
                teacherName,
                gapStarts,
                gapEnds);
        if (gapClash != null) return new Clash("TEACHER_MIN_GAP", "HARD");
      }
      var blocked =
          sql.fetchOne(
              "select id from teaching.resource_block where teacher_id=? and block_date=?"
                  + " and starts_at<? and ends_at>? limit 1",
              teacherId,
              date,
              ends,
              starts);
      if (blocked != null)
        return new Clash("TEACHER_UNAVAILABLE", policy.blockTeacherUnavailable() ? "HARD" : "WARN");
    }
    if (roomId != null || roomName != null) {
      var roomClash =
          sql.fetchOne(
              "select id from teaching.lesson where lesson_date=? and starts_at<? and ends_at>?"
                  + " and (room_id=? or room_name=?) limit 1",
              date,
              ends,
              starts,
              roomId,
              roomName);
      if (roomClash != null) return new Clash("ROOM_CLASH", "HARD");
      var blocked =
          sql.fetchOne(
              "select id from teaching.resource_block where room_id=? and block_date=?"
                  + " and starts_at<? and ends_at>? limit 1",
              roomId,
              date,
              ends,
              starts);
      if (blocked != null)
        return new Clash("ROOM_UNAVAILABLE", policy.blockRoomUnavailable() ? "HARD" : "WARN");
    }
    return null;
  }

  private boolean sameResource(UUID leftId, String leftName, UUID rightId, String rightName) {
    return (leftId != null && leftId.equals(rightId))
        || (leftName != null && leftName.equals(rightName));
  }

  private record Clash(String code, String severity) {}

  private record Policy(
      boolean blockTeacherUnavailable, boolean blockRoomUnavailable, int minGapMinutes) {}

  private Policy policy(UUID schoolId) {
    var row =
        sql.fetchOne(
            "select block_teacher_unavailable,block_room_unavailable,min_gap_minutes"
                + " from teaching.schedule_policy where school_id=?",
            schoolId);
    if (row == null) return new Policy(true, true, 0);
    return new Policy(
        row.get("block_teacher_unavailable", Boolean.class),
        row.get("block_room_unavailable", Boolean.class),
        row.get("min_gap_minutes", Integer.class));
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

  /** 教师/教室：给了实体就校验并取名称快照，否则退回自由文本。 */
  private ResourceRef resource(UUID id, String kind, UUID schoolId, String fallbackName) {
    if (id == null) return new ResourceRef(null, trim(fallbackName, 50));
    var table = "TEACHER".equals(kind) ? "organization.teacher" : "organization.room";
    var row =
        sql.fetchOne(
            "select id,name,active from " + table + " where id=? and school_id=?", id, schoolId);
    if (row == null) throw new ScheduleConflict("RESOURCE_NOT_IN_SCHOOL");
    if (!row.get("active", Boolean.class)) throw new ScheduleConflict("RESOURCE_INACTIVE");
    return new ResourceRef(row.get("id", UUID.class), row.get("name", String.class));
  }

  private record ResourceRef(UUID id, String name) {}

  private String date(LocalDate value) {
    return value == null ? null : value.toString();
  }

  private String time(LocalTime value) {
    return value == null ? null : value.format(DateTimeFormatter.ofPattern("HH:mm"));
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
