package org.teraschool.teaching.api;

import java.util.UUID;

/** 学员进度：一条报名下一门课程的课时消耗与课节进度。 */
public record StudentProgressView(
    UUID studentId,
    String studentName,
    String studentNo,
    UUID enrollmentId,
    String productName,
    UUID curriculumId,
    String curriculumName,
    String totalHours,
    String consumedHours,
    String remainingHours,
    int attendedLessons,
    int totalLessons,
    String nextLessonDate,
    String nextStartsAt) {}
