package org.teraschool.teaching.api;

/** 只带版本的课次动作（发布、关闭）。 */
public record LessonVersion(Long expectedVersion) {}
