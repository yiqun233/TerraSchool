package org.teraschool.admissions.api;

import java.util.UUID;

/** 可逆客户线索合并结果；源记录及其历史关联仍保留。 */
public record CustomerMergeResult(
    UUID sourceId, UUID primaryId, long sourceVersion, long primaryVersion) {}
