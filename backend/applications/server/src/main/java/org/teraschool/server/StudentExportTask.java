package org.teraschool.server;

import java.time.Instant;
import java.util.UUID;

record StudentExportTask(
    UUID id,
    UUID schoolId,
    String status,
    int attemptCount,
    Integer rowCount,
    String errorCode,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt) {}
