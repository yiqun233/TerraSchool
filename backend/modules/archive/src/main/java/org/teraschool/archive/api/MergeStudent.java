package org.teraschool.archive.api;

import java.util.UUID;

/** 将无业务事实的重复档案合并为指定主档；不重写源档历史或联系人。 */
public record MergeStudent(
    UUID requestId,
    UUID primaryStudentId,
    Long expectedSourceVersion,
    Long expectedPrimaryVersion,
    String reason) {}
