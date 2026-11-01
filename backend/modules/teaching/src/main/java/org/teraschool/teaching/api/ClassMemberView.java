package org.teraschool.teaching.api;

import java.util.UUID;

/** 在班报名；考勤与消费都按 enrollId 记。 */
public record ClassMemberView(
    UUID enrollmentId, String studentName, String joinedOn, String status) {}
