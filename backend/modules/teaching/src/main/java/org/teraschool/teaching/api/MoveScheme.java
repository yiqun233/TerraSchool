package org.teraschool.teaching.api;

import java.util.List;
import java.util.UUID;

/**
 * 拖拽可行性矩阵：行=日期，列=学校时段（以源课次时长截断/补齐）。 status ∈ FREE / CLASS_OCCUPIED / STUDENT_CLASH / TEACHER_CLASH
 * / ROOM_CLASH / OTHER。
 */
public record MoveScheme(UUID lessonId, List<Day> days) {

  public record Day(String date, List<Cell> cells) {}

  public record Cell(String startsAt, String endsAt, String status, String reason) {}
}
