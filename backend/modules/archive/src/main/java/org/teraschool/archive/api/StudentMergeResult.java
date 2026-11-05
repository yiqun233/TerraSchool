package org.teraschool.archive.api;

import java.util.UUID;

public record StudentMergeResult(
    UUID mergeId,
    UUID sourceStudentId,
    UUID primaryStudentId,
    String sourceStudentNo,
    String sourceName,
    long sourceVersion,
    long primaryVersion,
    String mergedAt) {}
