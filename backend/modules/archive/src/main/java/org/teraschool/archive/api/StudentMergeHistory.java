package org.teraschool.archive.api;

import java.util.UUID;

public record StudentMergeHistory(
    UUID mergeId,
    UUID sourceStudentId,
    String sourceStudentNo,
    String sourceName,
    long sourceVersion,
    String mergedAt,
    String reversedAt) {}
