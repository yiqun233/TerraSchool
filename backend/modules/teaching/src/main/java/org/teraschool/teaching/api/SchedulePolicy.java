package org.teraschool.teaching.api;

/**
 * 学校级排课策略。
 *
 * <p>默认全部按阻断处理；把对应开关关掉即"只警告"，对应旧系统的 ignore 配置。
 */
public record SchedulePolicy(
    boolean blockTeacherUnavailable,
    boolean blockRoomUnavailable,
    int minGapMinutes,
    Long expectedVersion,
    int minutesPerHour) {

  /** 兼容旧调用：不传折算规则时按 60 分钟/课时。 */
  public SchedulePolicy(
      boolean blockTeacherUnavailable,
      boolean blockRoomUnavailable,
      int minGapMinutes,
      Long expectedVersion) {
    this(blockTeacherUnavailable, blockRoomUnavailable, minGapMinutes, expectedVersion, 60);
  }
}
