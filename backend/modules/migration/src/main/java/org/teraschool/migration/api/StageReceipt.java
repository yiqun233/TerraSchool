package org.teraschool.migration.api;

/** 暂存一条旧库收款：按学员号关联，金额与日期参与对账。 */
public record StageReceipt(
    String legacyId, String studentNo, String amount, String method, String occurredOn) {}
