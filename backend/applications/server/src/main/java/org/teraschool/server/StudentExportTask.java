package org.teraschool.server;

import java.time.Instant;
import java.util.UUID;

record StudentExportTask(
    UUID id,
    UUID schoolId,
    String exportType,
    String format,
    String status,
    int attemptCount,
    Integer rowCount,
    String errorCode,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {}
