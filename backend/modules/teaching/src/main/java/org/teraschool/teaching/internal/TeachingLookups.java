package org.teraschool.teaching.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.teraschool.identity.api.ResourceScope;
import org.teraschool.teaching.api.LessonView;

/**
 * 教务共享读取与格式化。
 *
 * <p>“这一行是否可读”“课时/金额如何展示”“报名累计消费多少”这三件事，写路径（TeachingService）
 * 与只读报表（TeachingReporting）必须用同一口径；集中在这里，避免两处各写一份后慢慢分叉。
 */
@Component
class TeachingLookups {
  private final DSLContext sql;

  TeachingLookups(DSLContext sql) {
    this.sql = sql;
  }

  void requireReadable(ResourceScope scope, UUID schoolId) {
    if (schoolId == null) throw new IllegalArgumentException("需要学校");
    if (!scope.readableSchoolIds().contains(schoolId))
      throw new AccessDeniedException("无该学校的教务读取权限");
  }

  Record requireClass(ResourceScope scope, UUID id) {
    var row =
        sql.fetchOne(
            "select c.id,c.school_id,c.curriculum_id,c.name,c.status,c.version,c.group_training,c.class_type,c.capacity,"
                + "cur.name as curriculum_name,"
                + "(select count(*) from teaching.enrollment_class m where m.class_id=c.id and m.active)"
                + " as member_count from teaching.class c"
                + " join product.curriculum cur on cur.id=c.curriculum_id where c.id=?",
            id);
    if (row == null || !scope.readableSchoolIds().contains(row.get("school_id", UUID.class)))
      throw new AccessDeniedException("班级不存在或不可访问");
    return row;
  }

  LessonView lessonView(Record row) {
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

  BigDecimal consumedHours(UUID enrollmentId) {
    return consumedHours(enrollmentId, null);
  }

  BigDecimal consumedHours(UUID enrollmentId, UUID curriculumId) {
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

  String hours(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  String money(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
  }

  String date(LocalDate value) {
    return value == null ? null : value.toString();
  }

  String time(LocalTime value) {
    return value == null ? null : value.toString();
  }
}
