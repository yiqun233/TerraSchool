package org.teraschool.archive.api;

import java.util.UUID;

/** 撤销一笔学员档案合并。 */
public record ReverseStudentMerge(UUID requestId, Long expectedSourceVersion, String reason) {}
