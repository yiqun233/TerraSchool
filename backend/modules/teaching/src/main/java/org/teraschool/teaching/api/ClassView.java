package org.teraschool.teaching.api;

import java.util.UUID;

/** 班级视图；课程来自课程主数据，班级属于一个学校。 */
public record ClassView(
    UUID id,
    UUID schoolId,
    String schoolName,
    UUID curriculumId,
    String curriculumName,
    String name,
    String status,
    boolean groupTraining,
    int memberCount,
    long version,
    Integer capacity) {}
